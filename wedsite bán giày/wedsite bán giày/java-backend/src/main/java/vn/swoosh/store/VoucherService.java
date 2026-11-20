package vn.swoosh.store;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class VoucherService {
    private final JdbcTemplate db;

    public VoucherService(JdbcTemplate db) {
        this.db = db;
    }

    public Map<String, Object> validate(Map<String, Object> data, String pathCode) {
        String code = (pathCode == null ? StoreSupport.text(data.get("code")) : pathCode).trim();
        String type = StoreSupport.text(data.get("discountType"));
        int value = StoreSupport.integer(data.get("discountValue"), "Giá trị giảm giá phải là số nguyên dương.");
        int minTotal = StoreSupport.integer(data.getOrDefault("minTotal", 0), "Giá trị đơn hàng tối thiểu không hợp lệ.");
        Object rawLimit = data.get("usageLimit");
        Integer limit = rawLimit == null || StoreSupport.text(rawLimit).isBlank()
            ? null : StoreSupport.integer(rawLimit, "Giới hạn lượt sử dụng phải là số nguyên dương.");
        Object active = data.getOrDefault("active", Boolean.TRUE);
        if (!Pattern.matches("[A-Za-z0-9_-]{3,30}", code)) StoreSupport.fail(400, "Mã voucher phải gồm 3–30 ký tự chữ, số, gạch ngang hoặc gạch dưới.");
        if (!Set.of("percent", "fixed").contains(type)) StoreSupport.fail(400, "Loại giảm giá không hợp lệ.");
        if (value < 1 || ("percent".equals(type) && value > 100)) StoreSupport.fail(400, "Giá trị giảm giá không hợp lệ.");
        if (minTotal < 0 || (limit != null && limit < 1) || !(active instanceof Boolean)) {
            StoreSupport.fail(400, "Thông tin voucher không hợp lệ.");
        }
        String start = normalizeDateTime(StoreSupport.text(data.get("startsAt")));
        String end = normalizeDateTime(StoreSupport.text(data.get("expiresAt")));
        if (!start.isEmpty() && !end.isEmpty() && !Instant.parse(start).isBefore(Instant.parse(end))) {
            StoreSupport.fail(400, "Ngày hết hạn phải sau ngày bắt đầu.");
        }
        Map<String, Object> voucher = new LinkedHashMap<>();
        voucher.put("code", code.toUpperCase());
        voucher.put("discount_type", type);
        voucher.put("discount_value", value);
        voucher.put("min_total", minTotal);
        voucher.put("starts_at", start.isEmpty() ? null : java.sql.Timestamp.from(Instant.parse(start)));
        voucher.put("expires_at", end.isEmpty() ? null : java.sql.Timestamp.from(Instant.parse(end)));
        voucher.put("usage_limit", limit);
        voucher.put("active", Boolean.TRUE.equals(active) ? 1 : 0);
        return voucher;
    }

    public Map<String, Object> find(String code, long subtotal) {
        if (code == null || code.isBlank() || !Pattern.matches("[A-Za-z0-9_-]{3,30}", code.trim())) {
            StoreSupport.fail(400, "Mã voucher không hợp lệ.");
        }
        List<Map<String, Object>> rows = db.queryForList("SELECT * FROM coupons WHERE code=?", code.trim());
        if (rows.isEmpty()) StoreSupport.fail(400, "Voucher không tồn tại, đã hết hạn hoặc đã hết lượt sử dụng.");
        Map<String, Object> voucher = rows.get(0);
        Instant now = Instant.now();
        Instant startsAt = voucher.get("start_date") == null ? null
            : ((java.sql.Timestamp) voucher.get("start_date")).toInstant();
        Instant expiresAt = voucher.get("end_date") == null ? null
            : ((java.sql.Timestamp) voucher.get("end_date")).toInstant();
        if (StoreSupport.number(voucher.get("is_active")) == 0
                || (startsAt != null && startsAt.isAfter(now))
                || (expiresAt != null && expiresAt.isBefore(now))
                || (voucher.get("usage_limit") != null
                    && StoreSupport.number(voucher.get("used_count")) >= StoreSupport.number(voucher.get("usage_limit")))) {
            StoreSupport.fail(400, "Voucher không tồn tại, đã hết hạn hoặc đã hết lượt sử dụng.");
        }
        if (subtotal < StoreSupport.number(voucher.get("min_order_value"))) {
            StoreSupport.fail(400, "Đơn hàng chưa đạt giá trị tối thiểu để dùng mã này.");
        }
        return voucher;
    }

    public long discount(Map<String, Object> voucher, long subtotal) {
        long amount = "PERCENT".equalsIgnoreCase(StoreSupport.text(voucher.get("discount_type")))
            ? subtotal * StoreSupport.number(voucher.get("discount_value")) / 100
            : StoreSupport.number(voucher.get("discount_value"));
        return Math.min(subtotal, amount);
    }

    private static String normalizeDateTime(String value) {
        if (value == null || value.isBlank()) return "";
        try {
            return OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.UTC).toString();
        } catch (RuntimeException ignored) {
            try {
                return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC).toString();
            } catch (RuntimeException error) {
                StoreSupport.fail(400, "Thời hạn voucher không hợp lệ.");
                return "";
            }
        }
    }
}
