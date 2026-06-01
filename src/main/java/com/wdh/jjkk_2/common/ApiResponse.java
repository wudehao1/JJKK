package com.wdh.jjkk_2.common;

/**
 * 所有 HTTP 接口统一使用的响应外壳。
 *
 * 前端只需要判断 success、读取 message 和 data，就可以处理成功、业务失败
 * 或系统异常等场景。保持统一结构也方便小程序端封装 request 方法，避免每个
 * 页面重复写不同的错误解析逻辑。
 */
public record ApiResponse<T>(
        boolean success,
        String message,
        T data
) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, "OK", data);
    }

    public static <T> ApiResponse<T> ok(String message, T data) {
        return new ApiResponse<>(true, message, data);
    }

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(true, "OK", null);
    }

    public static ApiResponse<Void> error(String message) {
        return new ApiResponse<>(false, message, null);
    }
}

