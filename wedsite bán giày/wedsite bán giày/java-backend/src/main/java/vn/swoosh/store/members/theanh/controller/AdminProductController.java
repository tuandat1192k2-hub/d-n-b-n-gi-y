package vn.swoosh.store.members.theanh.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
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
@RequestMapping("/api/admin/products")
public class AdminProductController {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final SecurityService security;

    public AdminProductController(JdbcTemplate db, ObjectMapper json, SecurityService security) {
        this.db = db;
        this.json = json;
        this.security = security;
    }

    @GetMapping
    Map<String, Object> list(HttpServletRequest request) {
        security.requireRole(request, "admin");
        return Map.of("products", db.queryForList(ProductController.productQuery() + " ORDER BY shoes.shoe_name")
            .stream().map(row -> StoreSupport.productJson(json, row)).toList());
    }

    @PostMapping
    @Transactional
    ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> data, HttpServletRequest request) {
        Map<String, Object> admin = security.requireRole(request, "admin");
        security.requireCsrf(admin, request);
        Map<String, Object> product = StoreSupport.validateProduct(data, null);
        try {
            Integer brandId = db.queryForObject(
                "SELECT TOP (1) brand_id FROM brands WHERE brand_name=N'Swoosh'", Integer.class);
            Long shoeId = db.queryForObject("""
                INSERT INTO running_shoes(
                  shoe_code,shoe_name,brand_id,gender_target,base_price,weight_gram,midsole_thickness_mm,
                  heel_to_toe_drop_mm,cushion_level,stability_level,is_beginner_friendly,status
                )
                OUTPUT INSERTED.shoe_id
                VALUES(?,?,?,N'Unisex',?,250,25,10,N'Medium',N'Neutral',1,?)
                """, Long.class, product.get("id"), product.get("name"), brandId,
                StoreSupport.number(product.get("price")) * 1000,
                Boolean.TRUE.equals(product.get("active")) ? "ACTIVE" : "HIDDEN");
            Integer colorId = db.queryForObject(
                "SELECT TOP (1) color_id FROM shoe_colors WHERE color_name=N'Mặc định'", Integer.class);
            Integer warehouseId = db.queryForObject(
                "SELECT TOP (1) warehouse_id FROM warehouses WHERE warehouse_name=N'Kho chính'", Integer.class);
            List<Map<String, Object>> sizes = db.queryForList(
                "SELECT size_id,size_eu FROM size_charts WHERE size_eu BETWEEN 38 AND 45 ORDER BY size_eu");
            if (sizes.isEmpty()) throw new IllegalStateException("The shoe size chart has no supported sizes.");
            int totalStock = Math.toIntExact(StoreSupport.number(product.get("stock")));
            for (int index = 0; index < sizes.size(); index++) {
                Map<String, Object> size = sizes.get(index);
                int euSize = ((Number) size.get("size_eu")).intValue();
                Long variantId = db.queryForObject("""
                    INSERT INTO shoe_variants(shoe_id,color_id,size_id,width_option,sku,is_active)
                    OUTPUT INSERTED.variant_id VALUES(?,?,?,N'Standard',?,1)
                    """, Long.class, shoeId, colorId, size.get("size_id"),
                    product.get("id") + "-default-" + euSize);
                int quantity = totalStock / sizes.size() + (index < totalStock % sizes.size() ? 1 : 0);
                db.update("""
                    INSERT INTO inventory_stocks(warehouse_id,variant_id,stock_quantity,reserved_quantity,updated_at)
                    VALUES(?,?,?,0,SYSUTCDATETIME())
                    """, warehouseId, variantId, quantity);
            }
        } catch (DataIntegrityViolationException error) {
            StoreSupport.fail(409, "Mã sản phẩm đã tồn tại.");
        }
        security.recordActivity(StoreSupport.text(admin.get("username")), "Thêm sản phẩm", "product",
            product.get("id"), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("product", product));
    }

    @PatchMapping("/{id}")
    @Transactional
    Map<String, Object> update(@PathVariable String id, @RequestBody Map<String, Object> data,
                               HttpServletRequest request) {
        Map<String, Object> admin = security.requireRole(request, "admin");
        security.requireCsrf(admin, request);
        Map<String, Object> product = StoreSupport.validateProduct(data, id);
        List<Map<String, Object>> old = db.queryForList("""
            SELECT shoe_id,CASE WHEN status='ACTIVE' THEN 1 ELSE 0 END AS active
            FROM running_shoes WHERE shoe_code=?
            """, id);
        if (old.isEmpty()) StoreSupport.fail(404, "Không tìm thấy sản phẩm.");
        if (StoreSupport.number(old.get(0).get("active")) != 0 && Boolean.FALSE.equals(product.get("active"))
                && StoreSupport.number(db.queryForObject(
                    "SELECT COUNT(*) FROM running_shoes WHERE status='ACTIVE'", Long.class)) <= 1) {
            StoreSupport.fail(409, "Cần duy trì ít nhất một sản phẩm đang bán.");
        }
        db.update("""
            UPDATE running_shoes SET shoe_name=?,base_price=?,sale_price=NULL,status=? WHERE shoe_code=?
            """, product.get("name"), StoreSupport.number(product.get("price")) * 1000,
            Boolean.TRUE.equals(product.get("active")) ? "ACTIVE" : "HIDDEN", id);
        List<Map<String, Object>> variants = db.queryForList("""
            SELECT variants.variant_id,stocks.reserved_quantity
            FROM shoe_variants variants
            JOIN inventory_stocks stocks ON stocks.variant_id=variants.variant_id
            JOIN warehouses ON warehouses.warehouse_id=stocks.warehouse_id
            WHERE variants.shoe_id=? AND warehouses.warehouse_name=N'Kho chính'
            ORDER BY variants.variant_id
            """, old.get(0).get("shoe_id"));
        if (variants.isEmpty()) StoreSupport.fail(409, "Sản phẩm chưa có biến thể kho để cập nhật tồn.");
        int totalStock = Math.toIntExact(StoreSupport.number(product.get("stock")));
        for (int index = 0; index < variants.size(); index++) {
            Map<String, Object> variant = variants.get(index);
            int quantity = totalStock / variants.size() + (index < totalStock % variants.size() ? 1 : 0);
            if (quantity < StoreSupport.number(variant.get("reserved_quantity"))) {
                StoreSupport.fail(409, "Tồn kho mới thấp hơn số lượng đang được giữ cho đơn hàng.");
            }
            db.update("""
                UPDATE inventory_stocks SET stock_quantity=?,updated_at=SYSUTCDATETIME()
                WHERE variant_id=? AND warehouse_id=(
                  SELECT warehouse_id FROM warehouses WHERE warehouse_name=N'Kho chính'
                )
                """, quantity, variant.get("variant_id"));
        }
        security.recordActivity(StoreSupport.text(admin.get("username")), "Cập nhật sản phẩm", "product", id, request);
        Map<String, Object> updated = db.queryForMap(ProductController.productQuery() + " WHERE shoes.shoe_code=?", id);
        return Map.of("product", StoreSupport.productJson(json, updated));
    }
}
