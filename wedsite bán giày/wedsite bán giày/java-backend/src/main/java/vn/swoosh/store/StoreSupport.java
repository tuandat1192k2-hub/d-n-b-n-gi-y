package vn.swoosh.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;

public final class StoreSupport {
    private StoreSupport() {
    }

    public static void fail(int status, String message) {
        throw new ApiException(status, message);
    }

    public static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    public static long number(Object value) {
        if (value instanceof Number number) return number.longValue();
        return value instanceof Boolean bool && bool ? 1L : 0L;
    }

    public static int integer(Object value, String message) {
        if (!(value instanceof Number number) || (number instanceof Double d && d % 1 != 0)
                || (number instanceof Float f && f % 1 != 0)
                || number.longValue() < Integer.MIN_VALUE || number.longValue() > Integer.MAX_VALUE) {
            fail(400, message);
        }
        return ((Number) value).intValue();
    }

    public static long longNumber(Object value, String message) {
        if (!(value instanceof Number number) || (number instanceof Double d && d % 1 != 0)
                || (number instanceof Float f && f % 1 != 0)) {
            fail(400, message);
        }
        return ((Number) value).longValue();
    }

    public static Object readJson(ObjectMapper mapper, Object value) {
        try {
            return mapper.readValue(text(value), new TypeReference<Object>() { });
        } catch (IOException error) {
            throw new IllegalStateException("Invalid JSON stored in the database.", error);
        }
    }

    public static String writeJson(ObjectMapper mapper, Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot serialize JSON.", error);
        }
    }

    public static Map<String, Object> productJson(ObjectMapper mapper, Map<String, Object> row) {
        Map<String, Object> product = new LinkedHashMap<>(row);
        product.put("active", number(row.get("active")) != 0);
        product.put("colors", readJson(mapper, row.get("colors")));
        return product;
    }

    public static Map<String, Object> orderJson(JdbcTemplate db, Map<String, Object> row) {
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("id", row.get("id"));
        order.put("number", row.get("order_number"));
        order.put("customerName", row.get("customer_name"));
        order.put("phone", row.get("phone"));
        order.put("address", row.get("address"));
        order.put("note", row.get("note"));
        order.put("total", row.get("total"));
        order.put("status", row.get("status"));
        order.put("createdAt", row.get("created_at"));
        order.put("voucherCode", row.get("voucher_code"));
        order.put("discount", row.get("discount"));
        order.put("items", db.queryForList(
            """
            SELECT COALESCE(items.shoe_code_snapshot,shoes.shoe_code) AS product_id,
                   COALESCE(items.shoe_name_snapshot,shoes.shoe_name) AS product_name,
                   COALESCE(items.size_eu_snapshot,sizes.size_eu) AS size,
                   items.quantity,items.unit_price
            FROM order_items items
            JOIN shoe_variants variants ON variants.variant_id=items.variant_id
            JOIN running_shoes shoes ON shoes.shoe_id=variants.shoe_id
            JOIN size_charts sizes ON sizes.size_id=variants.size_id
            WHERE items.order_id=?
            """,
            row.get("id")));
        return order;
    }

    public static void recordActivity(JdbcTemplate db, String actor, String action, String entity, Object id, String ip) {
        db.update("INSERT INTO app_activity_logs(actor,action,entity,entity_id,created_at,ip_address) VALUES(?,?,?,?,?,?)",
            actor == null || actor.isBlank() ? "Khách" : actor, action, entity,
            id == null ? "" : id.toString(), java.sql.Timestamp.from(java.time.Instant.now()), ip);
    }

    public static LocalDate parseDate(String value) {
        try {
            LocalDate result = LocalDate.parse(value);
            if (!result.toString().equals(value)) fail(400, "Ngày phải theo định dạng YYYY-MM-DD.");
            return result;
        } catch (RuntimeException error) {
            fail(400, "Ngày bắt đầu và ngày kết thúc không hợp lệ.");
            return LocalDate.MIN;
        }
    }

    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    public static Map<String, Object> validateProduct(Map<String, Object> data, String pathId) {
        String id = pathId == null ? text(data.get("id")) : pathId;
        String name = text(data.get("name")).trim();
        int price = integer(data.get("price"), "Giá sản phẩm phải từ 1 đến 100.000 (nghìn đồng).");
        int stock = integer(data.get("stock"), "Tồn kho phải từ 0 đến 100.000.");
        Object active = data.getOrDefault("active", Boolean.TRUE);
        if (!Pattern.matches("[a-z0-9-]{2,40}", id)) fail(400, "Mã sản phẩm chỉ được gồm chữ thường, số và dấu gạch ngang.");
        if (name.length() < 2 || name.length() > 100
                || !Pattern.matches("[\\p{L}\\p{N}_\\s.,'’()-]{2,100}", name)) {
            fail(400, "Tên sản phẩm phải có từ 2 đến 100 ký tự.");
        }
        if (price < 1 || price > 100000) fail(400, "Giá sản phẩm phải từ 1 đến 100.000 (nghìn đồng).");
        if (stock < 0 || stock > 100000) fail(400, "Tồn kho phải từ 0 đến 100.000.");
        if (!(active instanceof Boolean)) fail(400, "Trạng thái sản phẩm không hợp lệ.");
        return Map.of("id", id, "name", name, "price", price, "stock", stock, "active", active,
            "bg", "#e8e5df", "colors", List.of(List.of("#ffffff", "#111111", "#cccccc")));
    }

    public static Map<String, String> validateStaffProfile(Map<?, ?> data, boolean required) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String field : List.of("full_name", "address", "citizen_id", "birth_date")) {
            if (!data.containsKey(field) && !required) continue;
            Object raw = data.get(field);
            if (!(raw instanceof String)) fail(400, "Vui lòng nhập đầy đủ họ tên, địa chỉ, số CCCD và ngày sinh.");
            String value = ((String) raw).trim();
            switch (field) {
                case "full_name" -> {
                    if (value.length() < 2 || value.length() > 100) fail(400, "Họ tên phải có từ 2 đến 100 ký tự.");
                }
                case "address" -> {
                    if (value.length() < 5 || value.length() > 200) fail(400, "Địa chỉ phải có từ 5 đến 200 ký tự.");
                }
                case "citizen_id" -> {
                    if (!Pattern.matches("[0-9]{12}", value)) fail(400, "Số CCCD phải gồm đúng 12 chữ số.");
                }
                case "birth_date" -> {
                    LocalDate date = parseDate(value);
                    if (date.isBefore(LocalDate.of(1900, 1, 1)) || date.isAfter(LocalDate.now())) {
                        fail(400, "Ngày sinh phải từ năm 1900 đến hiện tại.");
                    }
                }
                default -> throw new IllegalStateException("Unexpected profile field.");
            }
            result.put(field, value);
        }
        if (required && result.size() != 4) fail(400, "Vui lòng nhập đầy đủ họ tên, địa chỉ, số CCCD và ngày sinh.");
        return result;
    }
}
