package vn.swoosh.store;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    private final HttpStatus status;

    public ApiException(int status, String message) {
        super(message);
        this.status = HttpStatus.valueOf(status);
    }

    public HttpStatus status() {
        return status;
    }
}
