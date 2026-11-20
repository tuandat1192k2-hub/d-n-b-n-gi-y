package vn.swoosh.store;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, String>> handleApiError(ApiException error) {
        return ResponseEntity.status(error.status()).body(Map.of("error", error.getMessage()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<Map<String, String>> handleMissingResource(NoResourceFoundException error) {
        return ResponseEntity.status(404).body(Map.of("error", "Không tìm thấy tệp tin."));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, String>> handleUnexpected(Exception error) {
        error.printStackTrace();
        return ResponseEntity.internalServerError()
            .body(Map.of("error", "Máy chủ gặp lỗi. Vui lòng thử lại."));
    }
}
