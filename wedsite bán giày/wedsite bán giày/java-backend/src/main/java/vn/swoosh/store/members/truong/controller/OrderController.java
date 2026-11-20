package vn.swoosh.store.members.truong.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import vn.swoosh.store.CredentialService;
import vn.swoosh.store.SecurityService;
import vn.swoosh.store.StoreSupport;
import vn.swoosh.store.VoucherService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private static final Set<Integer> SIZES = Set.of(38, 39, 40, 41, 42, 43, 44, 45);
    private static final Set<String> STATUSES = Set.of("pending", "confirmed", "shipping", "completed", "cancelled");
    private static final Pattern PHONE = Pattern.compile("[0-9]{9,12}");
    private final JdbcTemplate db;
    private final SecurityService security;
    private final VoucherService vouchers;
    private final CredentialService credentials;

    public OrderController(JdbcTemplate db, SecurityService security, VoucherService vouchers,
                           CredentialService credentials) {
        this.db = db;
        this.security = security;
        this.vouchers = vouchers;
        this.credentials = credentials;
    }

    @GetMapping
    Map<String, Object> list(@RequestParam(defaultValue = "") String q,
                             @RequestParam(defaultValue = "") String status, HttpServletRequest request) {
        security.requirePermission(request, "orders.read");
        if (!status.isEmpty() && !STATUSES.contains(status)) StoreSupport.fail(400, "Trạng thái lọc không hợp lệ.");
        String query = q.trim();
        String search = "%" + query + "%";
        List<Map<String, Object>> rows = db.queryForList("""
            SELECT orders.order_id AS id,orders.order_code AS order_number,
                   orders.recipient_name AS customer_name,orders.recipient_phone AS phone,
                   orders.shipping_address AS address,orders.note,orders.final_amount AS total,
                   LOWER(orders.order_status) AS status,orders.created_at,
                   coupons.code AS voucher_code,orders.discount_amount AS discount
            FROM orders LEFT JOIN coupons ON coupons.coupon_id=orders.coupon_id
            WHERE (?='' OR LOWER(orders.order_status)=LOWER(?)) AND
              (?='' OR orders.order_code LIKE ? OR orders.recipient_name LIKE ? OR orders.recipient_phone LIKE ?)
            ORDER BY orders.order_id DESC OFFSET 0 ROWS FETCH NEXT 500 ROWS ONLY
            """, status, status, query, search, search, search);
        return Map.of("orders", rows.stream().map(row -> StoreSupport.orderJson(db, row)).toList());
    }

    @PostMapping
    @Transactional
    ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> data,
                                               HttpServletRequest request) {
        Map<String, Object> session = security.currentSession(request);
        if (session == null) StoreSupport.fail(403, "Phiên đặt hàng hết hạn. Hãy tải lại trang.");
        security.requireCsrf(session, request);
        String name = StoreSupport.text(data.get("name")).trim();
        String phone = StoreSupport.text(data.get("phone"));
        String address = StoreSupport.text(data.get("address")).trim();
        String note = StoreSupport.text(data.get("note"));
        Object rawItems = data.get("items");
        if (name.isEmpty() || name.length() > 100) StoreSupport.fail(400, "Vui lòng nhập họ tên hợp lệ.");
        if (!PHONE.matcher(phone).matches()) StoreSupport.fail(400, "Số điện thoại cần có từ 9 đến 12 chữ số.");
        if (address.length() < 5 || address.length() > 200) StoreSupport.fail(400, "Vui lòng nhập địa chỉ nhận hàng hợp lệ.");
        if (note.length() > 500) StoreSupport.fail(400, "Ghi chú không hợp lệ.");
        if (!(rawItems instanceof List<?> items) || items.isEmpty() || items.size() > 20) {
            StoreSupport.fail(400, "Đơn hàng không có sản phẩm hợp lệ.");
        }
        if (!db.queryForList("""
                SELECT 1 FROM orders
                WHERE recipient_phone=? AND created_at>=DATEADD(SECOND,-45,SYSUTCDATETIME())
                  AND UPPER(order_status)<>'CANCELLED'
                """, phone).isEmpty()) {
            StoreSupport.fail(429, "Đơn hàng vừa được gửi. Vui lòng chờ trước khi đặt thêm đơn.");
        }

        List<Map<String, Object>> clean = new ArrayList<>();
        long subtotal = 0;
        int totalQuantity = 0;
        for (Object raw : (List<?>) rawItems) {
            if (!(raw instanceof Map<?, ?>)) StoreSupport.fail(400, "Sản phẩm trong đơn không hợp lệ.");
            Map<?, ?> item = (Map<?, ?>) raw;
            String code = StoreSupport.text(item.get("id"));
            int size = StoreSupport.integer(item.get("size"), "Cỡ hoặc số lượng sản phẩm không hợp lệ.");
            int quantity = StoreSupport.integer(item.get("quantity"), "Cỡ hoặc số lượng sản phẩm không hợp lệ.");
            if (!SIZES.contains(size) || quantity < 1 || quantity > 99) {
                StoreSupport.fail(400, "Cỡ hoặc số lượng sản phẩm không hợp lệ.");
            }
            List<Map<String, Object>> products = db.queryForList("""
                SELECT TOP (1) variants.variant_id,shoes.shoe_code,shoes.shoe_name,
                       COALESCE(shoes.sale_price,shoes.base_price) AS price,
                       stocks.stock_quantity-stocks.reserved_quantity AS available
                FROM running_shoes shoes
                JOIN shoe_variants variants ON variants.shoe_id=shoes.shoe_id AND variants.is_active=1
                JOIN size_charts sizes ON sizes.size_id=variants.size_id
                JOIN inventory_stocks stocks ON stocks.variant_id=variants.variant_id
                JOIN warehouses ON warehouses.warehouse_id=stocks.warehouse_id
                WHERE shoes.shoe_code=? AND sizes.size_eu=? AND shoes.status='ACTIVE'
                  AND warehouses.warehouse_name=N'Kho chính'
                ORDER BY variants.variant_id
                """, code, size);
            if (products.isEmpty()) StoreSupport.fail(409, "Một sản phẩm hoặc cỡ trong giỏ không còn được bán.");
            Map<String, Object> product = products.get(0);
            if (quantity > StoreSupport.number(product.get("available"))) {
                StoreSupport.fail(409, "Sản phẩm " + product.get("shoe_name") + " không đủ tồn kho.");
            }
            int changed = db.update("""
                UPDATE inventory_stocks
                SET stock_quantity=stock_quantity-?,updated_at=SYSUTCDATETIME()
                WHERE variant_id=? AND stock_quantity-reserved_quantity>=?
                  AND warehouse_id=(SELECT TOP (1) warehouse_id FROM warehouses WHERE warehouse_name=N'Kho chính')
                """, quantity, product.get("variant_id"), quantity);
            if (changed == 0) StoreSupport.fail(409, "Tồn kho vừa thay đổi. Vui lòng tải lại giỏ hàng.");
            long unitPrice = StoreSupport.number(product.get("price"));
            subtotal += unitPrice * quantity;
            totalQuantity += quantity;
            clean.add(Map.of("variantId", product.get("variant_id"), "id", product.get("shoe_code"),
                "name", product.get("shoe_name"), "size", size, "quantity", quantity, "unitPrice", unitPrice));
        }

        Map<String, Object> voucher = data.get("voucherCode") == null || StoreSupport.text(data.get("voucherCode")).isBlank()
            ? null : vouchers.find(StoreSupport.text(data.get("voucherCode")), subtotal);
        long discount = voucher == null ? 0 : vouchers.discount(voucher, subtotal);
        long total = subtotal - discount;
        String orderCode = "SW-" + java.util.HexFormat.of().formatHex(randomBytes(4)).toUpperCase();
        long userId = "customer".equals(session.get("role")) && session.get("user_id") != null
            ? StoreSupport.number(session.get("user_id"))
            : customerUserId(name, phone);
        Long couponId = voucher == null ? null : StoreSupport.number(voucher.get("coupon_id"));
        Long orderId = db.queryForObject("""
            INSERT INTO orders(
              order_code,user_id,recipient_name,recipient_phone,shipping_address,
              subtotal_amount,discount_amount,coupon_id,final_amount,note,points_awarded,order_status,created_at
            )
            OUTPUT INSERTED.order_id VALUES(?,?,?,?,?,?,?,?,?, ?,0,'PENDING',SYSUTCDATETIME())
            """, Long.class, orderCode, userId, name, phone, address,
            subtotal, discount, couponId, total, note);
        for (Map<String, Object> item : clean) {
            db.update("""
                INSERT INTO order_items(
                  order_id,variant_id,quantity,unit_price,shoe_code_snapshot,
                  shoe_name_snapshot,size_eu_snapshot
                )
                VALUES(?,?,?,?,?,?,?)
                """, orderId, item.get("variantId"), item.get("quantity"), item.get("unitPrice"),
                item.get("id"), item.get("name"), item.get("size"));
        }
        if (voucher != null && db.update("""
                UPDATE coupons SET used_count=used_count+1
                WHERE coupon_id=? AND is_active=1 AND (usage_limit IS NULL OR used_count<usage_limit)
                """, couponId) == 0) {
            StoreSupport.fail(409, "Voucher vừa hết lượt sử dụng. Vui lòng thử lại không dùng mã này.");
        }
        security.recordActivity("Khách hàng", "Tạo đơn hàng", "order", orderCode, request);
        long customerOrders = db.queryForObject("""
            SELECT COUNT(*) FROM orders
            WHERE recipient_phone=? AND created_at>=DATEADD(HOUR,-24,SYSUTCDATETIME())
            """, Long.class, phone);
        if (total >= 20_000_000 || totalQuantity >= 10 || customerOrders > 3) {
            security.recordActivity("Hệ thống", "Cảnh báo rủi ro đơn hàng", "order", orderCode, request);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
            "orderNumber", orderCode, "subtotal", subtotal, "discount", discount,
            "total", total, "status", "pending"));
    }

    @PatchMapping("/{id}")
    @Transactional
    Map<String, Object> updateStatus(@PathVariable long id, @RequestBody Map<String, Object> data,
                                    HttpServletRequest request) {
        Map<String, Object> session = security.requirePermission(request, "orders.update");
        security.requireCsrf(session, request);
        String status = StoreSupport.text(data.get("status"));
        Map<String, Set<String>> transitions = Map.of(
            "pending", Set.of("confirmed", "cancelled"),
            "confirmed", Set.of("shipping", "cancelled"),
            "shipping", Set.of("completed", "cancelled"),
            "completed", Set.of(), "cancelled", Set.of());
        List<Map<String, Object>> rows = db.queryForList("""
            SELECT order_status,recipient_phone,recipient_name,final_amount,points_awarded,user_id
            FROM orders WITH (UPDLOCK,ROWLOCK) WHERE order_id=?
            """, id);
        if (rows.isEmpty()) StoreSupport.fail(404, "Không tìm thấy đơn hàng.");
        Map<String, Object> order = rows.get(0);
        String currentStatus = StoreSupport.text(order.get("order_status")).toLowerCase();
        if (!transitions.getOrDefault(currentStatus, Set.of()).contains(status)) {
            StoreSupport.fail(409, "Không thể chuyển đơn hàng sang trạng thái này.");
        }
        int points = "completed".equals(status) && StoreSupport.number(order.get("points_awarded")) == 0
            ? Math.toIntExact(StoreSupport.number(order.get("final_amount")) / 10000) : 0;
        if ("cancelled".equals(status)) {
            for (Map<String, Object> item : db.queryForList(
                    "SELECT variant_id,quantity FROM order_items WHERE order_id=?", id)) {
                db.update("""
                    UPDATE inventory_stocks SET stock_quantity=stock_quantity+?,updated_at=SYSUTCDATETIME()
                    WHERE variant_id=? AND warehouse_id=(
                      SELECT TOP (1) warehouse_id FROM warehouses WHERE warehouse_name=N'Kho chính'
                    )
                    """, item.get("quantity"), item.get("variant_id"));
            }
        }
        db.update("""
            UPDATE orders SET order_status=?,updated_at=SYSUTCDATETIME(),
                              points_awarded=CASE WHEN ?=0 THEN points_awarded ELSE ? END
            WHERE order_id=?
            """, status.toUpperCase(), points, points, id);
        if (points > 0) {
            int updated = db.update("UPDATE loyalty_accounts SET points=points+? WHERE user_id=?",
                points, order.get("user_id"));
            if (updated == 0) {
                db.update("""
                    INSERT INTO loyalty_accounts(user_id,points,created_at)
                    VALUES(?,?,SYSUTCDATETIME())
                    """, order.get("user_id"), points);
            }
        }
        security.recordActivity(StoreSupport.text(session.get("username")), "Cập nhật trạng thái đơn hàng", "order", id, request);
        return Map.of("ok", true, "status", status);
    }

    private long customerUserId(String name, String phone) {
        List<Map<String, Object>> users = db.queryForList(
            "SELECT user_id FROM users WHERE phone_number=?", phone);
        if (!users.isEmpty()) {
            long userId = StoreSupport.number(users.get(0).get("user_id"));
            db.update("UPDATE users SET full_name=?,updated_at=SYSUTCDATETIME() WHERE user_id=?", name, userId);
            return userId;
        }
        String randomPassword = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(32));
        Long userId = db.queryForObject("""
            INSERT INTO users(phone_number,password_hash,full_name,status,created_at,updated_at)
            OUTPUT INSERTED.user_id VALUES(?,?,?,'ACTIVE',SYSUTCDATETIME(),SYSUTCDATETIME())
            """, Long.class, phone, credentials.hash(randomPassword), name);
        return userId;
    }

    private static byte[] randomBytes(int count) {
        byte[] bytes = new byte[count];
        new java.security.SecureRandom().nextBytes(bytes);
        return bytes;
    }
}
