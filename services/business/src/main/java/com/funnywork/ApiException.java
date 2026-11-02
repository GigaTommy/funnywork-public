package com.funnywork;

public class ApiException extends RuntimeException {
    public final int status;
    public final String code;
    public ApiException(int status, String code, String message) {
        super(message); this.status = status; this.code = code;
    }
    public static ApiException forbidden() { return new ApiException(403,"POLICY_DENIED","Operation is outside the authorized scope"); }
    public static ApiException missing() { return new ApiException(404,"NOT_FOUND","Resource not found in authorized scope"); }
    public static ApiException conflict(String code) { return new ApiException(409,code,"Operation conflicts with current state"); }
}
