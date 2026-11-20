package vn.swoosh.store.members.vietanh.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import vn.swoosh.store.SecurityService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/logs")
public class ActivityController {
    private final JdbcTemplate db;
    private final SecurityService security;

    public ActivityController(JdbcTemplate db, SecurityService security) {
        this.db = db;
        this.security = security;
    }

    @GetMapping
    Map<String, Object> list(@RequestParam(defaultValue = "") String q, HttpServletRequest request) {
        security.requireRole(request, "admin");
        String query = q.trim();
        String search = "%" + query + "%";
        return Map.of("logs", db.queryForList("""
            SELECT actor,action,entity,entity_id,created_at,ip_address FROM app_activity_logs
            WHERE ?='' OR actor LIKE ? OR action LIKE ? OR entity LIKE ? OR entity_id LIKE ?
            ORDER BY id DESC OFFSET 0 ROWS FETCH NEXT 500 ROWS ONLY
            """, query, search, search, search, search));
    }
}
