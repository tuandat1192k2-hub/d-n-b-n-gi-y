package vn.swoosh.store.members.tuandat.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import vn.swoosh.store.CredentialService;
import vn.swoosh.store.SecurityService;
import vn.swoosh.store.StoreSupport;
import org.springframework.dao.DataIntegrityViolationException;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/users")
public class StaffController {
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9._-]{3,40}");
    private final JdbcTemplate db;
    private final SecurityService security;
    private final CredentialService credentials;

    public StaffController(JdbcTemplate db, SecurityService security, CredentialService credentials) {
        this.db = db;
        this.security = security;
        this.credentials = credentials;
    }

    @GetMapping
    Map<String, Object> list(HttpServletRequest request) {
        security.requireRole(request, "admin");
        return Map.of("users", db.queryForList("""
            SELECT users.user_id AS id,users.username,N'staff' AS role,
                   CASE WHEN users.status='ACTIVE' THEN 1 ELSE 0 END AS active,
                   users.created_at,users.full_name,profiles.address,profiles.citizen_id,
                   profiles.birth_date,roles.role_id,roles.role_name
            FROM users
            JOIN staff_profiles profiles ON profiles.user_id=users.user_id
            OUTER APPLY (
              SELECT TOP (1) role.role_id,role.role_name
              FROM user_roles user_role JOIN roles role ON role.role_id=user_role.role_id
              WHERE user_role.user_id=users.user_id AND role.role_code<>'ADMIN'
              ORDER BY role.role_id
            ) roles
            ORDER BY users.username
            """));
    }

    @PostMapping
    @Transactional
    ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> data, HttpServletRequest request) {
        Map<String, Object> admin = security.requireRole(request, "admin");
        security.requireCsrf(admin, request);
        String username = StoreSupport.text(data.get("username")).trim();
        Object rawPassword = data.get("password");
        if (!(rawPassword instanceof String) || !USERNAME.matcher(username).matches()) {
            StoreSupport.fail(400, "Tên đăng nhập hoặc mật khẩu không hợp lệ.");
        }
        String password = (String) rawPassword;
        if (password.length() < 12 || password.length() > 256) {
            StoreSupport.fail(400, "Mật khẩu phải có từ 12 đến 256 ký tự.");
        }
        Map<String, String> profile = StoreSupport.validateStaffProfile(data, true);
        int roleId = data.containsKey("roleId")
            ? StoreSupport.integer(data.get("roleId"), "Vai trò nhân viên không hợp lệ.")
            : db.queryForObject("SELECT role_id FROM roles WHERE role_code='SALES_STAFF'", Integer.class);
        validateStaffRole(roleId);
        try {
            Long userId = db.queryForObject("""
                INSERT INTO users(username,password_hash,full_name,status,created_at,updated_at)
                OUTPUT INSERTED.user_id VALUES(?,?,?,'ACTIVE',SYSUTCDATETIME(),SYSUTCDATETIME())
                """, Long.class, username, credentials.hash(password), profile.get("full_name"));
            db.update("""
                INSERT INTO staff_profiles(user_id,address,citizen_id,birth_date)
                VALUES(?,?,?,?)
                """, userId, profile.get("address"), profile.get("citizen_id"),
                java.sql.Date.valueOf(LocalDate.parse(profile.get("birth_date"))));
            db.update("INSERT INTO user_roles(user_id,role_id,assigned_at) VALUES(?,?,SYSUTCDATETIME())",
                userId, roleId);
        } catch (DataIntegrityViolationException error) {
            if (!db.queryForList("SELECT 1 FROM users WHERE username=?", username).isEmpty()) {
                StoreSupport.fail(409, "Tên đăng nhập đã tồn tại.");
            }
            if (!db.queryForList("SELECT 1 FROM staff_profiles WHERE citizen_id=?", profile.get("citizen_id")).isEmpty()) {
                StoreSupport.fail(409, "Số CCCD đã được sử dụng cho nhân viên khác.");
            }
            throw error;
        }
        security.recordActivity(StoreSupport.text(admin.get("username")), "Tạo tài khoản nhân viên", "user", username, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("ok", true));
    }

    @PatchMapping("/{id}")
    @Transactional
    Map<String, Object> update(@PathVariable long id, @RequestBody Map<String, Object> data,
                               HttpServletRequest request) {
        Map<String, Object> admin = security.requireRole(request, "admin");
        security.requireCsrf(admin, request);
        if (db.queryForList("SELECT 1 FROM staff_profiles WHERE user_id=?", id).isEmpty()) {
            StoreSupport.fail(404, "Không tìm thấy nhân viên.");
        }
        Set<String> profileFields = Set.of("full_name", "address", "citizen_id", "birth_date");
        if (data.keySet().stream().noneMatch(key ->
                Set.of("username", "password", "active", "roleId").contains(key) || profileFields.contains(key))) {
            StoreSupport.fail(400, "Không có thông tin nào để cập nhật.");
        }
        List<String> assignments = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        if (data.containsKey("username")) {
            String username = StoreSupport.text(data.get("username")).trim();
            if (!USERNAME.matcher(username).matches()) StoreSupport.fail(400, "Tên đăng nhập phải có 3–40 ký tự hợp lệ.");
            assignments.add("username=?");
            values.add(username);
        }
        String password = StoreSupport.text(data.get("password"));
        if (!password.isEmpty()) {
            if (password.length() < 12 || password.length() > 256) StoreSupport.fail(400, "Mật khẩu mới phải có từ 12 đến 256 ký tự.");
            assignments.add("password_hash=?");
            values.add(credentials.hash(password));
        }
        if (data.containsKey("active")) {
            if (!(data.get("active") instanceof Boolean)) StoreSupport.fail(400, "Trạng thái tài khoản không hợp lệ.");
            assignments.add("status=?");
            values.add(Boolean.TRUE.equals(data.get("active")) ? "ACTIVE" : "INACTIVE");
        }
        Integer roleId = null;
        if (data.containsKey("roleId")) {
            roleId = StoreSupport.integer(data.get("roleId"), "Vai trò nhân viên không hợp lệ.");
            validateStaffRole(roleId);
        }
        Map<String, Object> profileData = new java.util.HashMap<>();
        for (String field : profileFields) {
            if (data.containsKey(field)) profileData.put(field, data.get(field));
        }
        Map<String, String> profile = StoreSupport.validateStaffProfile(profileData, false);
        List<String> profileAssignments = new ArrayList<>();
        List<Object> profileValues = new ArrayList<>();
        for (Map.Entry<String, String> entry : profile.entrySet()) {
            profileAssignments.add(entry.getKey() + "=?");
            profileValues.add("birth_date".equals(entry.getKey())
                ? java.sql.Date.valueOf(LocalDate.parse(entry.getValue())) : entry.getValue());
        }
        if (assignments.isEmpty() && profileAssignments.isEmpty() && roleId == null) {
            StoreSupport.fail(400, "Hãy nhập thông tin để cập nhật.");
        }
        try {
            if (!assignments.isEmpty()) {
                assignments.add("updated_at=?");
                values.add(java.sql.Timestamp.from(Instant.now()));
                values.add(id);
                db.update("UPDATE users SET " + String.join(",", assignments) + " WHERE user_id=?",
                    values.toArray());
            }
            if (!profileAssignments.isEmpty()) {
                profileValues.add(id);
                db.update("UPDATE staff_profiles SET " + String.join(",", profileAssignments) + " WHERE user_id=?",
                    profileValues.toArray());
                if (profile.containsKey("full_name")) {
                    db.update("UPDATE users SET full_name=?,updated_at=SYSUTCDATETIME() WHERE user_id=?",
                        profile.get("full_name"), id);
                }
            }
            if (roleId != null) {
                db.update("DELETE FROM user_roles WHERE user_id=?", id);
                db.update("INSERT INTO user_roles(user_id,role_id,assigned_at) VALUES(?,?,SYSUTCDATETIME())",
                    id, roleId);
            }
        } catch (DataIntegrityViolationException error) {
            if (data.containsKey("username") && !db.queryForList(
                    "SELECT 1 FROM users WHERE username=? AND user_id<>?",
                    StoreSupport.text(data.get("username")).trim(), id).isEmpty()) {
                StoreSupport.fail(409, "Tên đăng nhập đã được sử dụng.");
            }
            if (profile.containsKey("citizen_id") && !db.queryForList(
                    "SELECT 1 FROM staff_profiles WHERE citizen_id=? AND user_id<>?",
                    profile.get("citizen_id"), id).isEmpty()) {
                StoreSupport.fail(409, "Số CCCD đã được sử dụng cho nhân viên khác.");
            }
            throw error;
        }
        if (Boolean.FALSE.equals(data.get("active")) || !password.isEmpty()) {
            db.update("DELETE FROM app_sessions WHERE user_id=?", id);
        }
        security.recordActivity(StoreSupport.text(admin.get("username")), "Cập nhật nhân viên", "user", id, request);
        return Map.of("ok", true);
    }

    private void validateStaffRole(int roleId) {
        List<Map<String, Object>> roles = db.queryForList(
            "SELECT role_code FROM roles WHERE role_id=?", roleId);
        if (roles.isEmpty() || "ADMIN".equals(roles.get(0).get("role_code"))) {
            StoreSupport.fail(400, "Vai trò nhân viên không tồn tại.");
        }
    }
}
