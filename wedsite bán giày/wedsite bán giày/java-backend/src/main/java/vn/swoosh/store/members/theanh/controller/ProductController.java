package vn.swoosh.store.members.theanh.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import vn.swoosh.store.StoreSupport;
import vn.swoosh.store.SecurityService;
import org.springframework.jdbc.core.JdbcTemplate;
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
public class ProductController {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final SecurityService security;

    public ProductController(JdbcTemplate db, ObjectMapper json, SecurityService security) {
        this.db = db;
        this.json = json;
        this.security = security;
    }

    @GetMapping("/products")
    Map<String, Object> products(@RequestParam Map<String, String> filters) {
        StringBuilder query = new StringBuilder(productQuery()).append(" WHERE shoes.status='ACTIVE'");
        List<Object> parameters = new ArrayList<>();
        String name = filters.getOrDefault("name", filters.getOrDefault("q", "")).trim();
        if (!name.isEmpty()) {
            query.append(" AND (shoes.shoe_name LIKE ? OR shoes.shoe_code LIKE ?)");
            parameters.add("%" + name + "%");
            parameters.add("%" + name + "%");
        }
        String purpose = filters.getOrDefault("purpose", "").trim();
        if (!purpose.isEmpty()) {
            query.append("""
                 AND (
                   shoes.shoe_name LIKE ? OR EXISTS (
                     SELECT 1 FROM shoe_suitable_terrains suitability
                     JOIN terrain_types terrain ON terrain.terrain_id=suitability.terrain_id
                     WHERE suitability.shoe_id=shoes.shoe_id
                       AND (terrain.terrain_name LIKE ? OR terrain.terrain_code LIKE ?)
                   ) OR EXISTS (
                     SELECT 1 FROM running_distances distance
                     WHERE distance.distance_id=shoes.suitable_distance_id
                       AND (distance.distance_label LIKE ? OR distance.distance_code LIKE ?)
                   )
                 )
                """);
            for (int i = 0; i < 5; i++) parameters.add("%" + purpose + "%");
        }
        addRange(query, parameters, "COALESCE(shoes.sale_price,shoes.base_price)", "minPrice", "maxPrice", filters);
        addRange(query, parameters, "shoes.weight_gram", "minWeight", "maxWeight", filters);
        String stability = filters.getOrDefault("stability", "").trim();
        if (!stability.isEmpty()) {
            query.append(" AND shoes.stability_level LIKE ?");
            parameters.add("%" + stability + "%");
        }
        String terrain = filters.getOrDefault("terrain", "").trim();
        if (!terrain.isEmpty()) {
            query.append("""
                 AND EXISTS (
                   SELECT 1 FROM shoe_suitable_terrains suitability
                   JOIN terrain_types terrain ON terrain.terrain_id=suitability.terrain_id
                   WHERE suitability.shoe_id=shoes.shoe_id AND UPPER(terrain.terrain_code)=UPPER(?)
                 )
                """);
            parameters.add(terrain);
        }
        String color = filters.getOrDefault("color", "").trim();
        if (!color.isEmpty()) {
            query.append("""
                 AND EXISTS (
                   SELECT 1 FROM shoe_variants variants
                   JOIN shoe_colors colors ON colors.color_id=variants.color_id
                   WHERE variants.shoe_id=shoes.shoe_id AND variants.is_active=1
                     AND (colors.color_name LIKE ? OR colors.hex_code LIKE ?)
                 )
                """);
            parameters.add("%" + color + "%");
            parameters.add("%" + color + "%");
        }
        String arch = filters.getOrDefault("arch", "").trim();
        if (!arch.isEmpty()) {
            query.append("""
                 AND EXISTS (
                   SELECT 1 FROM foot_arch_types arch
                   WHERE arch.arch_type_id=shoes.suitable_arch_id
                     AND (arch.arch_code LIKE ? OR arch.arch_name LIKE ?)
                 )
                """);
            parameters.add("%" + arch + "%");
            parameters.add("%" + arch + "%");
        }
        String distance = filters.getOrDefault("distance", "").trim();
        if (!distance.isEmpty()) {
            query.append("""
                 AND EXISTS (
                   SELECT 1 FROM running_distances distance
                   WHERE distance.distance_id=shoes.suitable_distance_id
                     AND (distance.distance_code LIKE ? OR distance.distance_label LIKE ?)
                 )
                """);
            parameters.add("%" + distance + "%");
            parameters.add("%" + distance + "%");
        }
        String size = filters.getOrDefault("sizeEU", "").trim();
        if (!size.isEmpty()) {
            query.append("""
                 AND EXISTS (
                   SELECT 1 FROM shoe_variants variants
                   JOIN size_charts sizes ON sizes.size_id=variants.size_id
                   WHERE variants.shoe_id=shoes.shoe_id AND variants.is_active=1 AND sizes.size_eu=?
                 )
                """);
            parameters.add(parseDecimal(size, "Cỡ giày không hợp lệ."));
        }
        query.append(" ORDER BY shoes.shoe_name");
        return Map.of("products", db.queryForList(query.toString(), parameters.toArray())
            .stream().map(row -> StoreSupport.productJson(json, row)).toList());
    }

    @GetMapping("/top-products")
    Map<String, Object> topProducts() {
        return Map.of("products", db.queryForList("""
            SELECT COALESCE(items.shoe_code_snapshot,shoes.shoe_code) AS id,
                   COALESCE(items.shoe_name_snapshot,shoes.shoe_name) AS name,
                   SUM(items.quantity) AS quantity,SUM(items.quantity*items.unit_price) AS revenue
            FROM order_items items
            JOIN orders ON orders.order_id=items.order_id
            JOIN shoe_variants variants ON variants.variant_id=items.variant_id
            JOIN running_shoes shoes ON shoes.shoe_id=variants.shoe_id
            WHERE UPPER(orders.order_status) IN ('CONFIRMED','SHIPPING','COMPLETED','DELIVERED')
            GROUP BY COALESCE(items.shoe_code_snapshot,shoes.shoe_code),
                     COALESCE(items.shoe_name_snapshot,shoes.shoe_name)
            ORDER BY quantity DESC,revenue DESC OFFSET 0 ROWS FETCH NEXT 10 ROWS ONLY
            """));
    }

    static String productQuery() {
        return """
            SELECT shoes.shoe_code AS id,shoes.shoe_name AS name,
                   CAST(COALESCE(shoes.sale_price,shoes.base_price)/1000 AS INT) AS price,
                   COALESCE((
                     SELECT SUM(stocks.stock_quantity)
                     FROM shoe_variants variants
                     JOIN inventory_stocks stocks ON stocks.variant_id=variants.variant_id
                     WHERE variants.shoe_id=shoes.shoe_id
                   ),0) AS stock,
                   CASE WHEN shoes.status='ACTIVE' THEN 1 ELSE 0 END AS active,
                   CAST('#e8e5df' AS NVARCHAR(20)) AS bg,
                   CAST('[[\"#ffffff\",\"#111111\",\"#cccccc\"]]' AS NVARCHAR(MAX)) AS colors
            FROM running_shoes shoes
            """;
    }

    @GetMapping("/size-chart")
    Map<String, Object> sizeChart() {
        return Map.of("sizes", db.queryForList("""
            SELECT size_id AS id,size_eu AS eu,size_us AS us,size_uk AS uk,size_cm AS cm
            FROM size_charts ORDER BY size_eu
            """));
    }

    @GetMapping("/brands")
    Map<String, Object> brands() {
        return Map.of("brands", db.queryForList(
            "SELECT brand_id AS id,brand_name AS name,country FROM brands ORDER BY brand_name"));
    }

    @GetMapping("/guides/{type}")
    Map<String, Object> guide(@PathVariable String type) {
        if (!Set.of("HOW_TO_MEASURE_FOOT", "HOW_TO_DETERMINE_ARCH", "SHOE_CARE_TIPS", "SIZE_GUIDE")
                .contains(type.toUpperCase())) {
            StoreSupport.fail(404, "Không tìm thấy hướng dẫn.");
        }
        List<Map<String, Object>> rows = db.queryForList("""
            SELECT guidance_id AS id,guide_type AS type,title,content_html,updated_at
            FROM guidance_contents WHERE guide_type=?
            """, type.toUpperCase());
        if (rows.isEmpty()) StoreSupport.fail(404, "Chưa có nội dung hướng dẫn này.");
        return Map.of("guide", rows.get(0));
    }

    @GetMapping("/products/{shoeCode}/variants")
    Map<String, Object> variants(@PathVariable String shoeCode) {
        return Map.of("variants", db.queryForList("""
            SELECT variants.variant_id AS id,variants.sku,variants.image_url,
                   variants.width_option,colors.color_id AS color_id,colors.color_name,
                   colors.hex_code,sizes.size_eu AS size,
                   COALESCE(SUM(stocks.stock_quantity-stocks.reserved_quantity),0) AS stock
            FROM running_shoes shoes
            JOIN shoe_variants variants ON variants.shoe_id=shoes.shoe_id AND variants.is_active=1
            JOIN shoe_colors colors ON colors.color_id=variants.color_id
            JOIN size_charts sizes ON sizes.size_id=variants.size_id
            LEFT JOIN inventory_stocks stocks ON stocks.variant_id=variants.variant_id
            WHERE shoes.shoe_code=? AND shoes.status='ACTIVE'
            GROUP BY variants.variant_id,variants.sku,variants.image_url,variants.width_option,
                     colors.color_id,colors.color_name,colors.hex_code,sizes.size_eu
            ORDER BY colors.color_name,sizes.size_eu
            """, shoeCode));
    }

    @GetMapping("/admin/size-chart")
    Map<String, Object> adminSizeChart(HttpServletRequest request) {
        security.requireRole(request, "admin");
        return sizeChart();
    }

    @PostMapping("/admin/size-chart")
    ResponseEntity<Map<String, Object>> createSize(@RequestBody Map<String, Object> data,
                                                   HttpServletRequest request) {
        Map<String, Object> admin = security.requireRole(request, "admin");
        security.requireCsrf(admin, request);
        Map<String, Object> size = validateSize(data);
        try {
            Long id = db.queryForObject("""
                INSERT INTO size_charts(size_eu,size_us,size_uk,size_cm)
                OUTPUT INSERTED.size_id VALUES(?,?,?,?)
                """, Long.class, size.get("eu"), size.get("us"), size.get("uk"), size.get("cm"));
            security.recordActivity(StoreSupport.text(admin.get("username")), "Thêm cỡ giày", "size_chart", id, request);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id, "ok", true));
        } catch (DataIntegrityViolationException error) {
            StoreSupport.fail(409, "Cỡ EU đã tồn tại.");
            return null;
        }
    }

    @PatchMapping("/admin/size-chart/{id}")
    Map<String, Object> updateSize(@PathVariable int id, @RequestBody Map<String, Object> data,
                                   HttpServletRequest request) {
        Map<String, Object> admin = security.requireRole(request, "admin");
        security.requireCsrf(admin, request);
        Map<String, Object> size = validateSize(data);
        try {
            if (db.update("UPDATE size_charts SET size_eu=?,size_us=?,size_uk=?,size_cm=? WHERE size_id=?",
                    size.get("eu"), size.get("us"), size.get("uk"), size.get("cm"), id) == 0) {
                StoreSupport.fail(404, "Không tìm thấy cỡ giày.");
            }
        } catch (DataIntegrityViolationException error) {
            StoreSupport.fail(409, "Cỡ EU đã tồn tại.");
        }
        security.recordActivity(StoreSupport.text(admin.get("username")), "Cập nhật bảng size", "size_chart", id, request);
        return Map.of("ok", true);
    }

    @DeleteMapping("/admin/size-chart/{id}")
    Map<String, Object> deleteSize(@PathVariable int id, HttpServletRequest request) {
        Map<String, Object> admin = security.requireRole(request, "admin");
        security.requireCsrf(admin, request);
        if (!db.queryForList("SELECT 1 FROM size_charts WHERE size_id=?", id).isEmpty()
                && !db.queryForList("SELECT 1 FROM shoe_variants WHERE size_id=?", id).isEmpty()) {
            StoreSupport.fail(409, "Cỡ giày đang được dùng cho sản phẩm.");
        }
        if (db.update("DELETE FROM size_charts WHERE size_id=?", id) == 0) StoreSupport.fail(404, "Không tìm thấy cỡ giày.");
        security.recordActivity(StoreSupport.text(admin.get("username")), "Xóa cỡ giày", "size_chart", id, request);
        return Map.of("ok", true);
    }

    @PostMapping("/admin/products/{shoeCode}/variants")
    @org.springframework.transaction.annotation.Transactional
    ResponseEntity<Map<String, Object>> createVariant(@PathVariable String shoeCode,
                                                       @RequestBody Map<String, Object> data,
                                                       HttpServletRequest request) {
        Map<String, Object> admin = security.requireRole(request, "admin");
        security.requireCsrf(admin, request);
        int colorId = StoreSupport.integer(data.get("colorId"), "Màu sắc không hợp lệ.");
        int sizeId = StoreSupport.integer(data.get("sizeId"), "Cỡ giày không hợp lệ.");
        String sku = StoreSupport.text(data.get("sku")).trim();
        String imageUrl = StoreSupport.text(data.get("imageUrl")).trim();
        if (!Pattern.matches("[A-Za-z0-9._/-]{2,100}", sku) || imageUrl.length() > 500) {
            StoreSupport.fail(400, "SKU hoặc đường dẫn ảnh không hợp lệ.");
        }
        List<Map<String, Object>> shoes = db.queryForList("SELECT shoe_id FROM running_shoes WHERE shoe_code=?", shoeCode);
        if (shoes.isEmpty()) StoreSupport.fail(404, "Không tìm thấy mẫu giày.");
        int warehouse = db.queryForObject(
            "SELECT TOP (1) warehouse_id FROM warehouses WHERE warehouse_name=N'Kho chính'", Integer.class);
        int initialStock = Math.max(0, StoreSupport.integer(data.getOrDefault("stock", 0), "Tồn kho không hợp lệ."));
        try {
            Long id = db.queryForObject("""
                INSERT INTO shoe_variants(shoe_id,color_id,size_id,width_option,sku,image_url,is_active)
                OUTPUT INSERTED.variant_id VALUES(?,?,?,?,?,?,1)
                """, Long.class, shoes.get(0).get("shoe_id"), colorId, sizeId,
                StoreSupport.text(data.getOrDefault("width", "Standard")), sku, imageUrl);
            db.update("""
                INSERT INTO inventory_stocks(warehouse_id,variant_id,stock_quantity,reserved_quantity,updated_at)
                VALUES(?,?,?,0,SYSUTCDATETIME())
                """, warehouse, id, initialStock);
            security.recordActivity(StoreSupport.text(admin.get("username")), "Thêm ảnh/biến thể giày",
                "shoe_variant", id, request);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id, "ok", true));
        } catch (DataIntegrityViolationException error) {
            StoreSupport.fail(409, "SKU, màu sắc hoặc size không hợp lệ hoặc đã tồn tại.");
            return null;
        }
    }

    @PatchMapping("/admin/variants/{id}/image")
    Map<String, Object> updateVariantImage(@PathVariable int id, @RequestBody Map<String, Object> data,
                                           HttpServletRequest request) {
        Map<String, Object> admin = security.requireRole(request, "admin");
        security.requireCsrf(admin, request);
        String imageUrl = StoreSupport.text(data.get("imageUrl")).trim();
        if (imageUrl.length() > 500 || (!imageUrl.isEmpty()
                && (!imageUrl.startsWith("/images/") || imageUrl.contains("..")))) {
            StoreSupport.fail(400, "Ảnh sản phẩm phải là đường dẫn trong thư mục images.");
        }
        if (db.update("UPDATE shoe_variants SET image_url=? WHERE variant_id=?",
                imageUrl.isEmpty() ? null : imageUrl, id) == 0) {
            StoreSupport.fail(404, "Không tìm thấy biến thể giày.");
        }
        security.recordActivity(StoreSupport.text(admin.get("username")), "Cập nhật ảnh biến thể",
            "shoe_variant", id, request);
        return Map.of("ok", true);
    }

    @DeleteMapping("/admin/variants/{id}")
    @org.springframework.transaction.annotation.Transactional
    Map<String, Object> deleteVariant(@PathVariable int id, HttpServletRequest request) {
        Map<String, Object> admin = security.requireRole(request, "admin");
        security.requireCsrf(admin, request);
        List<Map<String, Object>> stock = db.queryForList(
            "SELECT reserved_quantity FROM inventory_stocks WHERE variant_id=?", id);
        if (stock.isEmpty()) StoreSupport.fail(404, "Không tìm thấy biến thể giày.");
        if (stock.stream().anyMatch(row -> StoreSupport.number(row.get("reserved_quantity")) > 0)
                || !db.queryForList("SELECT 1 FROM order_items WHERE variant_id=?", id).isEmpty()) {
            StoreSupport.fail(409, "Không thể xóa biến thể đang được đặt hoặc có trong lịch sử đơn.");
        }
        db.update("DELETE FROM inventory_stocks WHERE variant_id=?", id);
        db.update("UPDATE shoe_variants SET is_active=0 WHERE variant_id=?", id);
        security.recordActivity(StoreSupport.text(admin.get("username")), "Ngừng bán biến thể giày",
            "shoe_variant", id, request);
        return Map.of("ok", true);
    }

    private static void addRange(StringBuilder query, List<Object> parameters, String expression,
                                 String minKey, String maxKey, Map<String, String> filters) {
        addBound(query, parameters, expression, filters.get(minKey), ">=", minKey);
        addBound(query, parameters, expression, filters.get(maxKey), "<=", maxKey);
    }

    private static void addBound(StringBuilder query, List<Object> parameters, String expression,
                                 String raw, String operator, String field) {
        if (raw == null || raw.isBlank()) return;
        long value;
        try {
            value = Long.parseLong(raw);
        } catch (NumberFormatException error) {
            StoreSupport.fail(400, "Bộ lọc " + field + " không hợp lệ.");
            return;
        }
        if (value < 0 || value > 1_000_000_000L) StoreSupport.fail(400, "Bộ lọc " + field + " không hợp lệ.");
        query.append(" AND ").append(expression).append(' ').append(operator).append(" ?");
        parameters.add(value);
    }

    private static double parseDecimal(String raw, String message) {
        try {
            double value = Double.parseDouble(raw);
            if (!Double.isFinite(value) || value <= 0) StoreSupport.fail(400, message);
            return value;
        } catch (NumberFormatException error) {
            StoreSupport.fail(400, message);
            return 0;
        }
    }

    private static Map<String, Object> validateSize(Map<String, Object> data) {
        double eu = parseDecimal(StoreSupport.text(data.get("eu")), "Cỡ EU không hợp lệ.");
        double cm = parseDecimal(StoreSupport.text(data.get("cm")), "Chiều dài bàn chân không hợp lệ.");
        Object rawUs = data.get("us");
        Object rawUk = data.get("uk");
        Double us = rawUs == null || StoreSupport.text(rawUs).isBlank()
            ? null : parseDecimal(StoreSupport.text(rawUs), "Cỡ US không hợp lệ.");
        Double uk = rawUk == null || StoreSupport.text(rawUk).isBlank()
            ? null : parseDecimal(StoreSupport.text(rawUk), "Cỡ UK không hợp lệ.");
        if (eu < 15 || eu > 55 || cm < 10 || cm > 40
                || (us != null && (us < 1 || us > 25)) || (uk != null && (uk < 1 || uk > 25))) {
            StoreSupport.fail(400, "Thông số bảng size nằm ngoài khoảng cho phép.");
        }
        Map<String, Object> size = new HashMap<>();
        size.put("eu", eu);
        size.put("us", us);
        size.put("uk", uk);
        size.put("cm", cm);
        return size;
    }
}
