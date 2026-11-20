package vn.swoosh.store.members.tuandat.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import vn.swoosh.store.CredentialService;
import vn.swoosh.store.SecurityService;
import vn.swoosh.store.StoreSupport;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class CustomerFeaturesController {
    private static final Pattern PHONE = Pattern.compile("[0-9]{9,12}");
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private final JdbcTemplate db;
    private final SecurityService security;
    private final CredentialService credentials;

    public CustomerFeaturesController(JdbcTemplate db, SecurityService security, CredentialService credentials) {
        this.db = db;
        this.security = security;
        this.credentials = credentials;
    }

    @GetMapping("/customer/profile")
    Map<String, Object> profile(HttpServletRequest request) {
        Map<String, Object> customer = customer(request);
        return db.queryForMap("""
            SELECT user_id,full_name,email,phone_number,avatar_url,created_at
            FROM users WHERE user_id=?
            """, customer.get("user_id"));
    }

    @PatchMapping("/customer/profile")
    Map<String, Object> updateProfile(@RequestBody Map<String, Object> data, HttpServletRequest request) {
        Map<String, Object> customer = customer(request);
        security.requireCsrf(customer, request);
        String name = StoreSupport.text(data.get("fullName")).trim();
        String email = StoreSupport.text(data.get("email")).trim();
        String phone = StoreSupport.text(data.get("phone")).trim();
        if (name.length() < 2 || name.length() > 100) StoreSupport.fail(400, "Vui lòng nhập họ tên hợp lệ.");
        if (!email.isEmpty() && (email.length() > 255 || !EMAIL.matcher(email).matches())) {
            StoreSupport.fail(400, "Địa chỉ email không hợp lệ.");
        }
        if (!PHONE.matcher(phone).matches()) StoreSupport.fail(400, "Số điện thoại phải có từ 9 đến 12 chữ số.");
        try {
            db.update("""
                UPDATE users SET full_name=?,email=?,phone_number=?,updated_at=SYSUTCDATETIME()
                WHERE user_id=?
                """, name, email.isEmpty() ? null : email, phone, customer.get("user_id"));
        } catch (DataIntegrityViolationException error) {
            StoreSupport.fail(409, "Email hoặc số điện thoại đã được sử dụng.");
        }
        security.recordActivity(name, "Cập nhật thông tin cá nhân", "customer", customer.get("user_id"), request);
        return profile(request);
    }

    @PostMapping("/customer/password/change")
    Map<String, Object> changePassword(@RequestBody Map<String, Object> data, HttpServletRequest request) {
        Map<String, Object> customer = customer(request);
        security.requireCsrf(customer, request);
        String current = StoreSupport.text(data.get("currentPassword"));
        String next = StoreSupport.text(data.get("newPassword"));
        if (next.length() < 8 || next.length() > 256) StoreSupport.fail(400, "Mật khẩu mới phải có từ 8 đến 256 ký tự.");
        String stored = db.queryForObject("SELECT password_hash FROM users WHERE user_id=?",
            String.class, customer.get("user_id"));
        if (!credentials.matches(current, stored)) StoreSupport.fail(401, "Mật khẩu hiện tại không đúng.");
        db.update("UPDATE users SET password_hash=?,updated_at=SYSUTCDATETIME() WHERE user_id=?",
            credentials.hash(next), customer.get("user_id"));
        db.update("DELETE FROM app_sessions WHERE user_id=? AND token_hash<>?",
            customer.get("user_id"), customer.get("token_hash"));
        security.recordActivity(StoreSupport.text(customer.get("username")), "Thay đổi mật khẩu", "auth", "", request);
        return Map.of("ok", true);
    }

    @PostMapping("/password/reset")
    Map<String, Object> resetPassword(@RequestBody Map<String, Object> data, HttpServletRequest request) {
        Map<String, Object> guest = security.currentSession(request);
        if (guest == null) StoreSupport.fail(403, "Phiên gửi yêu cầu đã hết hạn. Hãy tải lại trang.");
        security.requireCsrf(guest, request);
        String identifier = StoreSupport.text(data.get("identifier")).trim();
        String code = StoreSupport.text(data.get("code")).trim();
        String password = StoreSupport.text(data.get("newPassword"));
        if (identifier.isEmpty() || identifier.length() > 255 || !code.matches("[0-9]{6}")) {
            StoreSupport.fail(400, "Thông tin đặt lại mật khẩu không hợp lệ.");
        }
        if (password.length() < 8 || password.length() > 256) {
            StoreSupport.fail(400, "Mật khẩu mới phải có từ 8 đến 256 ký tự.");
        }
        List<Map<String, Object>> accounts = db.queryForList("""
            SELECT user_id,full_name FROM users WHERE email=? OR phone_number=?
            """, identifier, identifier);
        if (accounts.isEmpty()) StoreSupport.fail(400, "Mã xác thực không hợp lệ hoặc đã hết hạn.");
        Object userId = accounts.get(0).get("user_id");
        List<Map<String, Object>> codes = db.queryForList("""
            SELECT TOP (1) verification_id FROM user_verifications
            WHERE user_id=? AND type='RECOVERY' AND code=? AND is_verified=0
              AND expires_at>SYSUTCDATETIME()
            ORDER BY verification_id DESC
            """, userId, code);
        if (codes.isEmpty()) StoreSupport.fail(400, "Mã xác thực không hợp lệ hoặc đã hết hạn.");
        db.update("UPDATE users SET password_hash=?,updated_at=SYSUTCDATETIME() WHERE user_id=?",
            credentials.hash(password), userId);
        db.update("UPDATE user_verifications SET is_verified=1 WHERE verification_id=?", codes.get(0).get("verification_id"));
        db.update("DELETE FROM app_sessions WHERE user_id=?", userId);
        security.recordActivity(StoreSupport.text(accounts.get(0).get("full_name")),
            "Đặt lại mật khẩu", "auth", "", request);
        return Map.of("ok", true);
    }

    @GetMapping("/customer/addresses")
    Map<String, Object> addresses(HttpServletRequest request) {
        Map<String, Object> customer = customer(request);
        return Map.of("addresses", db.queryForList("""
            SELECT address_id AS id,recipient_name,phone,province,district,ward,detailed_address,is_default
            FROM shipping_addresses WHERE user_id=? ORDER BY is_default DESC,address_id DESC
            """, customer.get("user_id")));
    }

    @PostMapping("/customer/addresses")
    @Transactional
    ResponseEntity<Map<String, Object>> createAddress(@RequestBody Map<String, Object> data,
                                                       HttpServletRequest request) {
        Map<String, Object> customer = customer(request);
        security.requireCsrf(customer, request);
        Map<String, Object> address = validateAddress(data);
        boolean makeDefault = Boolean.TRUE.equals(data.get("isDefault"));
        if (makeDefault) db.update("UPDATE shipping_addresses SET is_default=0 WHERE user_id=?", customer.get("user_id"));
        Long id = db.queryForObject("""
            INSERT INTO shipping_addresses(
              user_id,recipient_name,phone,province,district,ward,detailed_address,is_default
            ) OUTPUT INSERTED.address_id VALUES(?,?,?,?,?,?,?,?)
            """, Long.class, customer.get("user_id"), address.get("recipientName"), address.get("phone"),
            address.get("province"), address.get("district"), address.get("ward"),
            address.get("detail"), makeDefault);
        security.recordActivity(StoreSupport.text(customer.get("username")), "Thêm địa chỉ giao hàng",
            "shipping_address", id, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id, "ok", true));
    }

    @PatchMapping("/customer/addresses/{id}")
    Map<String, Object> updateAddress(@PathVariable long id, @RequestBody Map<String, Object> data,
                                      HttpServletRequest request) {
        Map<String, Object> customer = customer(request);
        security.requireCsrf(customer, request);
        Map<String, Object> address = validateAddress(data);
        int updated = db.update("""
            UPDATE shipping_addresses
            SET recipient_name=?,phone=?,province=?,district=?,ward=?,detailed_address=?
            WHERE address_id=? AND user_id=?
            """, address.get("recipientName"), address.get("phone"), address.get("province"),
            address.get("district"), address.get("ward"), address.get("detail"), id, customer.get("user_id"));
        if (updated == 0) StoreSupport.fail(404, "Không tìm thấy địa chỉ giao hàng.");
        return Map.of("ok", true);
    }

    @DeleteMapping("/customer/addresses/{id}")
    Map<String, Object> deleteAddress(@PathVariable long id, HttpServletRequest request) {
        Map<String, Object> customer = customer(request);
        security.requireCsrf(customer, request);
        if (db.update("DELETE FROM shipping_addresses WHERE address_id=? AND user_id=?", id, customer.get("user_id")) == 0) {
            StoreSupport.fail(404, "Không tìm thấy địa chỉ giao hàng.");
        }
        return Map.of("ok", true);
    }

    @PostMapping("/customer/addresses/{id}/default")
    @Transactional
    Map<String, Object> defaultAddress(@PathVariable long id, HttpServletRequest request) {
        Map<String, Object> customer = customer(request);
        security.requireCsrf(customer, request);
        if (db.queryForList("SELECT 1 FROM shipping_addresses WHERE address_id=? AND user_id=?",
                id, customer.get("user_id")).isEmpty()) {
            StoreSupport.fail(404, "Không tìm thấy địa chỉ giao hàng.");
        }
        db.update("UPDATE shipping_addresses SET is_default=0 WHERE user_id=?", customer.get("user_id"));
        db.update("UPDATE shipping_addresses SET is_default=1 WHERE address_id=? AND user_id=?",
            id, customer.get("user_id"));
        return Map.of("ok", true);
    }

    @GetMapping("/customer/wishlist")
    Map<String, Object> wishlist(HttpServletRequest request) {
        Map<String, Object> customer = customer(request);
        return Map.of("products", db.queryForList("""
            SELECT shoes.shoe_code AS id,shoes.shoe_name AS name,
                   COALESCE(shoes.sale_price,shoes.base_price) AS price,
                   wishlist.created_at
            FROM user_wishlists wishlist
            JOIN running_shoes shoes ON shoes.shoe_id=wishlist.shoe_id
            WHERE wishlist.user_id=? ORDER BY wishlist.created_at DESC
            """, customer.get("user_id")));
    }

    @PostMapping("/customer/wishlist")
    ResponseEntity<Map<String, Object>> addWishlist(@RequestBody Map<String, Object> data,
                                                     HttpServletRequest request) {
        Map<String, Object> customer = customer(request);
        security.requireCsrf(customer, request);
        String code = StoreSupport.text(data.get("shoeCode")).trim();
        List<Map<String, Object>> shoes = db.queryForList(
            "SELECT shoe_id FROM running_shoes WHERE shoe_code=? AND status='ACTIVE'", code);
        if (shoes.isEmpty()) StoreSupport.fail(404, "Không tìm thấy sản phẩm đang bán.");
        db.update("""
            IF NOT EXISTS (SELECT 1 FROM user_wishlists WHERE user_id=? AND shoe_id=?)
              INSERT INTO user_wishlists(user_id,shoe_id) VALUES(?,?)
            """, customer.get("user_id"), shoes.get(0).get("shoe_id"),
            customer.get("user_id"), shoes.get(0).get("shoe_id"));
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("ok", true));
    }

    @DeleteMapping("/customer/wishlist/{shoeCode}")
    Map<String, Object> removeWishlist(@PathVariable String shoeCode, HttpServletRequest request) {
        Map<String, Object> customer = customer(request);
        security.requireCsrf(customer, request);
        db.update("""
            DELETE wishlist FROM user_wishlists wishlist
            JOIN running_shoes shoes ON shoes.shoe_id=wishlist.shoe_id
            WHERE wishlist.user_id=? AND shoes.shoe_code=?
            """, customer.get("user_id"), shoeCode);
        return Map.of("ok", true);
    }

    @GetMapping("/customer/orders")
    Map<String, Object> orders(HttpServletRequest request) {
        Map<String, Object> customer = customer(request);
        List<Map<String, Object>> rows = db.queryForList("""
            SELECT order_id AS id,order_code AS order_number,recipient_name AS customer_name,
                   recipient_phone AS phone,shipping_address AS address,final_amount AS total,
                   LOWER(order_status) AS status,created_at
            FROM orders WHERE user_id=? ORDER BY order_id DESC
            """, customer.get("user_id"));
        return Map.of("orders", rows.stream().map(row -> StoreSupport.orderJson(db, row)).toList());
    }

    @PostMapping("/customer/orders/{id}/cancel")
    @Transactional
    Map<String, Object> cancelOrder(@PathVariable long id, HttpServletRequest request) {
        Map<String, Object> customer = customer(request);
        security.requireCsrf(customer, request);
        List<Map<String, Object>> orders = db.queryForList("""
            SELECT order_status FROM orders WITH (UPDLOCK,ROWLOCK)
            WHERE order_id=? AND user_id=?
            """, id, customer.get("user_id"));
        if (orders.isEmpty()) StoreSupport.fail(404, "Không tìm thấy đơn hàng.");
        if (!"PENDING".equalsIgnoreCase(StoreSupport.text(orders.get(0).get("order_status")))) {
            StoreSupport.fail(409, "Chỉ có thể hủy đơn hàng đang chờ xác nhận.");
        }
        restoreInventory(id);
        db.update("""
            UPDATE orders SET order_status='CANCELLED'
            WHERE order_id=? AND user_id=? AND order_status='PENDING'
            """, id, customer.get("user_id"));
        db.update("""
            INSERT INTO order_status_logs(order_id,old_status,new_status,note,changed_at,changed_by)
            VALUES(?,'PENDING','CANCELLED',N'Khách hàng hủy đơn',SYSUTCDATETIME(),?)
            """, id, customer.get("user_id"));
        security.recordActivity(StoreSupport.text(customer.get("username")),
            "Hủy đơn hàng", "order", id, request);
        return Map.of("ok", true, "status", "cancelled");
    }

    @PostMapping("/customer/orders/{id}/exchanges")
    ResponseEntity<Map<String, Object>> requestExchange(@PathVariable long id,
                                                         @RequestBody Map<String, Object> data,
                                                         HttpServletRequest request) {
        Map<String, Object> customer = customer(request);
        security.requireCsrf(customer, request);
        int oldVariant = StoreSupport.integer(data.get("oldVariantId"), "Biến thể đơn hàng không hợp lệ.");
        int newVariant = StoreSupport.integer(data.get("newVariantId"), "Biến thể muốn đổi không hợp lệ.");
        String reason = StoreSupport.text(data.get("reason")).trim();
        if (reason.isEmpty() || reason.length() > 255 || oldVariant == newVariant) {
            StoreSupport.fail(400, "Vui lòng nhập lý do đổi size hoặc màu hợp lệ.");
        }
        if (db.queryForList("""
                SELECT 1 FROM orders
                WHERE order_id=? AND user_id=? AND UPPER(order_status) IN ('DELIVERED','COMPLETED')
                """, id, customer.get("user_id")).isEmpty()) {
            StoreSupport.fail(409, "Chỉ có thể yêu cầu đổi hàng đã giao thành công.");
        }
        if (db.queryForList("""
                SELECT 1 FROM order_items WHERE order_id=? AND variant_id=?
                """, id, oldVariant).isEmpty()) {
            StoreSupport.fail(400, "Sản phẩm gốc không thuộc đơn hàng.");
        }
        if (db.queryForList("""
                SELECT 1 FROM shoe_variants old
                JOIN shoe_variants next ON next.shoe_id=old.shoe_id
                WHERE old.variant_id=? AND next.variant_id=? AND next.is_active=1
                """, oldVariant, newVariant).isEmpty()) {
            StoreSupport.fail(400, "Chỉ có thể đổi sang size hoặc màu còn bán của cùng mẫu giày.");
        }
        Long exchangeId = db.queryForObject("""
            INSERT INTO size_exchange_requests(order_id,old_variant_id,new_variant_id,reason,status,created_at)
            OUTPUT INSERTED.exchange_id VALUES(?,?,?,?,'PENDING',SYSUTCDATETIME())
            """, Long.class, id, oldVariant, newVariant, reason);
        security.recordActivity(StoreSupport.text(customer.get("username")),
            "Yêu cầu đổi size hoặc màu", "exchange", exchangeId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", exchangeId, "status", "PENDING"));
    }

    @PostMapping("/customer/orders/{id}/returns")
    ResponseEntity<Map<String, Object>> requestReturn(@PathVariable long id,
                                                       @RequestBody Map<String, Object> data,
                                                       HttpServletRequest request) {
        Map<String, Object> customer = customer(request);
        security.requireCsrf(customer, request);
        String reason = StoreSupport.text(data.get("reason")).trim();
        if (reason.length() < 5 || reason.length() > 2000) StoreSupport.fail(400, "Vui lòng nhập lý do hoàn trả.");
        List<Map<String, Object>> orders = db.queryForList("""
            SELECT final_amount FROM orders
            WHERE order_id=? AND user_id=? AND UPPER(order_status) IN ('DELIVERED','COMPLETED')
            """, id, customer.get("user_id"));
        if (orders.isEmpty()) StoreSupport.fail(409, "Chỉ có thể yêu cầu hoàn tiền cho đơn đã giao thành công.");
        if (!db.queryForList("SELECT 1 FROM order_returns WHERE order_id=? AND status<>'REJECTED'", id).isEmpty()) {
            StoreSupport.fail(409, "Đơn hàng đã có yêu cầu hoàn tiền.");
        }
        Long returnId = db.queryForObject("""
            INSERT INTO order_returns(order_id,user_id,reason,refund_amount,status,created_at)
            OUTPUT INSERTED.return_id VALUES(?,?,?,?,'PENDING',SYSUTCDATETIME())
            """, Long.class, id, customer.get("user_id"), reason, orders.get(0).get("final_amount"));
        security.recordActivity(StoreSupport.text(customer.get("username")),
            "Yêu cầu hoàn tiền", "return", returnId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", returnId, "status", "PENDING"));
    }

    @GetMapping("/reviews")
    Map<String, Object> reviews(@RequestParam(required = false) String shoeCode) {
        String code = shoeCode == null ? "" : shoeCode.trim();
        return Map.of("reviews", db.queryForList("""
            SELECT reviews.review_id AS id,shoes.shoe_code AS shoe_code,shoes.shoe_name,
                   users.full_name,reviews.star_rating,reviews.cushion_rating,
                   reviews.stability_rating,reviews.grip_rating,
                   reviews.fit_true_to_size_rating,reviews.running_feeling_comment,
                   reviews.created_at
            FROM shoe_reviews reviews
            JOIN running_shoes shoes ON shoes.shoe_id=reviews.shoe_id
            JOIN users ON users.user_id=reviews.user_id
            WHERE reviews.status='APPROVED' AND (?='' OR shoes.shoe_code=?)
            ORDER BY reviews.created_at DESC
            """, code, code));
    }

    @PostMapping("/customer/reviews")
    ResponseEntity<Map<String, Object>> createReview(@RequestBody Map<String, Object> data,
                                                      HttpServletRequest request) {
        Map<String, Object> customer = customer(request);
        security.requireCsrf(customer, request);
        String shoeCode = StoreSupport.text(data.get("shoeCode")).trim();
        int stars = StoreSupport.integer(data.get("stars"), "Đánh giá phải từ 1 đến 5 sao.");
        String comment = StoreSupport.text(data.get("comment")).trim();
        if (stars < 1 || stars > 5 || comment.length() > 2000) StoreSupport.fail(400, "Nội dung đánh giá không hợp lệ.");
        List<Map<String, Object>> purchases = db.queryForList("""
            SELECT TOP (1) shoes.shoe_id,orders.order_id
            FROM orders JOIN order_items items ON items.order_id=orders.order_id
            JOIN shoe_variants variants ON variants.variant_id=items.variant_id
            JOIN running_shoes shoes ON shoes.shoe_id=variants.shoe_id
            WHERE orders.user_id=? AND shoes.shoe_code=? AND UPPER(orders.order_status)='COMPLETED'
            ORDER BY orders.order_id DESC
            """, customer.get("user_id"), shoeCode);
        if (purchases.isEmpty()) StoreSupport.fail(403, "Chỉ có thể đánh giá sản phẩm đã mua và nhận thành công.");
        if (!db.queryForList("""
                SELECT 1 FROM shoe_reviews WHERE user_id=? AND order_id=? AND shoe_id=?
                """, customer.get("user_id"), purchases.get(0).get("order_id"), purchases.get(0).get("shoe_id")).isEmpty()) {
            StoreSupport.fail(409, "Bạn đã đánh giá sản phẩm này trong đơn hàng.");
        }
        Long reviewId = db.queryForObject("""
            INSERT INTO shoe_reviews(shoe_id,user_id,order_id,star_rating,running_feeling_comment,status,created_at)
            OUTPUT INSERTED.review_id VALUES(?,?,?,?,?,'PENDING',SYSUTCDATETIME())
            """, Long.class, purchases.get(0).get("shoe_id"), customer.get("user_id"),
            purchases.get(0).get("order_id"), stars, comment);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", reviewId, "status", "PENDING"));
    }

    @PostMapping("/customer/reviews/{id}/report")
    ResponseEntity<Map<String, Object>> reportReview(@PathVariable long id,
                                                      @RequestBody Map<String, Object> data,
                                                      HttpServletRequest request) {
        Map<String, Object> customer = customer(request);
        security.requireCsrf(customer, request);
        String reason = StoreSupport.text(data.get("reason")).trim();
        if (reason.length() < 5 || reason.length() > 1000) StoreSupport.fail(400, "Vui lòng nhập lý do báo cáo.");
        List<Map<String, Object>> reviews = db.queryForList(
            "SELECT shoe_id FROM shoe_reviews WHERE review_id=? AND status='APPROVED'", id);
        if (reviews.isEmpty()) StoreSupport.fail(404, "Không tìm thấy đánh giá đang hiển thị.");
        Long complaintId = db.queryForObject("""
            INSERT INTO customer_complaints(user_id,shoe_id,complaint_type,content,status,created_at)
            OUTPUT INSERTED.complaint_id VALUES(?,?,'REVIEW',?,'OPEN',SYSUTCDATETIME())
            """, Long.class, customer.get("user_id"), reviews.get(0).get("shoe_id"), "Đánh giá #" + id + ": " + reason);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", complaintId, "status", "OPEN"));
    }

    @GetMapping("/admin/refunds")
    Map<String, Object> refunds(HttpServletRequest request) {
        security.requireRole(request, "admin");
        return Map.of("requests", db.queryForList("""
            SELECT returns.return_id AS id,returns.order_id,orders.order_code,
                   orders.recipient_name,orders.recipient_phone,returns.reason,
                   returns.refund_amount,returns.status,returns.created_at
            FROM order_returns returns JOIN orders ON orders.order_id=returns.order_id
            ORDER BY returns.created_at DESC
            """));
    }

    @PatchMapping("/admin/refunds/{id}")
    @Transactional
    Map<String, Object> updateRefund(@PathVariable long id, @RequestBody Map<String, Object> data,
                                     HttpServletRequest request) {
        Map<String, Object> admin = security.requireRole(request, "admin");
        security.requireCsrf(admin, request);
        String status = StoreSupport.text(data.get("status")).toUpperCase();
        if (!List.of("APPROVED", "REJECTED", "REFUNDED").contains(status)) {
            StoreSupport.fail(400, "Trạng thái yêu cầu hoàn tiền không hợp lệ.");
        }
        List<Map<String, Object>> rows = db.queryForList("""
            SELECT refund_amount,status FROM order_returns WHERE return_id=?
            """, id);
        if (rows.isEmpty()) StoreSupport.fail(404, "Không tìm thấy yêu cầu hoàn tiền.");
        String oldStatus = StoreSupport.text(rows.get(0).get("status"));
        if (!"PENDING".equals(oldStatus) && !("APPROVED".equals(oldStatus) && "REFUNDED".equals(status))) {
            StoreSupport.fail(409, "Yêu cầu hoàn tiền đã được xử lý.");
        }
        db.update("UPDATE order_returns SET status=? WHERE return_id=?", status, id);
        if ("REFUNDED".equals(status)) {
            db.update("""
                INSERT INTO refund_transactions(return_id,amount,refunded_at)
                VALUES(?,?,SYSUTCDATETIME())
                """, id, rows.get(0).get("refund_amount"));
        }
        security.recordActivity(StoreSupport.text(admin.get("username")),
            "Cập nhật yêu cầu hoàn tiền", "return", id, request);
        return Map.of("ok", true, "status", status);
    }

    private Map<String, Object> customer(HttpServletRequest request) {
        return security.requireRole(request, "customer");
    }

    private Map<String, Object> validateAddress(Map<String, Object> data) {
        Map<String, Object> address = new HashMap<>();
        address.put("recipientName", StoreSupport.text(data.get("recipientName")).trim());
        address.put("phone", StoreSupport.text(data.get("phone")).trim());
        address.put("province", StoreSupport.text(data.get("province")).trim());
        address.put("district", StoreSupport.text(data.get("district")).trim());
        address.put("ward", StoreSupport.text(data.get("ward")).trim());
        address.put("detail", StoreSupport.text(data.get("detail")).trim());
        if (StoreSupport.text(address.get("recipientName")).length() < 2
                || StoreSupport.text(address.get("recipientName")).length() > 100
                || !PHONE.matcher(StoreSupport.text(address.get("phone"))).matches()
                || StoreSupport.text(address.get("province")).isEmpty()
                || StoreSupport.text(address.get("district")).isEmpty()
                || StoreSupport.text(address.get("ward")).isEmpty()
                || StoreSupport.text(address.get("detail")).length() < 5
                || StoreSupport.text(address.get("detail")).length() > 255) {
            StoreSupport.fail(400, "Thông tin địa chỉ giao hàng không hợp lệ.");
        }
        return address;
    }

    private void restoreInventory(long orderId) {
        for (Map<String, Object> item : db.queryForList(
                "SELECT variant_id,quantity FROM order_items WHERE order_id=?", orderId)) {
            db.update("""
                UPDATE inventory_stocks SET stock_quantity=stock_quantity+?,updated_at=SYSUTCDATETIME()
                WHERE variant_id=? AND warehouse_id=(
                  SELECT TOP (1) warehouse_id FROM warehouses WHERE warehouse_name=N'Kho chính'
                )
                """, item.get("quantity"), item.get("variant_id"));
        }
    }
}
