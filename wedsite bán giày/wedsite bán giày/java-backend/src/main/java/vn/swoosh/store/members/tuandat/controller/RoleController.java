package vn.swoosh.store.members.tuandat.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import vn.swoosh.store.ApiException;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/roles")
public class RoleController {
    private static final Set<String> ALLOWED_PERMISSIONS = Set.of("orders.read", "orders.update");
    private final JdbcTemplate db;
    private final SecurityService security;

    public RoleController(JdbcTemplate db, SecurityService security) {
        this.db = db;
        this.security = security;
    }

    @GetMapping
    Map<String, Object> list(HttpServletRequest request) {
        security.requireRole(request, "admin");
        List<Map<String, Object>> roles = db.queryForList(
            "SELECT role_id AS id,role_code AS code,role_name AS name FROM roles ORDER BY role_id").stream().map(row -> {
            Map<String, Object> result = new HashMap<>(row);
            result.put("permissions", db.queryForList("""
                SELECT permissions.permission_code
                FROM role_permissions assignments
                JOIN permissions ON permissions.permission_id=assignments.permission_id
                WHERE assignments.role_id=?
                ORDER BY permissions.permission_code
                """, String.class, row.get("id")));
            return result;
        }).toList();
        return Map.of("roles", roles);
    }

    @PostMapping
    @Transactional
    ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> data, HttpServletRequest request) {
        Map<String, Object> admin = security.requireRole(request, "admin");
        security.requireCsrf(admin, request);
        String name = StoreSupport.text(data.get("name")).trim();
        List<String> permissions = validatePermissions(data.get("permissions"));
        validateName(name);
        if (!db.queryForList("SELECT 1 FROM roles WHERE role_name=?", name).isEmpty()) {
            StoreSupport.fail(409, "Tên vai trò đã tồn tại.");
        }
        String code = "CUSTOM_" + java.util.UUID.randomUUID().toString().replace("-", "")
            .substring(0, 12).toUpperCase();
        Long id;
        try {
            id = db.queryForObject("""
                INSERT INTO roles(role_code,role_name,description)
                OUTPUT INSERTED.role_id VALUES(?,?,N'Vai trò tùy chỉnh')
                """, Long.class, code, name);
        } catch (DataIntegrityViolationException error) {
            throw new ApiException(409, "Không thể tạo vai trò với thông tin này.");
        }
        setPermissions(id, permissions);
        security.recordActivity(StoreSupport.text(admin.get("username")), "Tạo vai trò", "role", id, request);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(Map.of("id", id, "name", name, "permissions", permissions));
    }

    @PatchMapping("/{id}")
    @Transactional
    Map<String, Object> update(@PathVariable long id, @RequestBody Map<String, Object> data,
                               HttpServletRequest request) {
        Map<String, Object> admin = security.requireRole(request, "admin");
        security.requireCsrf(admin, request);
        String name = StoreSupport.text(data.get("name")).trim();
        List<String> permissions = validatePermissions(data.get("permissions"));
        validateName(name);
        if (db.queryForList("SELECT 1 FROM roles WHERE role_id=?", id).isEmpty()) {
            StoreSupport.fail(404, "Không tìm thấy vai trò.");
        }
        if (!db.queryForList("SELECT 1 FROM roles WHERE role_name=? AND role_id<>?", name, id).isEmpty()) {
            StoreSupport.fail(409, "Tên vai trò đã tồn tại.");
        }
        db.update("UPDATE roles SET role_name=? WHERE role_id=?", name, id);
        setPermissions(id, permissions);
        security.recordActivity(StoreSupport.text(admin.get("username")), "Cập nhật vai trò", "role", id, request);
        return Map.of("ok", true);
    }

    @DeleteMapping("/{id}")
    @Transactional
    Map<String, Object> delete(@PathVariable long id, HttpServletRequest request) {
        Map<String, Object> admin = security.requireRole(request, "admin");
        security.requireCsrf(admin, request);
        List<Map<String, Object>> roles = db.queryForList("SELECT role_code FROM roles WHERE role_id=?", id);
        if (roles.isEmpty()) StoreSupport.fail(404, "Không tìm thấy vai trò.");
        if ("ADMIN".equals(roles.get(0).get("role_code"))) StoreSupport.fail(409, "Không thể xóa vai trò quản trị viên.");
        if (!db.queryForList("SELECT 1 FROM user_roles WHERE role_id=?", id).isEmpty()) {
            StoreSupport.fail(409, "Hãy chuyển nhân viên sang vai trò khác trước khi xóa.");
        }
        db.update("DELETE FROM role_permissions WHERE role_id=?", id);
        db.update("DELETE FROM roles WHERE role_id=?", id);
        security.recordActivity(StoreSupport.text(admin.get("username")), "Xóa vai trò", "role", id, request);
        return Map.of("ok", true);
    }

    private static List<String> validatePermissions(Object value) {
        if (!(value instanceof List<?> list)
                || list.stream().anyMatch(item -> !(item instanceof String) || !ALLOWED_PERMISSIONS.contains(item))) {
            StoreSupport.fail(400, "Tên vai trò hoặc danh sách quyền không hợp lệ.");
        }
        return ((List<?>) value).stream().map(Object::toString).distinct().sorted().toList();
    }

    private static void validateName(String name) {
        if (name.length() < 2 || name.length() > 60) StoreSupport.fail(400, "Tên vai trò hoặc danh sách quyền không hợp lệ.");
    }

    private void setPermissions(long roleId, List<String> permissions) {
        db.update("DELETE FROM role_permissions WHERE role_id=?", roleId);
        for (String permission : permissions) {
            db.update("""
                INSERT INTO role_permissions(role_id,permission_id)
                SELECT ?,permission_id FROM permissions WHERE permission_code=?
                """, roleId, permission);
        }
    }
}
