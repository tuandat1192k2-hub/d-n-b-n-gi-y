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
@RequestMapping("/api/admin/customers")
public class CustomerController {
    private final JdbcTemplate db;
    private final SecurityService security;

    public CustomerController(JdbcTemplate db, SecurityService security) {
        this.db = db;
        this.security = security;
    }

    @GetMapping
    Map<String, Object> list(@RequestParam(defaultValue = "") String q, HttpServletRequest request) {
        security.requireRole(request, "admin");
        String query = q.trim();
        String search = "%" + query + "%";
        return Map.of("customers", db.queryForList("""
            WITH ranked_orders AS (
              SELECT recipient_phone,recipient_name,created_at,
                     ROW_NUMBER() OVER(PARTITION BY recipient_phone ORDER BY order_id DESC) AS position FROM orders
            ), customer_totals AS (
              SELECT recipient_phone,COUNT(*) AS order_count,
                     SUM(CASE WHEN UPPER(order_status)<>'CANCELLED' THEN final_amount ELSE 0 END) AS total_spent,
                     MAX(created_at) AS last_order_at
              FROM orders GROUP BY recipient_phone
            )
            SELECT totals.recipient_phone AS phone,ranked.recipient_name AS full_name,
                   totals.order_count,totals.total_spent,totals.last_order_at,
                   COALESCE(accounts.points,0) AS points,
                   (SELECT TOP (1) tier_name FROM loyalty_tiers
                    WHERE min_points<=COALESCE(accounts.points,0) ORDER BY min_points DESC) AS tier_name
            FROM customer_totals totals
            JOIN ranked_orders ranked ON ranked.recipient_phone=totals.recipient_phone AND ranked.position=1
            LEFT JOIN users ON users.phone_number=totals.recipient_phone
            LEFT JOIN loyalty_accounts accounts ON accounts.user_id=users.user_id
            WHERE (?='' OR totals.recipient_phone LIKE ? OR ranked.recipient_name LIKE ?)
            ORDER BY totals.last_order_at DESC OFFSET 0 ROWS FETCH NEXT 500 ROWS ONLY
            """, query, search, search));
    }
}
