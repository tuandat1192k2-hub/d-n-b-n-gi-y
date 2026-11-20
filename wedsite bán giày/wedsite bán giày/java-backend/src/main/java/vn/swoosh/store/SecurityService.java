package vn.swoosh.store;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class SecurityService {
    private static final long SESSION_SECONDS = 8 * 60 * 60;
    private final JdbcTemplate db;
    private final StoreApplication.StoreProperties properties;
    private final SecureRandom random = new SecureRandom();

    public SecurityService(JdbcTemplate db, StoreApplication.StoreProperties properties) {
        this.db = db;
        this.properties = properties;
    }

    public Map<String, Object> currentSession(HttpServletRequest request) {
        String token = null;
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if ("swoosh_session".equals(cookie.getName())) token = cookie.getValue();
            }
        }
        if (token == null || token.isBlank()) return null;
        List<Map<String, Object>> rows = db.queryForList("""
            SELECT sessions.token_hash,sessions.csrf_token,sessions.user_id,sessions.expires_at,
                   users.username,users.phone_number,users.full_name,
                   CASE WHEN users.status='ACTIVE' THEN 1 ELSE 0 END AS active,
                   CASE WHEN roles.role_code='ADMIN' THEN 'admin'
                        WHEN roles.role_code='CUSTOMER' THEN 'customer'
                        WHEN roles.role_id IS NOT NULL THEN 'staff' END AS role,
                   roles.role_id
            FROM app_sessions sessions
            LEFT JOIN users ON users.user_id=sessions.user_id
            OUTER APPLY (
              SELECT TOP (1) role.role_id,role.role_code
              FROM user_roles user_role JOIN roles role ON role.role_id=user_role.role_id
              WHERE user_role.user_id=users.user_id
              ORDER BY CASE WHEN role.role_code='ADMIN' THEN 0 ELSE 1 END,role.role_id
            ) roles
            WHERE sessions.token_hash=? AND sessions.expires_at>?
            """, StoreSupport.sha256(token), Instant.now().getEpochSecond());
        if (rows.isEmpty()) return null;
        Map<String, Object> session = rows.get(0);
        if (session.get("user_id") != null && StoreSupport.number(session.get("active")) == 0) return null;
        return session;
    }

    public Map<String, Object> requireRole(HttpServletRequest request, String... allowed) {
        Map<String, Object> session = currentSession(request);
        if (session == null || session.get("user_id") == null) StoreSupport.fail(401, "Vui lòng đăng nhập.");
        for (String role : allowed) {
            if (role.equals(session.get("role"))) return session;
        }
        StoreSupport.fail(403, "Tài khoản không có quyền thực hiện thao tác này.");
        return session;
    }

    public Map<String, Object> requirePermission(HttpServletRequest request, String permission) {
        Map<String, Object> session = requireRole(request, "admin", "staff");
        if ("admin".equals(session.get("role"))) return session;
        if (session.get("role_id") == null) StoreSupport.fail(403, "Tài khoản chưa được gán vai trò.");
        List<String> permissions = db.queryForList("""
            SELECT permissions.permission_code
            FROM role_permissions assignments
            JOIN permissions ON permissions.permission_id=assignments.permission_id
            WHERE assignments.role_id=?
            """, String.class, session.get("role_id"));
        if (!permissions.contains(permission)) {
            StoreSupport.fail(403, "Vai trò của bạn không có quyền thực hiện thao tác này.");
        }
        return session;
    }

    public void requireCsrf(Map<String, Object> session, HttpServletRequest request) {
        String supplied = request.getHeader("X-CSRF-Token");
        if (supplied == null || !MessageDigest.isEqual(
                StoreSupport.text(session.get("csrf_token")).getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8))) {
            StoreSupport.fail(403, "Phiên gửi biểu mẫu hết hạn. Hãy tải lại trang rồi thử lại.");
        }
    }

    public Map<String, Object> sessionUser(Map<String, Object> row) {
        Map<String, Object> user = new HashMap<>();
        user.put("username", row.get("username"));
        user.put("role", row.get("role"));
        user.put("phone", row.get("phone_number"));
        user.put("fullName", row.get("full_name"));
        if ("staff".equals(row.get("role"))) {
            user.put("permissions", db.queryForList("""
                SELECT permissions.permission_code
                FROM role_permissions assignments
                JOIN permissions ON permissions.permission_id=assignments.permission_id
                WHERE assignments.role_id=?
                """, String.class, row.get("role_id")));
        }
        return user;
    }

    public ResponseEntity<Map<String, Object>> createSession(Long userId, HttpServletRequest request,
                                                              HttpServletResponse response) {
        byte[] tokenBytes = new byte[32];
        byte[] csrfBytes = new byte[32];
        random.nextBytes(tokenBytes);
        random.nextBytes(csrfBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        String csrf = Base64.getUrlEncoder().withoutPadding().encodeToString(csrfBytes);
        db.update("INSERT INTO app_sessions(token_hash,csrf_token,user_id,expires_at) VALUES(?,?,?,?)",
            StoreSupport.sha256(token), csrf, userId, Instant.now().getEpochSecond() + SESSION_SECONDS);
        boolean secure = properties.cookieSecure()
            || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto"));
        String cookie = "swoosh_session=" + token + "; Path=/; HttpOnly; SameSite=Lax; Max-Age="
            + SESSION_SECONDS + (secure ? "; Secure" : "");
        response.addHeader(HttpHeaders.SET_COOKIE, cookie);
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
            .body(Map.of("csrfToken", csrf));
    }

    public void expireCookie(HttpServletRequest request, HttpServletResponse response) {
        boolean secure = properties.cookieSecure()
            || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto"));
        response.addHeader(HttpHeaders.SET_COOKIE, "swoosh_session=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0"
            + (secure ? "; Secure" : ""));
    }

    public String clientIp(HttpServletRequest request) {
        if (properties.trustProxy()) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    public void recordActivity(String actor, String action, String entity, Object id, HttpServletRequest request) {
        StoreSupport.recordActivity(db, actor, action, entity, id, clientIp(request));
    }
}
