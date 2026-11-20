package vn.swoosh.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.bouncycastle.crypto.generators.SCrypt;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
@ConditionalOnProperty(name = "swoosh.api.monolith.enabled", havingValue = "true")
public class StoreApiController {
    private static final long SESSION_SECONDS = 8 * 60 * 60;
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9._-]{3,40}");
    private static final Pattern PHONE = Pattern.compile("[0-9]{9,12}");
    private static final Pattern CITIZEN_ID = Pattern.compile("[0-9]{12}");
    private static final Set<String> PERMISSIONS = Set.of("orders.read", "orders.update");
    private static final Set<Integer> SIZES = Set.of(38, 39, 40, 41, 42, 43, 44, 45);
    private final JdbcTemplate db;
    private final StoreApplication.StoreProperties properties;
    private final ObjectMapper json;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, List<Long>> loginAttempts = new ConcurrentHashMap<>();

    public StoreApiController(JdbcTemplate db, StoreApplication.StoreProperties properties, ObjectMapper json) {
        this.db = db;
        this.properties = properties;
        this.json = json;
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, String>> handleApiError(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("error", error.getReason() == null ? "Yêu cầu không hợp lệ." : error.getReason()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, String>> handleUnexpected(Exception error) {
        error.printStackTrace();
        return ResponseEntity.status(500).body(Map.of("error", "Máy chủ gặp lỗi. Vui lòng thử lại."));
    }

    @GetMapping("/health")
    Map<String, Object> health() {
        return Map.of("status", "ok", "time", Instant.now().toString());
    }

    @GetMapping("/session")
    ResponseEntity<Map<String, Object>> session(HttpServletRequest request, HttpServletResponse response) {
        Map<String, Object> session = currentSession(request);
        if (session == null) {
            return createSession(null, response);
        }
        Map<String, Object> body = new HashMap<>();
        body.put("csrfToken", session.get("csrf_token"));
        body.put("user", session.get("user_id") == null ? null : sessionUser(session));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    @PostMapping("/login")
    ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, Object> data,
                                               HttpServletRequest request,
                                               HttpServletResponse response) {
        Map<String, Object> guest = currentSession(request);
        if (guest == null) fail(403, "Phiên đăng nhập hết hạn. Hãy tải lại trang.");
        requireCsrf(guest, request);
        String ip = clientIp(request);
        List<Long> attempts = loginAttempts.compute(ip, (key, old) -> {
            List<Long> recent = old == null ? new ArrayList<>() : new ArrayList<>(old);
            recent.removeIf(value -> System.nanoTime() - value > 300_000_000_000L);
            if (recent.size() < 10) recent.add(System.nanoTime());
            return recent;
        });
        if (attempts.size() >= 10) fail(429, "Bạn đã thử đăng nhập quá nhiều lần. Hãy chờ 5 phút.");
        String username = text(data.get("username")).trim();
        String password = text(data.get("password"));
        if (username.length() > 40 || password.length() > 256) fail(400, "Tên đăng nhập hoặc mật khẩu không hợp lệ.");
        List<Map<String, Object>> users = db.queryForList("SELECT * FROM users WHERE username=?", username);
        if (users.isEmpty() || number(users.get(0).get("active")) == 0
                || !passwordMatches(password, text(users.get(0).get("password_hash")))) {
            recordActivity(username.isBlank() ? "Không rõ" : username, "Đăng nhập thất bại", "auth", "", ip);
            fail(401, "Tên đăng nhập hoặc mật khẩu không đúng.");
        }
        Map<String, Object> user = users.get(0);
        db.update("DELETE FROM sessions WHERE token_hash=?", guest.get("token_hash"));
        Map<String, Object> created = createSession(number(user.get("id")), response).getBody();
        recordActivity(username, "Đăng nhập", "auth", "", ip);
        loginAttempts.remove(ip);
        Map<String, Object> body = new HashMap<>(created);
        body.put("user", sessionUser(user));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    @PostMapping("/logout")
    Map<String, Object> logout(HttpServletRequest request, HttpServletResponse response) {
        Map<String, Object> session = currentSession(request);
        if (session == null) fail(401, "Phiên đăng nhập đã hết hạn.");
        requireCsrf(session, request);
        db.update("DELETE FROM sessions WHERE token_hash=?", session.get("token_hash"));
        recordActivity(text(session.get("username")), "Đăng xuất", "auth", "", clientIp(request));
        expireCookie(request, response);
        return Map.of("ok", true);
    }

    @GetMapping("/products")
    Map<String, Object> products() {
        return Map.of("products", db.queryForList("SELECT * FROM products WHERE active=1 ORDER BY name").stream().map(this::productJson).toList());
    }

    @GetMapping("/admin/products")
    Map<String, Object> adminProducts(HttpServletRequest request) {
        requireRole(request, "admin");
        return Map.of("products", db.queryForList("SELECT * FROM products ORDER BY name").stream().map(this::productJson).toList());
    }

    @GetMapping("/admin/users")
    Map<String, Object> users(HttpServletRequest request) {
        requireRole(request, "admin");
        return Map.of("users", db.queryForList("""
            SELECT users.id,users.username,users.role,users.active,users.created_at,
                   users.full_name,users.address,users.citizen_id,users.birth_date,
                   users.role_id,staff_roles.name AS role_name
            FROM users LEFT JOIN staff_roles ON staff_roles.id=users.role_id
            ORDER BY users.role,users.username
            """));
    }

    @GetMapping("/admin/roles")
    Map<String, Object> roles(HttpServletRequest request) {
        requireRole(request, "admin");
        return Map.of("roles", db.queryForList("SELECT * FROM staff_roles ORDER BY id").stream().map(row -> {
            Map<String, Object> role = new HashMap<>(row);
            role.put("permissions", readJson(text(row.get("permissions"))));
            return role;
        }).toList());
    }

    @GetMapping("/orders")
    Map<String, Object> orders(@RequestParam(defaultValue = "") String q,
                               @RequestParam(defaultValue = "") String status,
                               HttpServletRequest request) {
        Map<String, Object> session = requirePermission(request, "orders.read");
        if (!status.isEmpty() && !Set.of("pending", "confirmed", "shipping", "completed", "cancelled").contains(status)) {
            fail(400, "Trạng thái lọc không hợp lệ.");
        }
        String search = "%" + q.trim() + "%";
        List<Map<String, Object>> rows = db.queryForList("""
            SELECT * FROM orders WHERE (?='' OR status=?) AND
              (?='' OR order_number LIKE ? OR customer_name LIKE ? OR phone LIKE ?)
            ORDER BY id DESC LIMIT 500
            """, status, status, q.trim(), search, search, search);
        return Map.of("orders", rows.stream().map(this::orderJson).toList());
    }

    @GetMapping("/admin/revenue")
    Map<String, Object> revenue(@RequestParam(name="from") String from,
                                @RequestParam(name="to") String to,
                                HttpServletRequest request) {
        requireRole(request, "admin");
        LocalDate start = parseDate(from);
        LocalDate end = parseDate(to);
        if (end.isBefore(start)) fail(400, "Ngày kết thúc phải từ ngày bắt đầu trở đi.");
        if (end.isAfter(LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh")))) fail(400, "Ngày kết thúc không thể nằm trong tương lai.");
        if (end.toEpochDay() - start.toEpochDay() > 365) fail(400, "Mỗi lần thống kê chỉ hỗ trợ tối đa 366 ngày.");
        String startUtc = start.atStartOfDay(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toInstant().toString();
        String endUtc = end.plusDays(1).atStartOfDay(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toInstant().toString();
        Map<String, Object> totals = db.queryForMap("""
            SELECT COALESCE(SUM(CASE WHEN status='completed' THEN total ELSE 0 END),0) AS revenue,
                   SUM(CASE WHEN status='completed' THEN 1 ELSE 0 END) AS completed_order_count,
                   COALESCE(AVG(CASE WHEN status='completed' THEN total END),0) AS average_order_value,
                   COALESCE(SUM(CASE WHEN status IN ('confirmed','shipping') THEN total ELSE 0 END),0) AS in_progress_revenue,
                   SUM(CASE WHEN status IN ('confirmed','shipping') THEN 1 ELSE 0 END) AS in_progress_order_count
            FROM orders WHERE datetime(created_at)>=datetime(?) AND datetime(created_at)<datetime(?)
            """, startUtc, endUtc);
        Map<String, Object> body = new HashMap<>();
        body.put("from", from);
        body.put("to", to);
        body.put("revenue", number(totals.get("revenue")));
        body.put("completedOrderCount", number(totals.get("completed_order_count")));
        body.put("averageOrderValue", Math.round(decimal(totals.get("average_order_value"))));
        body.put("inProgressRevenue", number(totals.get("in_progress_revenue")));
        body.put("inProgressOrderCount", number(totals.get("in_progress_order_count")));
        body.put("daily", db.queryForList("""
            SELECT date(created_at,'+7 hours') AS day,SUM(total) AS revenue,COUNT(*) AS order_count
            FROM orders WHERE status='completed' AND datetime(created_at)>=datetime(?)
              AND datetime(created_at)<datetime(?) GROUP BY date(created_at,'+7 hours') ORDER BY day
            """, startUtc, endUtc));
        return body;
    }

    @GetMapping("/top-products")
    Map<String, Object> topProducts() {
        return Map.of("products", db.queryForList("""
            SELECT order_items.product_id AS id,order_items.product_name AS name,
                   SUM(order_items.quantity) AS quantity,
                   SUM(order_items.quantity*order_items.unit_price) AS revenue
            FROM order_items JOIN orders ON orders.id=order_items.order_id
            WHERE orders.status IN ('confirmed','shipping','completed')
            GROUP BY order_items.product_id,order_items.product_name
            ORDER BY quantity DESC,revenue DESC LIMIT 10
            """));
    }

    @GetMapping("/size-chart")
    Map<String, Object> sizeChart() {
        return Map.of("sizes", List.of(
            Map.of("eu",38,"us","5.5","uk","5","cm",24),
            Map.of("eu",39,"us","6.5","uk","6","cm",24.5),
            Map.of("eu",40,"us","7","uk","6","cm",25),
            Map.of("eu",41,"us","8","uk","7","cm",26),
            Map.of("eu",42,"us","8.5","uk","7.5","cm",26.5),
            Map.of("eu",43,"us","9.5","uk","8.5","cm",27.5),
            Map.of("eu",44,"us","10","uk","9","cm",28),
            Map.of("eu",45,"us","11","uk","10","cm",29)
        ));
    }

    @GetMapping("/vouchers")
    Map<String, Object> activeVouchers() {
        return Map.of("vouchers", db.queryForList("""
            SELECT code,discount_type,discount_value,min_total,starts_at,expires_at
            FROM vouchers WHERE active=1 ORDER BY code
            """));
    }

    @GetMapping("/admin/vouchers")
    Map<String, Object> adminVouchers(HttpServletRequest request) {
        requireRole(request, "admin");
        return Map.of("vouchers", db.queryForList("SELECT * FROM vouchers ORDER BY code"));
    }

    @GetMapping("/admin/logs")
    Map<String, Object> logs(@RequestParam(defaultValue = "") String q, HttpServletRequest request) {
        requireRole(request, "admin");
        String search = "%" + q.trim() + "%";
        return Map.of("logs", db.queryForList("""
            SELECT actor,action,entity,entity_id,created_at,ip_address FROM activity_logs
            WHERE ?='' OR actor LIKE ? OR action LIKE ? OR entity LIKE ? OR entity_id LIKE ?
            ORDER BY id DESC LIMIT 500
            """, q.trim(), search, search, search, search));
    }

    @GetMapping("/admin/loyalty")
    Map<String, Object> adminLoyalty(HttpServletRequest request) {
        requireRole(request, "admin");
        return Map.of("tiers", db.queryForList("SELECT * FROM loyalty_tiers ORDER BY min_points"),
            "accounts", db.queryForList("SELECT phone,full_name,points,created_at FROM loyalty_accounts ORDER BY points DESC LIMIT 500"));
    }

    @GetMapping("/admin/media")
    Map<String, Object> adminMedia(HttpServletRequest request) {
        requireRole(request, "admin");
        List<Map<String, Object>> rows = db.queryForList("SELECT * FROM product_media ORDER BY id DESC");
        return Map.of("media", rows.stream().map(row -> {
            Map<String, Object> media = new HashMap<>(row);
            media.put("url", "/images/products/" + row.get("filename"));
            return media;
        }).toList());
    }

    @GetMapping("/admin/customers")
    Map<String, Object> customers(@RequestParam(defaultValue = "") String q, HttpServletRequest request) {
        requireRole(request, "admin");
        String search = "%" + q.trim() + "%";
        return Map.of("customers", db.queryForList("""
            WITH ranked_orders AS (
              SELECT phone,customer_name,created_at,
                     ROW_NUMBER() OVER(PARTITION BY phone ORDER BY id DESC) AS position FROM orders
            ), customer_totals AS (
              SELECT phone,COUNT(*) AS order_count,
                     SUM(CASE WHEN status!='cancelled' THEN total ELSE 0 END) AS total_spent,
                     MAX(created_at) AS last_order_at FROM orders GROUP BY phone
            )
            SELECT totals.phone,ranked.customer_name AS full_name,totals.order_count,totals.total_spent,
                   totals.last_order_at,COALESCE(accounts.points,0) AS points,
                   (SELECT name FROM loyalty_tiers WHERE min_points<=COALESCE(accounts.points,0)
                    ORDER BY min_points DESC LIMIT 1) AS tier_name
            FROM customer_totals totals JOIN ranked_orders ranked
              ON ranked.phone=totals.phone AND ranked.position=1
            LEFT JOIN loyalty_accounts accounts ON accounts.phone=totals.phone
            WHERE (?='' OR totals.phone LIKE ? OR ranked.customer_name LIKE ?)
            ORDER BY totals.last_order_at DESC LIMIT 500
            """, q.trim(), search, search));
    }

    @GetMapping("/loyalty")
    Map<String, Object> loyalty(@RequestParam String phone, @RequestParam(name="order") String orderNumber) {
        if (!PHONE.matcher(phone).matches() || !Pattern.matches("SW-[A-Fa-f0-9]{8}", orderNumber)) {
            fail(400, "Nhập số điện thoại và mã đơn hàng hợp lệ.");
        }
        if (db.queryForList("SELECT 1 FROM orders WHERE order_number=? AND phone=? AND status='completed'", orderNumber, phone).isEmpty()) {
            fail(404, "Không tìm thấy đơn hàng hoàn tất khớp với thông tin này.");
        }
        List<Map<String, Object>> accounts = db.queryForList("SELECT phone,full_name,points FROM loyalty_accounts WHERE phone=?", phone);
        List<Map<String, Object>> tiers = db.queryForList("SELECT name,min_points,benefits FROM loyalty_tiers ORDER BY min_points");
        long points = accounts.isEmpty() ? 0 : number(accounts.get(0).get("points"));
        Map<String, Object> tier = tiers.stream().filter(row -> number(row.get("min_points")) <= points)
            .max(Comparator.comparingLong(row -> number(row.get("min_points")))).orElse(null);
        Map<String, Object> account = accounts.isEmpty()
            ? Map.of("phone", phone, "full_name", "", "points", 0) : accounts.get(0);
        return Map.of("account", account, "tier", tier == null ? Map.of() : tier, "tiers", tiers);
    }

    @GetMapping("/admin/revenue/guard")
    void revenueGuard(HttpServletRequest request) {
        requireRole(request, "admin");
    }

    @PostMapping("/admin/users")
    ResponseEntity<Map<String, Object>> createStaff(@RequestBody Map<String, Object> data,
                                                     HttpServletRequest request) {
        Map<String, Object> admin = mutateAsAdmin(request, data);
        String username = text(data.get("username")).trim();
        String password = text(data.get("password"));
        if (!USERNAME.matcher(username).matches()) fail(400, "Tên đăng nhập phải có 3–40 ký tự hợp lệ.");
        if (password.length() < 12 || password.length() > 256) fail(400, "Mật khẩu phải có từ 12 đến 256 ký tự.");
        Map<String, String> profile = validateProfile(data, true);
        int roleId = integer(data.getOrDefault("roleId", 1), "Vai trò nhân viên không hợp lệ.");
        if (db.queryForList("SELECT 1 FROM staff_roles WHERE id=?", roleId).isEmpty()) fail(400, "Vai trò nhân viên không tồn tại.");
        try {
            db.update("""
                INSERT INTO users(username,password_hash,role,active,created_at,full_name,address,citizen_id,birth_date,role_id)
                VALUES(?,?,'staff',1,?,?,?,?,?,?)
                """, username, passwordHash(password), Instant.now().toString(),
                profile.get("full_name"), profile.get("address"), profile.get("citizen_id"),
                profile.get("birth_date"), roleId);
        } catch (DataIntegrityViolationException error) {
            if (!db.queryForList("SELECT 1 FROM users WHERE username=?", username).isEmpty()) fail(409, "Tên đăng nhập đã tồn tại.");
            fail(409, "Số CCCD đã được sử dụng cho nhân viên khác.");
        }
        recordActivity(text(admin.get("username")), "Tạo tài khoản nhân viên", "user", username, clientIp(request));
        return ResponseEntity.status(201).body(Map.of("ok", true));
    }

    @PostMapping("/admin/products")
    ResponseEntity<Map<String, Object>> createProduct(@RequestBody Map<String, Object> data,
                                                       HttpServletRequest request) {
        Map<String, Object> admin = mutateAsAdmin(request, data);
        Map<String, Object> product = validateProduct(data, null);
        try {
            db.update("INSERT INTO products(id,name,price,stock,active,bg,colors) VALUES(?,?,?,?,?,?,?)",
                product.get("id"), product.get("name"), product.get("price"), product.get("stock"),
                product.get("active"), product.get("bg"), writeJson(product.get("colors")));
        } catch (DataIntegrityViolationException error) {
            fail(409, "Mã sản phẩm đã tồn tại.");
        }
        recordActivity(text(admin.get("username")), "Thêm sản phẩm", "product", product.get("id"), clientIp(request));
        return ResponseEntity.status(201).body(Map.of("product", product));
    }

    @PostMapping("/admin/vouchers")
    ResponseEntity<Map<String, Object>> createVoucher(@RequestBody Map<String, Object> data,
                                                        HttpServletRequest request) {
        Map<String, Object> admin = mutateAsAdmin(request, data);
        Map<String, Object> voucher = validateVoucher(data, null);
        try {
            db.update("""
                INSERT INTO vouchers(code,discount_type,discount_value,min_total,starts_at,expires_at,usage_limit,active)
                VALUES(?,?,?,?,?,?,?,?)
                """, voucher.get("code"), voucher.get("discount_type"), voucher.get("discount_value"),
                voucher.get("min_total"), voucher.get("starts_at"), voucher.get("expires_at"),
                voucher.get("usage_limit"), voucher.get("active"));
        } catch (DataIntegrityViolationException error) {
            fail(409, "Mã voucher đã tồn tại.");
        }
        recordActivity(text(admin.get("username")), "Tạo voucher", "voucher", voucher.get("code"), clientIp(request));
        return ResponseEntity.status(201).body(Map.of("voucher", voucher));
    }

    @PostMapping("/admin/roles")
    ResponseEntity<Map<String, Object>> createRole(@RequestBody Map<String, Object> data,
                                                     HttpServletRequest request) {
        Map<String, Object> admin = mutateAsAdmin(request, data);
        String name = text(data.get("name")).trim();
        List<String> permissions = validatePermissions(data.get("permissions"));
        if (name.length() < 2 || name.length() > 60) fail(400, "Tên vai trò hoặc danh sách quyền không hợp lệ.");
        KeyHolder key = new GeneratedKeyHolder();
        try {
            db.update(connection -> {
                var statement = connection.prepareStatement("INSERT INTO staff_roles(name,permissions) VALUES(?,?)", Statement.RETURN_GENERATED_KEYS);
                statement.setString(1, name);
                statement.setString(2, writeJson(permissions));
                return statement;
            }, key);
        } catch (DataIntegrityViolationException error) {
            fail(409, "Tên vai trò đã tồn tại.");
        }
        long id = key.getKey().longValue();
        recordActivity(text(admin.get("username")), "Tạo vai trò", "role", id, clientIp(request));
        return ResponseEntity.status(201).body(Map.of("id", id, "name", name, "permissions", permissions));
    }

    @PostMapping("/admin/loyalty/tiers")
    ResponseEntity<Map<String, Object>> createTier(@RequestBody Map<String, Object> data,
                                                     HttpServletRequest request) {
        Map<String, Object> admin = mutateAsAdmin(request, data);
        String name = text(data.get("name")).trim();
        int points = integer(data.get("minPoints"), "Thông tin hạng thành viên không hợp lệ.");
        String benefits = text(data.get("benefits")).trim();
        if (name.length() < 2 || name.length() > 40 || points < 0 || benefits.length() > 200) {
            fail(400, "Thông tin hạng thành viên không hợp lệ.");
        }
        try {
            db.update("INSERT INTO loyalty_tiers(name,min_points,benefits) VALUES(?,?,?)", name, points, benefits);
        } catch (DataIntegrityViolationException error) {
            fail(409, "Tên hạng hoặc mốc điểm đã tồn tại.");
        }
        recordActivity(text(admin.get("username")), "Tạo hạng thành viên", "loyalty_tier", name, clientIp(request));
        return ResponseEntity.status(201).body(Map.of("ok", true));
    }

    @PostMapping("/admin/media")
    ResponseEntity<Map<String, Object>> uploadMedia(@RequestBody Map<String, Object> data,
                                                     HttpServletRequest request) {
        Map<String, Object> admin = mutateAsAdmin(request, data);
        String dataUrl = text(data.get("dataUrl"));
        String altText = text(data.get("altText"));
        if (altText.length() > 150) fail(400, "Thông tin ảnh không hợp lệ.");
        var match = Pattern.compile("^data:image/(png|jpeg|webp);base64,([A-Za-z0-9+/=]+)$").matcher(dataUrl);
        if (!match.matches()) fail(400, "Chỉ hỗ trợ ảnh PNG, JPEG hoặc WebP.");
        String format = match.group(1);
        byte[] imageBytes;
        try {
            imageBytes = Base64.getDecoder().decode(match.group(2));
        } catch (IllegalArgumentException error) {
            fail(400, "Dữ liệu ảnh không hợp lệ.");
            return ResponseEntity.badRequest().build();
        }
        boolean valid = switch (format) {
            case "png" -> imageBytes.length >= 45 && startsWith(imageBytes, new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a})
                && new String(imageBytes, 12, 4, StandardCharsets.US_ASCII).equals("IHDR")
                && endsWith(imageBytes, new byte[]{0x49, 0x45, 0x4e, 0x44, (byte) 0xae, 0x42, 0x60, (byte) 0x82});
            case "jpeg" -> imageBytes.length >= 20 && imageBytes[0] == (byte) 0xff && imageBytes[1] == (byte) 0xd8
                && imageBytes[2] == (byte) 0xff && imageBytes[imageBytes.length - 2] == (byte) 0xff && imageBytes[imageBytes.length - 1] == (byte) 0xd9;
            case "webp" -> imageBytes.length >= 30 && new String(imageBytes, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
                && new String(imageBytes, 8, 4, StandardCharsets.US_ASCII).equals("WEBP")
                && Integer.toUnsignedLong(java.nio.ByteBuffer.wrap(imageBytes, 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt()) == imageBytes.length - 8L;
            default -> false;
        };
        if (!valid || imageBytes.length == 0 || imageBytes.length > 700 * 1024) fail(400, "Ảnh vượt quá 700 KB hoặc định dạng tệp không khớp.");
        String extension = format.equals("jpeg") ? ".jpg" : "." + format;
        String filename = randomHex(12) + extension;
        Path directory = properties.root().toAbsolutePath().normalize().resolve("images/products");
        try {
            Files.createDirectories(directory);
            Files.write(directory.resolve(filename), imageBytes);
        } catch (IOException error) {
            throw new IllegalStateException("Không thể lưu ảnh sản phẩm.", error);
        }
        db.update("INSERT INTO product_media(filename,alt_text,created_at) VALUES(?,?,?)",
            filename, altText.trim(), Instant.now().toString());
        recordActivity(text(admin.get("username")), "Tải ảnh sản phẩm", "media", filename, clientIp(request));
        return ResponseEntity.status(201).body(Map.of("filename", filename, "url", "/images/products/" + filename));
    }

    @PostMapping("/vouchers/check")
    Map<String, Object> checkVoucher(@RequestBody Map<String, Object> data, HttpServletRequest request) {
        Map<String, Object> session = currentSession(request);
        if (session == null) fail(403, "Phiên kiểm tra voucher đã hết hạn. Hãy tải lại trang.");
        requireCsrf(session, request);
        long subtotal = longNumber(data.get("subtotal"), "Giá trị đơn hàng không hợp lệ.");
        if (subtotal < 0) fail(400, "Giá trị đơn hàng không hợp lệ.");
        Map<String, Object> voucher = findVoucher(text(data.get("code")), subtotal);
        long discount = discount(voucher, subtotal);
        return Map.of("code", voucher.get("code"), "discount", discount, "total", subtotal - discount);
    }

    @PostMapping("/orders")
    @org.springframework.transaction.annotation.Transactional
    ResponseEntity<Map<String, Object>> createOrder(@RequestBody Map<String, Object> data,
                                                      HttpServletRequest request) {
        Map<String, Object> session = currentSession(request);
        if (session == null) fail(403, "Phiên đặt hàng hết hạn. Hãy tải lại trang.");
        requireCsrf(session, request);
        String name = text(data.get("name")).trim();
        String phone = text(data.get("phone"));
        String address = text(data.get("address")).trim();
        String note = text(data.get("note"));
        Object rawItems = data.get("items");
        if (name.isEmpty() || name.length() > 100) fail(400, "Vui lòng nhập họ tên hợp lệ.");
        if (!PHONE.matcher(phone).matches()) fail(400, "Số điện thoại cần có từ 9 đến 12 chữ số.");
        if (address.length() < 5 || address.length() > 200) fail(400, "Vui lòng nhập địa chỉ nhận hàng hợp lệ.");
        if (note.length() > 500) fail(400, "Ghi chú không hợp lệ.");
        if (!(rawItems instanceof List<?>)) fail(400, "Đơn hàng không có sản phẩm hợp lệ.");
        List<?> items = (List<?>) rawItems;
        if (items.isEmpty() || items.size() > 20) fail(400, "Đơn hàng không có sản phẩm hợp lệ.");
        if (!db.queryForList("SELECT 1 FROM orders WHERE phone=? AND created_at>=datetime('now','-45 seconds') AND status!='cancelled' LIMIT 1", phone).isEmpty()) {
            fail(429, "Đơn hàng vừa được gửi. Vui lòng chờ trước khi đặt thêm đơn.");
        }
        List<Map<String, Object>> clean = new ArrayList<>();
        long subtotal = 0;
        int totalQuantity = 0;
        for (Object raw : items) {
            if (!(raw instanceof Map<?, ?>)) fail(400, "Sản phẩm trong đơn không hợp lệ.");
            Map<?, ?> item = (Map<?, ?>) raw;
            String id = text(item.get("id"));
            int size = integer(item.get("size"), "Cỡ hoặc số lượng sản phẩm không hợp lệ.");
            int quantity = integer(item.get("quantity"), "Cỡ hoặc số lượng sản phẩm không hợp lệ.");
            if (!SIZES.contains(size) || quantity < 1 || quantity > 99) fail(400, "Cỡ hoặc số lượng sản phẩm không hợp lệ.");
            List<Map<String, Object>> products = db.queryForList("SELECT id,name,price,stock FROM products WHERE id=? AND active=1", id);
            if (products.isEmpty()) fail(409, "Một sản phẩm trong giỏ không còn được bán.");
            Map<String, Object> product = products.get(0);
            if (quantity > number(product.get("stock"))) fail(409, "Sản phẩm " + product.get("name") + " không đủ tồn kho.");
            db.update("UPDATE products SET stock=stock-? WHERE id=?", quantity, id);
            long unitPrice = number(product.get("price")) * 1000;
            subtotal += unitPrice * quantity;
            totalQuantity += quantity;
            clean.add(Map.of("id", id, "name", product.get("name"), "size", size,
                "quantity", quantity, "unitPrice", unitPrice));
        }
        Map<String, Object> voucher = data.get("voucherCode") == null || text(data.get("voucherCode")).isBlank()
            ? null : findVoucher(text(data.get("voucherCode")), subtotal);
        long discount = voucher == null ? 0 : discount(voucher, subtotal);
        long total = subtotal - discount;
        String orderNumber = "SW-" + randomHex(4).toUpperCase();
        String now = Instant.now().toString();
        db.update("""
            INSERT INTO orders(order_number,customer_name,phone,address,note,total,status,created_at,updated_at,voucher_code,discount)
            VALUES(?,?,?,?,?,?,'pending',?,?,?,?)
            """, orderNumber, name, phone, address, note, total, now, now,
            voucher == null ? null : voucher.get("code"), discount);
        long orderId = db.queryForObject("SELECT id FROM orders WHERE order_number=?", Long.class, orderNumber);
        for (Map<String, Object> item : clean) {
            db.update("""
                INSERT INTO order_items(order_id,product_id,product_name,size,quantity,unit_price)
                VALUES(?,?,?,?,?,?)
                """, orderId, item.get("id"), item.get("name"), item.get("size"), item.get("quantity"), item.get("unitPrice"));
        }
        if (voucher != null) db.update("UPDATE vouchers SET used_count=used_count+1 WHERE code=?", voucher.get("code"));
        recordActivity("Khách hàng", "Tạo đơn hàng", "order", orderNumber, clientIp(request));
        long customerOrders = db.queryForObject("SELECT COUNT(*) FROM orders WHERE phone=? AND created_at>=datetime('now','-24 hours')", Long.class, phone);
        if (total >= 20_000_000 || totalQuantity >= 10 || customerOrders > 3) {
            recordActivity("Hệ thống", "Cảnh báo rủi ro đơn hàng", "order", orderNumber, clientIp(request));
        }
        return ResponseEntity.status(201).body(Map.of(
            "orderNumber", orderNumber, "subtotal", subtotal, "discount", discount,
            "total", total, "status", "pending"));
    }

    @PatchMapping("/admin/products/{id}")
    Map<String, Object> updateProduct(@PathVariable String id, @RequestBody Map<String, Object> data,
                                      HttpServletRequest request) {
        Map<String, Object> admin = mutateAsAdmin(request, data);
        Map<String, Object> product = validateProduct(data, id);
        List<Map<String, Object>> old = db.queryForList("SELECT active FROM products WHERE id=?", id);
        if (old.isEmpty()) fail(404, "Không tìm thấy sản phẩm.");
        if (number(old.get(0).get("active")) != 0 && Boolean.FALSE.equals(product.get("active"))
                && number(db.queryForObject("SELECT COUNT(*) FROM products WHERE active=1", Long.class)) <= 1) {
            fail(409, "Cần duy trì ít nhất một sản phẩm đang bán.");
        }
        db.update("UPDATE products SET name=?,price=?,stock=?,active=? WHERE id=?",
            product.get("name"), product.get("price"), product.get("stock"), product.get("active"), id);
        recordActivity(text(admin.get("username")), "Cập nhật sản phẩm", "product", id, clientIp(request));
        return Map.of("product", product);
    }

    @PatchMapping("/admin/users/{id}")
    Map<String, Object> updateStaff(@PathVariable long id, @RequestBody Map<String, Object> data,
                                     HttpServletRequest request) {
        Map<String, Object> admin = mutateAsAdmin(request, data);
        Set<String> profileFields = Set.of("full_name", "address", "citizen_id", "birth_date");
        if (data.keySet().stream().noneMatch(key -> Set.of("username", "password", "active", "roleId").contains(key) || profileFields.contains(key))) {
            fail(400, "Không có thông tin nào để cập nhật.");
        }
        List<String> columns = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        if (data.containsKey("username")) {
            String username = text(data.get("username")).trim();
            if (!USERNAME.matcher(username).matches()) fail(400, "Tên đăng nhập phải có 3–40 ký tự hợp lệ.");
            columns.add("username=?");
            values.add(username);
        }
        String password = text(data.get("password"));
        if (!password.isEmpty()) {
            if (password.length() < 12 || password.length() > 256) fail(400, "Mật khẩu mới phải có từ 12 đến 256 ký tự.");
            columns.add("password_hash=?");
            values.add(passwordHash(password));
        }
        if (data.containsKey("active")) {
            if (!(data.get("active") instanceof Boolean active)) fail(400, "Trạng thái tài khoản không hợp lệ.");
            columns.add("active=?");
            values.add(Boolean.TRUE.equals(data.get("active")) ? 1 : 0);
        }
        if (data.containsKey("roleId")) {
            int roleId = integer(data.get("roleId"), "Vai trò nhân viên không hợp lệ.");
            if (db.queryForList("SELECT 1 FROM staff_roles WHERE id=?", roleId).isEmpty()) fail(400, "Vai trò nhân viên không tồn tại.");
            columns.add("role_id=?");
            values.add(roleId);
        }
        Map<String, String> profile = validateProfile(data.entrySet().stream()
            .filter(entry -> profileFields.contains(entry.getKey()))
            .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue())), false);
        for (Map.Entry<String, String> entry : profile.entrySet()) {
            columns.add(entry.getKey() + "=?");
            values.add(entry.getValue());
        }
        if (columns.isEmpty()) fail(400, "Hãy nhập thông tin để cập nhật.");
        values.add(id);
        try {
            int updated = db.update("UPDATE users SET " + String.join(",", columns) + " WHERE id=? AND role='staff'", values.toArray());
            if (updated == 0) fail(404, "Không tìm thấy nhân viên.");
        } catch (DataIntegrityViolationException error) {
            if (data.containsKey("username") && !db.queryForList("SELECT 1 FROM users WHERE username=? AND id!=?", text(data.get("username")).trim(), id).isEmpty()) {
                fail(409, "Tên đăng nhập đã được sử dụng.");
            }
            fail(409, "Số CCCD đã được sử dụng cho nhân viên khác.");
        }
        if (Boolean.FALSE.equals(data.get("active")) || !password.isEmpty()) {
            db.update("DELETE FROM sessions WHERE user_id=?", id);
        }
        recordActivity(text(admin.get("username")), "Cập nhật nhân viên", "user", id, clientIp(request));
        return Map.of("ok", true);
    }

    @PatchMapping("/admin/vouchers/{code}")
    Map<String, Object> updateVoucher(@PathVariable String code, @RequestBody Map<String, Object> data,
                                       HttpServletRequest request) {
        Map<String, Object> admin = mutateAsAdmin(request, data);
        Map<String, Object> voucher = validateVoucher(data, code);
        int updated = db.update("""
            UPDATE vouchers SET discount_type=?,discount_value=?,min_total=?,starts_at=?,expires_at=?,usage_limit=?,active=?
            WHERE code=? COLLATE NOCASE
            """, voucher.get("discount_type"), voucher.get("discount_value"), voucher.get("min_total"),
            voucher.get("starts_at"), voucher.get("expires_at"), voucher.get("usage_limit"),
            voucher.get("active"), code);
        if (updated == 0) fail(404, "Không tìm thấy voucher.");
        recordActivity(text(admin.get("username")), "Cập nhật voucher", "voucher", code, clientIp(request));
        return Map.of("ok", true);
    }

    @PatchMapping("/admin/roles/{id}")
    Map<String, Object> updateRole(@PathVariable long id, @RequestBody Map<String, Object> data,
                                    HttpServletRequest request) {
        Map<String, Object> admin = mutateAsAdmin(request, data);
        String name = text(data.get("name")).trim();
        List<String> permissions = validatePermissions(data.get("permissions"));
        if (name.length() < 2 || name.length() > 60) fail(400, "Tên vai trò hoặc danh sách quyền không hợp lệ.");
        try {
            if (db.update("UPDATE staff_roles SET name=?,permissions=? WHERE id=?", name, writeJson(permissions), id) == 0) {
                fail(404, "Không tìm thấy vai trò.");
            }
        } catch (DataIntegrityViolationException error) {
            fail(409, "Tên vai trò đã tồn tại.");
        }
        recordActivity(text(admin.get("username")), "Cập nhật vai trò", "role", id, clientIp(request));
        return Map.of("ok", true);
    }

    @PatchMapping("/admin/loyalty/tiers/{id}")
    Map<String, Object> updateTier(@PathVariable long id, @RequestBody Map<String, Object> data,
                                    HttpServletRequest request) {
        Map<String, Object> admin = mutateAsAdmin(request, data);
        String name = text(data.get("name")).trim();
        int points = integer(data.get("minPoints"), "Thông tin hạng thành viên không hợp lệ.");
        String benefits = text(data.get("benefits")).trim();
        if (name.length() < 2 || name.length() > 40 || points < 0 || benefits.length() > 200) fail(400, "Thông tin hạng thành viên không hợp lệ.");
        List<Map<String, Object>> old = db.queryForList("SELECT min_points FROM loyalty_tiers WHERE id=?", id);
        if (old.isEmpty()) fail(404, "Không tìm thấy hạng thành viên.");
        if (number(old.get(0).get("min_points")) == 0 && points != 0) fail(409, "Hạng cơ bản phải giữ mốc 0 điểm.");
        try {
            db.update("UPDATE loyalty_tiers SET name=?,min_points=?,benefits=? WHERE id=?", name, points, benefits, id);
        } catch (DataIntegrityViolationException error) {
            fail(409, "Tên hạng hoặc mốc điểm đã tồn tại.");
        }
        recordActivity(text(admin.get("username")), "Cập nhật hạng thành viên", "loyalty_tier", id, clientIp(request));
        return Map.of("ok", true);
    }

    @PatchMapping("/orders/{id}")
    @org.springframework.transaction.annotation.Transactional
    Map<String, Object> updateOrder(@PathVariable long id, @RequestBody Map<String, Object> data,
                                    HttpServletRequest request) {
        Map<String, Object> session = requirePermission(request, "orders.update");
        requireCsrf(session, request);
        String status = text(data.get("status"));
        Map<String, Set<String>> transitions = Map.of(
            "pending", Set.of("confirmed", "cancelled"),
            "confirmed", Set.of("shipping", "cancelled"),
            "shipping", Set.of("completed", "cancelled"),
            "completed", Set.of(), "cancelled", Set.of());
        List<Map<String, Object>> rows = db.queryForList("SELECT status,phone,customer_name,total,points_awarded FROM orders WHERE id=?", id);
        if (rows.isEmpty()) fail(404, "Không tìm thấy đơn hàng.");
        Map<String, Object> order = rows.get(0);
        if (!transitions.getOrDefault(text(order.get("status")), Set.of()).contains(status)) {
            fail(409, "Không thể chuyển đơn hàng sang trạng thái này.");
        }
        if ("cancelled".equals(status)) {
            for (Map<String, Object> item : db.queryForList("SELECT product_id,quantity FROM order_items WHERE order_id=?", id)) {
                db.update("UPDATE products SET stock=stock+? WHERE id=?", item.get("quantity"), item.get("product_id"));
            }
        }
        db.update("UPDATE orders SET status=?,updated_at=? WHERE id=?", status, Instant.now().toString(), id);
        if ("completed".equals(status) && number(order.get("points_awarded")) == 0) {
            long points = number(order.get("total")) / 10000;
            db.update("""
                INSERT INTO loyalty_accounts(phone,full_name,points,created_at) VALUES(?,?,?,?)
                ON CONFLICT(phone) DO UPDATE SET full_name=excluded.full_name,points=loyalty_accounts.points+excluded.points
                """, order.get("phone"), order.get("customer_name"), points, Instant.now().toString());
            db.update("UPDATE orders SET points_awarded=? WHERE id=?", points, id);
        }
        recordActivity(text(session.get("username")), "Cập nhật trạng thái đơn hàng", "order", id, clientIp(request));
        return Map.of("ok", true, "status", status);
    }

    @DeleteMapping("/admin/vouchers/{code}")
    Map<String, Object> deleteVoucher(@PathVariable String code, HttpServletRequest request) {
        Map<String, Object> admin = requireRole(request, "admin");
        requireCsrf(admin, request);
        if (db.update("DELETE FROM vouchers WHERE code=? COLLATE NOCASE", code) == 0) fail(404, "Không tìm thấy voucher.");
        recordActivity(text(admin.get("username")), "Xóa voucher", "voucher", code, clientIp(request));
        return Map.of("ok", true);
    }

    @DeleteMapping("/admin/roles/{id}")
    Map<String, Object> deleteRole(@PathVariable long id, HttpServletRequest request) {
        Map<String, Object> admin = requireRole(request, "admin");
        requireCsrf(admin, request);
        if (id == 1) fail(409, "Không thể xóa vai trò mặc định.");
        if (!db.queryForList("SELECT 1 FROM staff_roles WHERE id=?", id).isEmpty()
                && !db.queryForList("SELECT 1 FROM users WHERE role_id=? LIMIT 1", id).isEmpty()) {
            fail(409, "Hãy chuyển nhân viên sang vai trò khác trước khi xóa.");
        }
        if (db.update("DELETE FROM staff_roles WHERE id=?", id) == 0) fail(404, "Không tìm thấy vai trò.");
        recordActivity(text(admin.get("username")), "Xóa vai trò", "role", id, clientIp(request));
        return Map.of("ok", true);
    }

    @DeleteMapping("/admin/loyalty/tiers/{id}")
    Map<String, Object> deleteTier(@PathVariable long id, HttpServletRequest request) {
        Map<String, Object> admin = requireRole(request, "admin");
        requireCsrf(admin, request);
        if (number(db.queryForObject("SELECT COUNT(*) FROM loyalty_tiers", Long.class)) <= 1) fail(409, "Cần duy trì ít nhất một hạng thành viên.");
        List<Map<String, Object>> tier = db.queryForList("SELECT min_points FROM loyalty_tiers WHERE id=?", id);
        if (tier.isEmpty()) fail(404, "Không tìm thấy hạng thành viên.");
        if (number(tier.get(0).get("min_points")) == 0) fail(409, "Không thể xóa hạng cơ bản 0 điểm.");
        db.update("DELETE FROM loyalty_tiers WHERE id=?", id);
        recordActivity(text(admin.get("username")), "Xóa hạng thành viên", "loyalty_tier", id, clientIp(request));
        return Map.of("ok", true);
    }

    @DeleteMapping("/admin/media/{filename}")
    Map<String, Object> deleteMedia(@PathVariable String filename, HttpServletRequest request) {
        Map<String, Object> admin = requireRole(request, "admin");
        requireCsrf(admin, request);
        if (!Pattern.matches("[a-f0-9]{24}\\.(png|jpg|webp)", filename)) fail(400, "Tệp media không hợp lệ.");
        if (db.update("DELETE FROM product_media WHERE filename=?", filename) == 0) fail(404, "Không tìm thấy ảnh.");
        try {
            Files.deleteIfExists(properties.root().toAbsolutePath().normalize().resolve("images/products").resolve(filename));
        } catch (IOException error) {
            throw new IllegalStateException("Không thể xóa ảnh sản phẩm.", error);
        }
        recordActivity(text(admin.get("username")), "Xóa ảnh sản phẩm", "media", filename, clientIp(request));
        return Map.of("ok", true);
    }

    private Map<String, Object> mutateAsAdmin(HttpServletRequest request, Map<String, Object> data) {
        Map<String, Object> admin = requireRole(request, "admin");
        requireCsrf(admin, request);
        return admin;
    }

    private ResponseEntity<Map<String, Object>> createSession(Long userId, HttpServletResponse response) {
        byte[] tokenBytes = new byte[32];
        byte[] csrfBytes = new byte[32];
        random.nextBytes(tokenBytes);
        random.nextBytes(csrfBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        String csrf = Base64.getUrlEncoder().withoutPadding().encodeToString(csrfBytes);
        long expires = Instant.now().getEpochSecond() + SESSION_SECONDS;
        String hash = sha256(token);
        db.update("INSERT INTO sessions(token_hash,csrf_token,user_id,expires_at) VALUES(?,?,?,?)", hash, csrf, userId, expires);
        boolean secure = properties.cookieSecure();
        String cookie = "swoosh_session=" + token + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=" + SESSION_SECONDS + (secure ? "; Secure" : "");
        response.addHeader(HttpHeaders.SET_COOKIE, cookie);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("csrfToken", csrf));
    }

    private void expireCookie(HttpServletRequest request, HttpServletResponse response) {
        boolean secure = properties.cookieSecure() || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto"));
        response.addHeader(HttpHeaders.SET_COOKIE, "swoosh_session=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0" + (secure ? "; Secure" : ""));
    }

    private Map<String, Object> currentSession(HttpServletRequest request) {
        String token = null;
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) if ("swoosh_session".equals(cookie.getName())) token = cookie.getValue();
        }
        if (token == null || token.isBlank()) return null;
        List<Map<String, Object>> sessions = db.queryForList("""
            SELECT sessions.token_hash,sessions.csrf_token,sessions.user_id,sessions.expires_at,
                   users.username,users.role,users.active,users.role_id
            FROM sessions LEFT JOIN users ON users.id=sessions.user_id
            WHERE sessions.token_hash=? AND sessions.expires_at>?
            """, sha256(token), Instant.now().getEpochSecond());
        if (sessions.isEmpty() || (sessions.get(0).get("user_id") != null && number(sessions.get(0).get("active")) == 0)) return null;
        return sessions.get(0);
    }

    private Map<String, Object> requireRole(HttpServletRequest request, String... allowed) {
        Map<String, Object> session = currentSession(request);
        if (session == null || session.get("user_id") == null) fail(401, "Vui lòng đăng nhập.");
        for (String role : allowed) if (role.equals(session.get("role"))) return session;
        fail(403, "Tài khoản không có quyền thực hiện thao tác này.");
        return session;
    }

    private Map<String, Object> requirePermission(HttpServletRequest request, String permission) {
        Map<String, Object> session = requireRole(request, "admin", "staff");
        if ("admin".equals(session.get("role"))) return session;
        if (session.get("role_id") == null) fail(403, "Tài khoản chưa được gán vai trò.");
        List<Map<String, Object>> role = db.queryForList("SELECT permissions FROM staff_roles WHERE id=?", session.get("role_id"));
        Object rawPermissions = role.isEmpty() ? null : readJson(text(role.get(0).get("permissions")));
        if (!(rawPermissions instanceof List<?> permissions) || !permissions.contains(permission)) {
            fail(403, "Vai trò của bạn không có quyền thực hiện thao tác này.");
        }
        return session;
    }

    private void requireCsrf(Map<String, Object> session, HttpServletRequest request) {
        String supplied = request.getHeader("X-CSRF-Token");
        if (supplied == null || !MessageDigest.isEqual(text(session.get("csrf_token")).getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
            fail(403, "Phiên gửi biểu mẫu hết hạn. Hãy tải lại trang rồi thử lại.");
        }
    }

    private Map<String, Object> sessionUser(Map<String, Object> row) {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("username", row.get("username"));
        user.put("role", row.get("role"));
        if ("staff".equals(row.get("role"))) {
            List<Map<String, Object>> roles = db.queryForList("SELECT permissions FROM staff_roles WHERE id=?", row.get("role_id"));
            user.put("permissions", roles.isEmpty() ? List.of() : readJson(text(roles.get(0).get("permissions"))));
        }
        return user;
    }

    private Map<String, Object> productJson(Map<String, Object> row) {
        Map<String, Object> product = new LinkedHashMap<>(row);
        product.put("active", number(row.get("active")) != 0);
        product.put("colors", readJson(text(row.get("colors"))));
        return product;
    }

    private Map<String, Object> orderJson(Map<String, Object> row) {
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("id", row.get("id"));
        order.put("number", row.get("order_number"));
        order.put("customerName", row.get("customer_name"));
        order.put("phone", row.get("phone"));
        order.put("address", row.get("address"));
        order.put("note", row.get("note"));
        order.put("total", row.get("total"));
        order.put("status", row.get("status"));
        order.put("createdAt", row.get("created_at"));
        order.put("voucherCode", row.get("voucher_code"));
        order.put("discount", row.get("discount"));
        order.put("items", db.queryForList("SELECT product_id,product_name,size,quantity,unit_price FROM order_items WHERE order_id=?", row.get("id")));
        return order;
    }

    private boolean passwordMatches(String password, String stored) {
        try {
            String[] parts = stored.split("\\$", 3);
            if (parts.length != 3 || !"scrypt".equals(parts[0])) return false;
            byte[] salt = HexFormat.of().parseHex(parts[1]);
            byte[] expected = HexFormat.of().parseHex(parts[2]);
            byte[] actual = SCrypt.generate(password.getBytes(StandardCharsets.UTF_8), salt, 1 << 14, 8, 1, expected.length);
            return MessageDigest.isEqual(actual, expected);
        } catch (RuntimeException error) {
            return false;
        }
    }

        private String passwordHash(String password) {
            byte[] salt = new byte[16];
            random.nextBytes(salt);
            byte[] hash = SCrypt.generate(password.getBytes(StandardCharsets.UTF_8), salt, 1 << 14, 8, 1, 64);
            return "scrypt$" + HexFormat.of().formatHex(salt) + "$" + HexFormat.of().formatHex(hash);
        }

        private Map<String, Object> validateProduct(Map<String, Object> data, String pathId) {
            String id = pathId == null ? text(data.get("id")) : pathId;
            String name = text(data.get("name")).trim();
            int price = integer(data.get("price"), "Giá sản phẩm phải từ 1 đến 100.000 (nghìn đồng).");
            int stock = integer(data.get("stock"), "Tồn kho phải từ 0 đến 100.000.");
            Object activeValue = data.getOrDefault("active", Boolean.TRUE);
            if (!Pattern.matches("[a-z0-9-]{2,40}", id)) fail(400, "Mã sản phẩm chỉ được gồm chữ thường, số và dấu gạch ngang.");
            if (name.length() < 2 || name.length() > 100 || !Pattern.matches("[\\p{L}\\p{N}_\\s.,'’()-]{2,100}", name)) {
                fail(400, "Tên sản phẩm phải có từ 2 đến 100 ký tự.");
            }
            if (price < 1 || price > 100000) fail(400, "Giá sản phẩm phải từ 1 đến 100.000 (nghìn đồng).");
            if (stock < 0 || stock > 100000) fail(400, "Tồn kho phải từ 0 đến 100.000.");
            if (!(activeValue instanceof Boolean)) fail(400, "Trạng thái sản phẩm không hợp lệ.");
            Map<String, Object> product = new LinkedHashMap<>();
            product.put("id", id);
            product.put("name", name);
            product.put("price", price);
            product.put("stock", stock);
            product.put("active", activeValue);
            product.put("bg", "#e8e5df");
            product.put("colors", List.of(List.of("#ffffff", "#111111", "#cccccc")));
            return product;
        }

        private Map<String, String> validateProfile(Map<?, ?> data, boolean required) {
            Map<String, String> profile = new LinkedHashMap<>();
            for (String field : List.of("full_name", "address", "citizen_id", "birth_date")) {
                if (!data.containsKey(field) && !required) continue;
                Object raw = data.get(field);
                if (!(raw instanceof String)) fail(400, "Vui lòng nhập đầy đủ họ tên, địa chỉ, số CCCD và ngày sinh.");
                String value = ((String) raw).trim();
                switch (field) {
                    case "full_name" -> {
                        if (value.length() < 2 || value.length() > 100) fail(400, "Họ tên phải có từ 2 đến 100 ký tự.");
                    }
                    case "address" -> {
                        if (value.length() < 5 || value.length() > 200) fail(400, "Địa chỉ phải có từ 5 đến 200 ký tự.");
                    }
                    case "citizen_id" -> {
                        if (!CITIZEN_ID.matcher(value).matches()) fail(400, "Số CCCD phải gồm đúng 12 chữ số.");
                    }
                    case "birth_date" -> {
                        LocalDate date = parseDate(value);
                        if (date.isBefore(LocalDate.of(1900, 1, 1)) || date.isAfter(LocalDate.now())) fail(400, "Ngày sinh phải từ năm 1900 đến hiện tại.");
                    }
                }
                profile.put(field, value);
            }
            if (required && profile.size() != 4) fail(400, "Vui lòng nhập đầy đủ họ tên, địa chỉ, số CCCD và ngày sinh.");
            return profile;
        }

        private Map<String, Object> validateVoucher(Map<String, Object> data, String pathCode) {
            String code = (pathCode == null ? text(data.get("code")) : pathCode).trim();
            String type = text(data.get("discountType"));
            int value = integer(data.get("discountValue"), "Giá trị giảm giá phải là số nguyên dương.");
            int minTotal = integer(data.getOrDefault("minTotal", 0), "Giá trị đơn hàng tối thiểu không hợp lệ.");
            Object limitValue = data.get("usageLimit");
            Integer limit = limitValue == null || text(limitValue).isBlank() ? null : integer(limitValue, "Giới hạn lượt sử dụng phải là số nguyên dương.");
            Object active = data.getOrDefault("active", Boolean.TRUE);
            if (!Pattern.matches("[A-Za-z0-9_-]{3,30}", code)) fail(400, "Mã voucher phải gồm 3–30 ký tự chữ, số, gạch ngang hoặc gạch dưới.");
            if (!Set.of("percent", "fixed").contains(type)) fail(400, "Loại giảm giá không hợp lệ.");
            if (value < 1 || ("percent".equals(type) && value > 100)) fail(400, "Giá trị giảm giá không hợp lệ.");
            if (minTotal < 0 || (limit != null && limit < 1) || !(active instanceof Boolean)) fail(400, "Thông tin voucher không hợp lệ.");
            String startsAt = normalizeDateTime(text(data.get("startsAt")));
            String expiresAt = normalizeDateTime(text(data.get("expiresAt")));
            if (!startsAt.isEmpty() && !expiresAt.isEmpty() && !Instant.parse(startsAt).isBefore(Instant.parse(expiresAt))) {
                fail(400, "Ngày hết hạn phải sau ngày bắt đầu.");
            }
            Map<String, Object> voucher = new LinkedHashMap<>();
            voucher.put("code", code.toUpperCase());
            voucher.put("discount_type", type);
            voucher.put("discount_value", value);
            voucher.put("min_total", minTotal);
            voucher.put("starts_at", startsAt.isEmpty() ? null : startsAt);
            voucher.put("expires_at", expiresAt.isEmpty() ? null : expiresAt);
            voucher.put("usage_limit", limit);
            voucher.put("active", (Boolean) active ? 1 : 0);
            return voucher;
        }

        private Map<String, Object> findVoucher(String code, long subtotal) {
            if (code.isBlank() || !Pattern.matches("[A-Za-z0-9_-]{3,30}", code.trim())) fail(400, "Mã voucher không hợp lệ.");
            List<Map<String, Object>> rows = db.queryForList("SELECT * FROM vouchers WHERE code=? COLLATE NOCASE", code.trim());
            if (rows.isEmpty()) fail(400, "Voucher không tồn tại, đã hết hạn hoặc đã hết lượt sử dụng.");
            Map<String, Object> voucher = rows.get(0);
            Instant now = Instant.now();
            String startsAt = text(voucher.get("starts_at"));
            String expiresAt = text(voucher.get("expires_at"));
            if (number(voucher.get("active")) == 0
                    || (!startsAt.isBlank() && Instant.parse(startsAt).isAfter(now))
                    || (!expiresAt.isBlank() && Instant.parse(expiresAt).isBefore(now))
                    || (voucher.get("usage_limit") != null && number(voucher.get("used_count")) >= number(voucher.get("usage_limit")))) {
                fail(400, "Voucher không tồn tại, đã hết hạn hoặc đã hết lượt sử dụng.");
            }
            if (subtotal < number(voucher.get("min_total"))) fail(400, "Đơn hàng chưa đạt giá trị tối thiểu để dùng mã này.");
            return voucher;
        }

        private long discount(Map<String, Object> voucher, long subtotal) {
            long amount = "percent".equals(voucher.get("discount_type"))
                ? subtotal * number(voucher.get("discount_value")) / 100
                : number(voucher.get("discount_value"));
            return Math.min(subtotal, amount);
        }

        private List<String> validatePermissions(Object value) {
            if (!(value instanceof List<?> list) || list.stream().anyMatch(item -> !(item instanceof String) || !PERMISSIONS.contains(item))) {
                fail(400, "Tên vai trò hoặc danh sách quyền không hợp lệ.");
            }
            return ((List<?>) value).stream().map(Object::toString).distinct().sorted().toList();
        }

        private String normalizeDateTime(String value) {
            if (value == null || value.isBlank()) return "";
            try {
                return OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.UTC).toString();
            } catch (RuntimeException ignored) {
                try {
                    return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC).toString();
                } catch (RuntimeException error) {
                    fail(400, "Thời hạn voucher không hợp lệ.");
                    return "";
                }
            }
        }

        private String writeJson(Object value) {
            try {
                return json.writeValueAsString(value);
            } catch (IOException error) {
                throw new IllegalStateException("Cannot serialize JSON.", error);
            }
        }

        private String randomHex(int bytes) {
            byte[] value = new byte[bytes];
            random.nextBytes(value);
            return HexFormat.of().formatHex(value);
        }

        private static String extension(String filename) {
            int dot = filename.lastIndexOf('.');
            return dot < 0 ? "" : filename.substring(dot);
        }

        private static boolean startsWith(byte[] data, byte[] prefix) {
            if (data.length < prefix.length) return false;
            for (int i = 0; i < prefix.length; i++) if (data[i] != prefix[i]) return false;
            return true;
        }

        private static boolean endsWith(byte[] data, byte[] suffix) {
            if (data.length < suffix.length) return false;
            int offset = data.length - suffix.length;
            for (int i = 0; i < suffix.length; i++) if (data[offset + i] != suffix[i]) return false;
            return true;
        }

        private static int integer(Object value, String message) {
            if (!(value instanceof Number number) || (number instanceof Double d && d % 1 != 0)
                    || (number instanceof Float f && f % 1 != 0)) {
                fail(400, message);
            }
            try {
                return ((Number) value).intValue();
            } catch (RuntimeException error) {
                fail(400, message);
                return 0;
            }
        }

        private static long longNumber(Object value, String message) {
            if (!(value instanceof Number)) fail(400, message);
            return ((Number) value).longValue();
        }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private Object readJson(String value) {
        try {
            return json.readValue(value, new TypeReference<Object>() {});
        } catch (IOException error) {
            throw new IllegalStateException("Invalid JSON stored in the database.", error);
        }
    }

    private void recordActivity(String actor, String action, String entity, Object id, String ip) {
        db.update("INSERT INTO activity_logs(actor,action,entity,entity_id,created_at,ip_address) VALUES(?,?,?,?,?,?)",
            actor == null || actor.isBlank() ? "Khách" : actor, action, entity, id == null ? "" : id.toString(), Instant.now().toString(), ip);
    }

    private String clientIp(HttpServletRequest request) {
        if ("1".equals(System.getenv("TRUST_PROXY"))) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException error) {
            fail(400, "Ngày bắt đầu và ngày kết thúc không hợp lệ.");
            return LocalDate.MIN;
        }
    }

    private static String text(Object value) { return value == null ? "" : value.toString(); }
    private static long number(Object value) { return value instanceof Number n ? n.longValue() : 0L; }
    private static double decimal(Object value) { return value instanceof Number n ? n.doubleValue() : 0d; }

    private static void fail(int status, String message) {
        throw new ResponseStatusException(HttpStatus.valueOf(status), message);
    }
}
