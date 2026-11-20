package vn.swoosh.store;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;

@Component
public class SecurityHeadersFilter implements Filter {
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        String path = httpRequest.getRequestURI();
        httpResponse.setHeader("X-Content-Type-Options", "nosniff");
        httpResponse.setHeader("Referrer-Policy", "same-origin");
        httpResponse.setHeader("X-Frame-Options", "DENY");
        httpResponse.setHeader("Content-Security-Policy",
            "default-src 'self'; img-src 'self' data: https:; style-src 'self' 'unsafe-inline'; "
                + "script-src 'self' 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'; "
                + "base-uri 'self'; form-action 'self'");
        if (path.startsWith("/api/")) {
            httpResponse.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
        } else if (path.startsWith("/images/")) {
            httpResponse.setHeader("Cache-Control", "public, max-age=604800, stale-while-revalidate=86400");
        } else if (path.equals("/robots.txt") || path.equals("/sitemap.xml") || path.equals("/favicon.ico")) {
            httpResponse.setHeader("Cache-Control", "public, max-age=86400");
        } else {
            httpResponse.setHeader("Cache-Control", "no-cache");
        }
        chain.doFilter(request, response);
    }
}
