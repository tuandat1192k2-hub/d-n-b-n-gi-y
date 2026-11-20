package vn.swoosh.store.members.minhhieu.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import vn.swoosh.store.ApiException;
import vn.swoosh.store.StoreApplication;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@RestController
@ConditionalOnProperty(name = "swoosh.static.member-controller.enabled", havingValue = "true")
public class StoreStaticController {
    private final StoreApplication.StoreProperties properties;

    public StoreStaticController(StoreApplication.StoreProperties properties) {
        this.properties = properties;
    }

    @GetMapping({"/", "/index.html", "/nike-store.html"})
    ResponseEntity<Resource> homepage() {
        Path root = properties.root().toAbsolutePath().normalize();
        Path page = root.resolve("index.html");
        if (!Files.isRegularFile(page)) page = root.resolve("nike-store.html");
        if (!Files.isRegularFile(page)) throw new ApiException(404, "Không tìm thấy trang chủ.");
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML)
            .cacheControl(CacheControl.noCache()).body(new FileSystemResource(page));
    }

    @GetMapping("/robots.txt")
    ResponseEntity<String> robots() {
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN)
            .body("User-agent: *\nAllow: /\nDisallow: /api/\nDisallow: /data/\nSitemap: /sitemap.xml\n");
    }

    @GetMapping("/sitemap.xml")
    ResponseEntity<String> sitemap() {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML)
            .body("""
                <?xml version="1.0" encoding="UTF-8"?>
                <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                  <url><loc>/</loc><changefreq>daily</changefreq><priority>1.0</priority></url>
                  <url><loc>/#collection</loc><changefreq>weekly</changefreq><priority>0.8</priority></url>
                  <url><loc>/#about</loc><changefreq>monthly</changefreq><priority>0.5</priority></url>
                  <url><loc>/#policies</loc><changefreq>monthly</changefreq><priority>0.3</priority></url>
                </urlset>
                """);
    }

    @GetMapping("/images/**")
    ResponseEntity<Resource> image(HttpServletRequest request) {
        Path root = properties.root().toAbsolutePath().normalize();
        String path = request.getRequestURI().substring("/images/".length());
        Path image = root.resolve("images").resolve(path).normalize();
        Path imageRoot = root.resolve("images");
        String filename = image.getFileName() == null ? "" : image.getFileName().toString();
        String lower = filename.toLowerCase();
        if (!image.startsWith(imageRoot) || filename.startsWith(".")
                || !Set.of(".png", ".jpg", ".jpeg", ".webp", ".svg", ".ico").contains(extension(lower))
                || !Files.isRegularFile(image)) {
            throw new ApiException(HttpStatus.NOT_FOUND.value(), "Không tìm thấy tệp tin.");
        }
        MediaType type = lower.endsWith(".svg") ? MediaType.valueOf("image/svg+xml")
            : lower.endsWith(".png") ? MediaType.IMAGE_PNG
            : lower.endsWith(".webp") ? MediaType.valueOf("image/webp")
            : lower.endsWith(".ico") ? MediaType.valueOf("image/x-icon") : MediaType.IMAGE_JPEG;
        return ResponseEntity.ok().contentType(type)
            .cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(7)))
            .body(new FileSystemResource(image));
    }

    private static String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot);
    }
}
