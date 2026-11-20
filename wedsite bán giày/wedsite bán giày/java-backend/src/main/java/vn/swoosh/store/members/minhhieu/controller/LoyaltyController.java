package vn.swoosh.store.members.minhhieu.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import vn.swoosh.store.SecurityService;
import vn.swoosh.store.StoreSupport;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class LoyaltyController {
    private final JdbcTemplate db;
    private final SecurityService security;

    public LoyaltyController(JdbcTemplate db, SecurityService security) {
        this.db = db;
        this.security = security;
    }

    @GetMapping("/admin/loyalty")
    Map<String, Object> adminList(HttpServletRequest request) {
        security.requireRole(request, "admin");
        return Map.of("tiers", db.queryForList(
                "SELECT tier_id AS id,tier_name AS name,min_points,benefits FROM loyalty_tiers ORDER BY min_points"),
            "accounts", db.queryForList("""
                SELECT users.phone_number AS phone,users.full_name,accounts.points,accounts.created_at
                FROM loyalty_accounts accounts JOIN users ON users.user_id=accounts.user_id
                ORDER BY accounts.points DESC OFFSET 0 ROWS FETCH NEXT 500 ROWS ONLY
                """));
    }

    @GetMapping("/loyalty")
    Map<String, Object> lookup(@RequestParam String phone, @RequestParam(name = "order") String orderNumber) {
        if (!phone.matches("[0-9]{9,12}") || !orderNumber.matches("SW-[A-Fa-f0-9]{8}")) {
            StoreSupport.fail(400, "Nhập số điện thoại và mã đơn hàng hợp lệ.");
        }
        if (db.queryForList(
                "SELECT 1 FROM orders WHERE order_code=? AND recipient_phone=? AND UPPER(order_status)='COMPLETED'",
                orderNumber, phone).isEmpty()) {
            StoreSupport.fail(404, "Không tìm thấy đơn hàng hoàn tất khớp với thông tin này.");
        }
        List<Map<String, Object>> accounts = db.queryForList("""
            SELECT users.phone_number AS phone,users.full_name,accounts.points
            FROM loyalty_accounts accounts JOIN users ON users.user_id=accounts.user_id
            WHERE users.phone_number=?
            """, phone);
        List<Map<String, Object>> tiers = db.queryForList(
            "SELECT tier_name AS name,min_points,benefits FROM loyalty_tiers ORDER BY min_points");
        long points = accounts.isEmpty() ? 0 : StoreSupport.number(accounts.get(0).get("points"));
        Map<String, Object> tier = tiers.stream()
            .filter(row -> StoreSupport.number(row.get("min_points")) <= points)
            .max(java.util.Comparator.comparingLong(row -> StoreSupport.number(row.get("min_points"))))
            .orElse(null);
        Map<String, Object> account = accounts.isEmpty()
            ? Map.of("phone", phone, "full_name", "", "points", 0) : accounts.get(0);
        Map<String, Object> response = new java.util.HashMap<>();
        response.put("account", account);
        response.put("tier", tier);
        response.put("tiers", tiers);
        return response;
    }

    @PostMapping("/admin/loyalty/tiers")
    ResponseEntity<Map<String, Object>> createTier(@RequestBody Map<String, Object> data,
                                                   HttpServletRequest request) {
        Map<String, Object> admin = security.requireRole(request, "admin");
        security.requireCsrf(admin, request);
        String name = StoreSupport.text(data.get("name")).trim();
        int points = StoreSupport.integer(data.get("minPoints"), "Thông tin hạng thành viên không hợp lệ.");
        String benefits = StoreSupport.text(data.get("benefits")).trim();
        validateTier(name, points, benefits);
        try {
            db.update("INSERT INTO loyalty_tiers(tier_name,min_points,benefits) VALUES(?,?,?)", name, points, benefits);
        } catch (DataIntegrityViolationException error) {
            StoreSupport.fail(409, "Tên hạng hoặc mốc điểm đã tồn tại.");
        }
        security.recordActivity(StoreSupport.text(admin.get("username")), "Tạo hạng thành viên",
            "loyalty_tier", name, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("ok", true));
    }

    @PatchMapping("/admin/loyalty/tiers/{id}")
    Map<String, Object> updateTier(@PathVariable long id, @RequestBody Map<String, Object> data,
                                   HttpServletRequest request) {
        Map<String, Object> admin = security.requireRole(request, "admin");
        security.requireCsrf(admin, request);
        String name = StoreSupport.text(data.get("name")).trim();
        int points = StoreSupport.integer(data.get("minPoints"), "Thông tin hạng thành viên không hợp lệ.");
        String benefits = StoreSupport.text(data.get("benefits")).trim();
        validateTier(name, points, benefits);
        List<Map<String, Object>> old = db.queryForList("SELECT min_points FROM loyalty_tiers WHERE tier_id=?", id);
        if (old.isEmpty()) StoreSupport.fail(404, "Không tìm thấy hạng thành viên.");
        if (StoreSupport.number(old.get(0).get("min_points")) == 0 && points != 0) {
            StoreSupport.fail(409, "Hạng cơ bản phải giữ mốc 0 điểm.");
        }
        try {
            db.update("UPDATE loyalty_tiers SET tier_name=?,min_points=?,benefits=? WHERE tier_id=?",
                name, points, benefits, id);
        } catch (DataIntegrityViolationException error) {
            StoreSupport.fail(409, "Tên hạng hoặc mốc điểm đã tồn tại.");
        }
        security.recordActivity(StoreSupport.text(admin.get("username")), "Cập nhật hạng thành viên",
            "loyalty_tier", id, request);
        return Map.of("ok", true);
    }

    @DeleteMapping("/admin/loyalty/tiers/{id}")
    Map<String, Object> deleteTier(@PathVariable long id, HttpServletRequest request) {
        Map<String, Object> admin = security.requireRole(request, "admin");
        security.requireCsrf(admin, request);
        if (StoreSupport.number(db.queryForObject("SELECT COUNT(*) FROM loyalty_tiers", Long.class)) <= 1) {
            StoreSupport.fail(409, "Cần duy trì ít nhất một hạng thành viên.");
        }
        List<Map<String, Object>> rows = db.queryForList("SELECT min_points FROM loyalty_tiers WHERE tier_id=?", id);
        if (rows.isEmpty()) StoreSupport.fail(404, "Không tìm thấy hạng thành viên.");
        if (StoreSupport.number(rows.get(0).get("min_points")) == 0) {
            StoreSupport.fail(409, "Không thể xóa hạng cơ bản 0 điểm.");
        }
        db.update("DELETE FROM loyalty_tiers WHERE tier_id=?", id);
        security.recordActivity(StoreSupport.text(admin.get("username")), "Xóa hạng thành viên",
            "loyalty_tier", id, request);
        return Map.of("ok", true);
    }

    private static void validateTier(String name, int points, String benefits) {
        if (name.length() < 2 || name.length() > 40 || points < 0 || benefits.length() > 200) {
            StoreSupport.fail(400, "Thông tin hạng thành viên không hợp lệ.");
        }
    }
}
