package vn.swoosh.store.members.truong.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import vn.swoosh.store.SecurityService;
import vn.swoosh.store.StoreSupport;
import vn.swoosh.store.VoucherService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class VoucherController {
    private final JdbcTemplate db;
    private final SecurityService security;
    private final VoucherService vouchers;

    public VoucherController(JdbcTemplate db, SecurityService security, VoucherService vouchers) {
        this.db = db;
        this.security = security;
        this.vouchers = vouchers;
    }

    @GetMapping("/vouchers")
    Map<String, Object> activeVouchers() {
        return Map.of("vouchers", db.queryForList("""
            SELECT code,LOWER(discount_type) AS discount_type,discount_value,
                   min_order_value AS min_total,start_date AS starts_at,end_date AS expires_at
            FROM coupons WHERE is_active=1 ORDER BY code
            """));
    }

    @GetMapping("/admin/vouchers")
    Map<String, Object> list(HttpServletRequest request) {
        security.requireRole(request, "admin");
        return Map.of("vouchers", db.queryForList("""
            SELECT coupon_id AS id,code,LOWER(discount_type) AS discount_type,discount_value,
                   min_order_value AS min_total,start_date AS starts_at,end_date AS expires_at,
                   usage_limit,used_count,is_active AS active
            FROM coupons ORDER BY code
            """));
    }

    @PostMapping("/vouchers/check")
    Map<String, Object> check(@RequestBody Map<String, Object> data, HttpServletRequest request) {
        Map<String, Object> session = security.currentSession(request);
        if (session == null) StoreSupport.fail(403, "Phiên kiểm tra voucher đã hết hạn. Hãy tải lại trang.");
        security.requireCsrf(session, request);
        long subtotal = StoreSupport.longNumber(data.get("subtotal"), "Giá trị đơn hàng không hợp lệ.");
        if (subtotal < 0) StoreSupport.fail(400, "Giá trị đơn hàng không hợp lệ.");
        Map<String, Object> voucher = vouchers.find(StoreSupport.text(data.get("code")), subtotal);
        long discount = vouchers.discount(voucher, subtotal);
        return Map.of("code", voucher.get("code"), "discount", discount, "total", subtotal - discount);
    }

    @PostMapping("/admin/vouchers")
    ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> data, HttpServletRequest request) {
        Map<String, Object> admin = security.requireRole(request, "admin");
        security.requireCsrf(admin, request);
        Map<String, Object> voucher = vouchers.validate(data, null);
        try {
            db.update("""
                INSERT INTO coupons(
                  code,discount_type,discount_value,min_order_value,start_date,end_date,usage_limit,used_count,is_active
                )
                VALUES(?,?,?,?,?,?,?,0,?)
                """, voucher.get("code"), StoreSupport.text(voucher.get("discount_type")).toUpperCase(),
                voucher.get("discount_value"),
                voucher.get("min_total"), voucher.get("starts_at"), voucher.get("expires_at"),
                voucher.get("usage_limit"), voucher.get("active"));
        } catch (DataIntegrityViolationException error) {
            StoreSupport.fail(409, "Mã voucher đã tồn tại.");
        }
        security.recordActivity(StoreSupport.text(admin.get("username")), "Tạo voucher", "voucher", voucher.get("code"), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("voucher", voucher));
    }

    @PatchMapping("/admin/vouchers/{code}")
    Map<String, Object> update(@PathVariable String code, @RequestBody Map<String, Object> data,
                               HttpServletRequest request) {
        Map<String, Object> admin = security.requireRole(request, "admin");
        security.requireCsrf(admin, request);
        Map<String, Object> voucher = vouchers.validate(data, code);
        int updated = db.update("""
            UPDATE coupons SET discount_type=?,discount_value=?,min_order_value=?,start_date=?,end_date=?,usage_limit=?,is_active=?
            WHERE UPPER(code)=UPPER(?)
            """, StoreSupport.text(voucher.get("discount_type")).toUpperCase(),
            voucher.get("discount_value"), voucher.get("min_total"),
            voucher.get("starts_at"), voucher.get("expires_at"), voucher.get("usage_limit"),
            voucher.get("active"), code);
        if (updated == 0) StoreSupport.fail(404, "Không tìm thấy voucher.");
        security.recordActivity(StoreSupport.text(admin.get("username")), "Cập nhật voucher", "voucher", code, request);
        return Map.of("ok", true);
    }

    @DeleteMapping("/admin/vouchers/{code}")
    Map<String, Object> delete(@PathVariable String code, HttpServletRequest request) {
        Map<String, Object> admin = security.requireRole(request, "admin");
        security.requireCsrf(admin, request);
        if (db.update("DELETE FROM coupons WHERE UPPER(code)=UPPER(?)", code) == 0) {
            StoreSupport.fail(404, "Không tìm thấy voucher.");
        }
        security.recordActivity(StoreSupport.text(admin.get("username")), "Xóa voucher", "voucher", code, request);
        return Map.of("ok", true);
    }
}
