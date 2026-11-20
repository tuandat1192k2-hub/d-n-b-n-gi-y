package vn.swoosh.store.members.theanh.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import vn.swoosh.store.SecurityService;
import vn.swoosh.store.StoreApplication;
import vn.swoosh.store.StoreSupport;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/media")
public class MediaController {
    private static final Pattern DATA_URL =
        Pattern.compile("^data:image/(png|jpeg|webp);base64,([A-Za-z0-9+/=]+)$");
    private static final Pattern STORED_FILENAME = Pattern.compile("[a-f0-9]{24}\\.(png|jpg|webp)");
    private final JdbcTemplate db;
    private final SecurityService security;
    private final StoreApplication.StoreProperties properties;

    public MediaController(JdbcTemplate db, SecurityService security,
                           StoreApplication.StoreProperties properties) {
        this.db = db;
        this.security = security;
        this.properties = properties;
    }

    @GetMapping
    Map<String, Object> list(HttpServletRequest request) {
        security.requireRole(request, "admin");
        return Map.of("media", db.queryForList("SELECT * FROM media_files ORDER BY media_id DESC")
            .stream().map(row -> {
                Map<String, Object> media = new java.util.HashMap<>(row);
                media.put("url", "/images/products/" + row.get("filename"));
                return media;
            }).toList());
    }

    @PostMapping
    ResponseEntity<Map<String, Object>> upload(@RequestBody Map<String, Object> data,
                                               HttpServletRequest request) {
        Map<String, Object> admin = security.requireRole(request, "admin");
        security.requireCsrf(admin, request);
        String dataUrl = StoreSupport.text(data.get("dataUrl"));
        String altText = StoreSupport.text(data.get("altText"));
        if (altText.length() > 150) StoreSupport.fail(400, "Thông tin ảnh không hợp lệ.");
        Matcher match = DATA_URL.matcher(dataUrl);
        if (!match.matches()) StoreSupport.fail(400, "Chỉ hỗ trợ ảnh PNG, JPEG hoặc WebP.");
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(match.group(2));
        } catch (IllegalArgumentException error) {
            StoreSupport.fail(400, "Dữ liệu ảnh không hợp lệ.");
            return ResponseEntity.badRequest().build();
        }
        String format = match.group(1);
        if (bytes.length == 0 || bytes.length > 700 * 1024 || !validImage(bytes, format)) {
            StoreSupport.fail(400, "Ảnh vượt quá 700 KB hoặc định dạng tệp không khớp.");
        }
        String extension = format.equals("jpeg") ? ".jpg" : "." + format;
        String filename = randomHex(12) + extension;
        Path directory = properties.root().toAbsolutePath().normalize().resolve("images/products");
        Path target = directory.resolve(filename);
        try {
            Files.createDirectories(directory);
            Files.write(target, bytes);
            db.update("INSERT INTO media_files(filename,alt_text,created_at) VALUES(?,?,?)",
                filename, altText.trim(), java.sql.Timestamp.from(Instant.now()));
        } catch (IOException error) {
            throw new IllegalStateException("Không thể lưu ảnh sản phẩm.", error);
        } catch (RuntimeException error) {
            try {
                Files.deleteIfExists(target);
            } catch (IOException cleanupError) {
                error.addSuppressed(cleanupError);
            }
            throw error;
        }
        security.recordActivity(StoreSupport.text(admin.get("username")), "Tải ảnh sản phẩm", "media", filename, request);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(Map.of("filename", filename, "url", "/images/products/" + filename));
    }

    @DeleteMapping("/{filename}")
    Map<String, Object> delete(@PathVariable String filename, HttpServletRequest request) {
        Map<String, Object> admin = security.requireRole(request, "admin");
        security.requireCsrf(admin, request);
        if (!STORED_FILENAME.matcher(filename).matches()) StoreSupport.fail(400, "Tệp media không hợp lệ.");
        if (db.update("DELETE FROM media_files WHERE filename=?", filename) == 0) {
            StoreSupport.fail(404, "Không tìm thấy ảnh.");
        }
        Path image = properties.root().toAbsolutePath().normalize().resolve("images/products").resolve(filename);
        try {
            Files.deleteIfExists(image);
        } catch (IOException error) {
            throw new IllegalStateException("Không thể xóa ảnh sản phẩm.", error);
        }
        security.recordActivity(StoreSupport.text(admin.get("username")), "Xóa ảnh sản phẩm", "media", filename, request);
        return Map.of("ok", true);
    }

    private static boolean validImage(byte[] bytes, String format) {
        return switch (format) {
            case "png" -> bytes.length >= 45
                && startsWith(bytes, new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a})
                && new String(bytes, 12, 4, StandardCharsets.US_ASCII).equals("IHDR")
                && endsWith(bytes, new byte[]{0x49, 0x45, 0x4e, 0x44, (byte) 0xae, 0x42, 0x60, (byte) 0x82});
            case "jpeg" -> bytes.length >= 20 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xd8
                && bytes[2] == (byte) 0xff && bytes[bytes.length - 2] == (byte) 0xff
                && bytes[bytes.length - 1] == (byte) 0xd9;
            case "webp" -> bytes.length >= 30
                && new String(bytes, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
                && new String(bytes, 8, 4, StandardCharsets.US_ASCII).equals("WEBP")
                && Integer.toUnsignedLong(ByteBuffer.wrap(bytes, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt())
                    == bytes.length - 8L;
            default -> false;
        };
    }

    private static String randomHex(int count) {
        byte[] bytes = new byte[count];
        new java.security.SecureRandom().nextBytes(bytes);
        return java.util.HexFormat.of().formatHex(bytes);
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (value[i] != prefix[i]) return false;
        return true;
    }

    private static boolean endsWith(byte[] value, byte[] suffix) {
        if (value.length < suffix.length) return false;
        int offset = value.length - suffix.length;
        for (int i = 0; i < suffix.length; i++) if (value[offset + i] != suffix[i]) return false;
        return true;
    }
}
