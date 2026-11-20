package vn.swoosh.store;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import jakarta.servlet.http.HttpServletRequest;

@RestController
class StoreStaticController {
    private final StoreApplication.StoreProperties properties;

    StoreStaticController(StoreApplication.StoreProperties properties) {
        this.properties = properties;
    }

    @GetMapping({"/", "/index.html", "/nike-store.html"})
    ResponseEntity<Resource> homepage() {
        Path root = properties.root().toAbsolutePath().normalize();
        Path page = root.resolve("index.html");
        if (!Files.isRegularFile(page)) page = root.resolve("nike-store.html");
        if (!Files.isRegularFile(page)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy trang chủ.");
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
            .body("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\"><url><loc>/</loc></url></urlset>");
    }

    @GetMapping("/images/**")
    ResponseEntity<Resource> image(HttpServletRequest request) {
        Path root = properties.root().toAbsolutePath().normalize();
        String path = request.getRequestURI().substring("/images/".length());
        Path image = root.resolve("images").resolve(path).normalize();
        Path imageRoot = root.resolve("images");
        String lower = image.getFileName() == null ? "" : image.getFileName().toString().toLowerCase();
        if (!image.startsWith(imageRoot) || lower.startsWith(".")
                || !Set.of(".png", ".jpg", ".jpeg", ".webp", ".svg", ".ico").contains(extension(lower))
                || !Files.isRegularFile(image)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy tệp tin.");
        }
        MediaType type = lower.endsWith(".svg") ? MediaType.valueOf("image/svg+xml")
            : lower.endsWith(".png") ? MediaType.IMAGE_PNG
            : lower.endsWith(".webp") ? MediaType.valueOf("image/webp")
            : lower.endsWith(".ico") ? MediaType.valueOf("image/x-icon") : MediaType.IMAGE_JPEG;
        return ResponseEntity.ok().contentType(type).cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(7)))
            .body(new FileSystemResource(image));
    }

    private static String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot);
    }
}
