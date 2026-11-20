package vn.swoosh.store.members.tuandat.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.swoosh.store.QueryCatalogService;
import vn.swoosh.store.SecurityService;

/** API quản trị để xem và chạy 109 câu truy vấn trong file ULNL_cau_lenh_truy_van.xlsx. */
@RestController
@RequestMapping("/api/admin/queries")
public class QueryController {
    private final QueryCatalogService catalog;
    private final SecurityService security;

    public QueryController(QueryCatalogService catalog, SecurityService security) {
        this.catalog = catalog;
        this.security = security;
    }

    /** Danh sách câu truy vấn; lọc theo người phụ trách bằng ?owner=Đạt. */
    @GetMapping
    Map<String, Object> list(@RequestParam(required = false) String owner, HttpServletRequest request) {
        security.requireRole(request, "admin");
        List<Map<String, Object>> queries = catalog.list(owner);
        return Map.of("total", queries.size(), "queries", queries);
    }

    @GetMapping("/{stt}")
    Map<String, Object> detail(@PathVariable int stt, HttpServletRequest request) {
        security.requireRole(request, "admin");
        return catalog.detail(stt);
    }

    /**
     * Chạy câu truy vấn. Body: {"params": {"email": "a@b.c"}, "commit": false}.
     * Mặc định commit = false: chạy rồi ROLLBACK để xem kết quả mà không đổi dữ liệu.
     */
    @PostMapping("/{stt}/run")
    Map<String, Object> run(@PathVariable int stt, @RequestBody(required = false) Map<String, Object> body,
                            HttpServletRequest request) {
        Map<String, Object> admin = security.requireRole(request, "admin");
        security.requireCsrf(admin, request);
        Map<String, Object> data = body == null ? Map.of() : body;
        Map<String, Object> params = new HashMap<>();
        if (data.get("params") instanceof Map<?, ?> given) {
            given.forEach((key, value) -> params.put(String.valueOf(key), value));
        }
        boolean commit = Boolean.TRUE.equals(data.get("commit"));
        Map<String, Object> result = catalog.run(stt, params, commit);
        security.recordActivity(String.valueOf(admin.get("username")),
            commit ? "Chạy truy vấn (ghi dữ liệu)" : "Chạy truy vấn (thử)", "query", stt, request);
        return result;
    }
}
