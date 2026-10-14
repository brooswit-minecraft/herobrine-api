package dev.herobrine.api.core;

public class ApiException extends RuntimeException {
    public final int status;
    public final String code;

    public ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static ApiException badRequest(String msg) { return new ApiException(400, "bad_request", msg); }
    public static ApiException notFound(String msg) { return new ApiException(404, "not_found", msg); }
    public static ApiException unavailable(String msg) { return new ApiException(409, "unavailable", msg); }
}
