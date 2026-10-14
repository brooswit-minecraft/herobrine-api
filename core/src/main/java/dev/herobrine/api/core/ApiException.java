package dev.herobrine.api.core;

public class ApiException extends RuntimeException {
    public final int status;
    public final String code;

    public ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static ApiException badRequest(String msg) { return new ApiException(400, "BAD_REQUEST", msg); }
    public static ApiException notFound(String msg) { return new ApiException(404, "NOT_FOUND", msg); }
    /** The action/resource exists but cannot be served in the current game state. */
    public static ApiException unavailable(String msg) { return new ApiException(409, "ACTION_UNAVAILABLE", msg); }
    public static ApiException forbidden(String msg) { return new ApiException(403, "PERMISSION_DENIED", msg); }
}
