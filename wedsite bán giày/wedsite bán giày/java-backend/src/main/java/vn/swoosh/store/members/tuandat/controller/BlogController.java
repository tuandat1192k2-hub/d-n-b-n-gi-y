package vn.swoosh.store.members.tuandat.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.swoosh.store.ApiException;
import vn.swoosh.store.SecurityService;
import vn.swoosh.store.StoreSupport;

@RestController
@RequestMapping("/api")
public class BlogController {
    private static final String ARTICLE_FIELDS = """
        articles.article_id,articles.title,articles.slug,articles.category,articles.content,
        articles.thumbnail_url,articles.status,articles.created_at,
        COALESCE(NULLIF(users.full_name,N''),users.username,N'Swoosh Store') AS author_name
        """;
    private final JdbcTemplate db;
    private final SecurityService security;

    public BlogController(JdbcTemplate db, SecurityService security) {
        this.db = db;
        this.security = security;
    }

    @GetMapping("/blog")
    Map<String, Object> listPublished() {
        return Map.of("articles", db.queryForList("""
            SELECT TOP (100) articles.article_id,articles.title,articles.slug,articles.category,
                   LEFT(articles.content,220) AS content_preview,articles.thumbnail_url,articles.created_at,
                   COALESCE(NULLIF(users.full_name,N''),users.username,N'Swoosh Store') AS author_name
            FROM dbo.running_articles articles
            LEFT JOIN dbo.users users ON users.user_id=articles.author_id
            WHERE articles.status='PUBLISHED'
            ORDER BY articles.created_at DESC,articles.article_id DESC
            """));
    }

    @GetMapping("/blog/{slug}")
    Map<String, Object> publishedArticle(@PathVariable String slug) {
        List<Map<String, Object>> articles = db.queryForList("SELECT " + ARTICLE_FIELDS + """
            FROM dbo.running_articles articles
            LEFT JOIN dbo.users users ON users.user_id=articles.author_id
            WHERE articles.slug=? AND articles.status='PUBLISHED'
            """, slug);
        if (articles.isEmpty()) StoreSupport.fail(404, "Không tìm thấy bài blog.");
        return Map.of("article", articles.get(0));
    }

    @GetMapping("/admin/blog")
    Map<String, Object> listAll(HttpServletRequest request) {
        security.requireRole(request, "admin");
        return Map.of("articles", db.queryForList("SELECT " + ARTICLE_FIELDS + """
            FROM dbo.running_articles articles
            LEFT JOIN dbo.users users ON users.user_id=articles.author_id
            ORDER BY articles.created_at DESC,articles.article_id DESC
            """));
    }

    @PostMapping("/admin/blog")
    @Transactional
    ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> data,
                                               HttpServletRequest request) {
        Map<String, Object> admin = security.requireRole(request, "admin");
        security.requireCsrf(admin, request);
        ArticleInput article = validate(data);
        String slug = createSlug(article.title());
        try {
            Long id = db.queryForObject("""
                INSERT INTO dbo.running_articles(title,slug,category,content,thumbnail_url,author_id,status)
                OUTPUT INSERTED.article_id VALUES(?,?,?,?,?,?,?)
                """, Long.class, article.title(), slug, article.category(), article.content(),
                article.thumbnailUrl(), admin.get("user_id"), article.status());
            security.recordActivity(StoreSupport.text(admin.get("username")), "Đăng bài blog", "blog", id, request);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("articleId", id, "slug", slug));
        } catch (DataIntegrityViolationException error) {
            throw new ApiException(409, "Không thể tạo bài blog với đường dẫn này.");
        }
    }

    @PatchMapping("/admin/blog/{id}")
    @Transactional
    Map<String, Object> update(@PathVariable long id, @RequestBody Map<String, Object> data,
                               HttpServletRequest request) {
        Map<String, Object> admin = security.requireRole(request, "admin");
        security.requireCsrf(admin, request);
        ArticleInput article = validate(data);
        if (db.update("""
            UPDATE dbo.running_articles
            SET title=?,category=?,content=?,thumbnail_url=?,status=?
            WHERE article_id=?
            """, article.title(), article.category(), article.content(), article.thumbnailUrl(),
            article.status(), id) == 0) {
            StoreSupport.fail(404, "Không tìm thấy bài blog.");
        }
        security.recordActivity(StoreSupport.text(admin.get("username")), "Cập nhật bài blog", "blog", id, request);
        return Map.of("ok", true);
    }

    @DeleteMapping("/admin/blog/{id}")
    @Transactional
    Map<String, Object> delete(@PathVariable long id, HttpServletRequest request) {
        Map<String, Object> admin = security.requireRole(request, "admin");
        security.requireCsrf(admin, request);
        if (db.update("DELETE FROM dbo.running_articles WHERE article_id=?", id) == 0) {
            StoreSupport.fail(404, "Không tìm thấy bài blog.");
        }
        security.recordActivity(StoreSupport.text(admin.get("username")), "Xóa bài blog", "blog", id, request);
        return Map.of("ok", true);
    }

    private ArticleInput validate(Map<String, Object> data) {
        String title = StoreSupport.text(data.get("title")).trim();
        String category = StoreSupport.text(data.get("category")).trim();
        String content = StoreSupport.text(data.get("content")).trim();
        String thumbnailUrl = StoreSupport.text(data.get("thumbnailUrl")).trim();
        String status = StoreSupport.text(data.getOrDefault("status", "PUBLISHED"))
            .trim().toUpperCase(Locale.ROOT);
        if (title.length() < 3 || title.length() > 255) {
            StoreSupport.fail(400, "Tiêu đề bài blog phải có từ 3 đến 255 ký tự.");
        }
        if (category.isEmpty() || category.length() > 100) {
            StoreSupport.fail(400, "Danh mục bài blog không hợp lệ.");
        }
        if (content.isEmpty() || content.length() > 100_000) {
            StoreSupport.fail(400, "Nội dung bài blog không được để trống và tối đa 100.000 ký tự.");
        }
        if (!thumbnailUrl.isEmpty() && !isAllowedThumbnail(thumbnailUrl)) {
            StoreSupport.fail(400, "Ảnh đại diện chỉ được dùng đường dẫn /images/ hoặc URL HTTPS.");
        }
        if (!List.of("DRAFT", "PUBLISHED").contains(status)) {
            StoreSupport.fail(400, "Trạng thái bài blog không hợp lệ.");
        }
        return new ArticleInput(title, category, content, thumbnailUrl.isEmpty() ? null : thumbnailUrl, status);
    }

    private String createSlug(String title) {
        String base = Normalizer.normalize(title, Normalizer.Form.NFD)
            .replace("đ", "d").replace("Đ", "D")
            .replaceAll("\\p{M}+", "")
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-|-$", "");
        if (base.isEmpty()) base = "bai-viet";
        if (base.length() > 230) base = base.substring(0, 230).replaceAll("-+$", "");
        String slug = base;
        for (int suffix = 2; !db.queryForList(
                "SELECT 1 FROM dbo.running_articles WHERE slug=?", slug).isEmpty(); suffix++) {
            slug = base + "-" + suffix;
        }
        return slug;
    }

    private static boolean isAllowedThumbnail(String value) {
        if (value.startsWith("/images/")) return true;
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null;
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    private record ArticleInput(String title, String category, String content, String thumbnailUrl, String status) {
    }
}
