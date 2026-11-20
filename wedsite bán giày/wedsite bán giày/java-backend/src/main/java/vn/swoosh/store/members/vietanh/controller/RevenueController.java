package vn.swoosh.store.members.vietanh.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import vn.swoosh.store.SecurityService;
import vn.swoosh.store.StoreSupport;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/revenue")
public class RevenueController {
    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private final JdbcTemplate db;
    private final SecurityService security;

    public RevenueController(JdbcTemplate db, SecurityService security) {
        this.db = db;
        this.security = security;
    }

    @GetMapping
    Map<String, Object> report(@RequestParam(name = "from") String from,
                               @RequestParam(name = "to") String to, HttpServletRequest request) {
        security.requireRole(request, "admin");
        LocalDate start = StoreSupport.parseDate(from);
        LocalDate end = StoreSupport.parseDate(to);
        if (end.isBefore(start)) StoreSupport.fail(400, "Ngày kết thúc phải từ ngày bắt đầu trở đi.");
        if (end.isAfter(LocalDate.now(VIETNAM))) StoreSupport.fail(400, "Ngày kết thúc không thể nằm trong tương lai.");
        if (end.toEpochDay() - start.toEpochDay() > 365) StoreSupport.fail(400, "Mỗi lần thống kê chỉ hỗ trợ tối đa 366 ngày.");
        java.sql.Timestamp startUtc = java.sql.Timestamp.from(start.atStartOfDay(VIETNAM).toInstant());
        java.sql.Timestamp endUtc = java.sql.Timestamp.from(end.plusDays(1).atStartOfDay(VIETNAM).toInstant());
        Map<String, Object> totals = db.queryForMap("""
            SELECT COALESCE(SUM(CASE WHEN UPPER(order_status)='COMPLETED' THEN final_amount ELSE 0 END),0) AS revenue,
                   SUM(CASE WHEN UPPER(order_status)='COMPLETED' THEN 1 ELSE 0 END) AS completed_order_count,
                   COALESCE(AVG(CASE WHEN UPPER(order_status)='COMPLETED' THEN final_amount END),0) AS average_order_value,
                   COALESCE(SUM(CASE WHEN UPPER(order_status) IN ('CONFIRMED','SHIPPING','PACKING') THEN final_amount ELSE 0 END),0) AS in_progress_revenue,
                   SUM(CASE WHEN UPPER(order_status) IN ('CONFIRMED','SHIPPING','PACKING') THEN 1 ELSE 0 END) AS in_progress_order_count
            FROM orders WHERE created_at>=? AND created_at<?
            """, startUtc, endUtc);
        Map<String, Object> body = new HashMap<>();
        body.put("from", from);
        body.put("to", to);
        body.put("revenue", StoreSupport.number(totals.get("revenue")));
        body.put("completedOrderCount", StoreSupport.number(totals.get("completed_order_count")));
        body.put("averageOrderValue", Math.round(((Number) totals.get("average_order_value")).doubleValue()));
        body.put("inProgressRevenue", StoreSupport.number(totals.get("in_progress_revenue")));
        body.put("inProgressOrderCount", StoreSupport.number(totals.get("in_progress_order_count")));
        body.put("daily", db.queryForList("""
            SELECT CONVERT(DATE,DATEADD(HOUR,7,created_at)) AS day,
                   SUM(final_amount) AS revenue,COUNT(*) AS order_count
            FROM orders WHERE UPPER(order_status)='COMPLETED' AND created_at>=?
              AND created_at<? GROUP BY CONVERT(DATE,DATEADD(HOUR,7,created_at))
            ORDER BY day
            """, startUtc, endUtc));
        return body;
    }
}
