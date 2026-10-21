package com.company.oms.common;

public class ApiResponse<T> {
    public boolean success;
    public String message;
    public T data;

    public ApiResponse() {}

    public ApiResponse(boolean success, String message, T data) {
        this.success = success;
        this.message = message;
        this.data = data;
    }

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, "ok", data);
    }

    public static <T> ApiResponse<T> ok() {
        return new ApiResponse<>(true, "ok", null);
    }

    public static <T> ApiResponse<T> fail(String message) {
        return new ApiResponse<>(false, message, null);
    }
}
