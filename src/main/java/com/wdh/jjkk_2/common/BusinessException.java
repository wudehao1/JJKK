package com.wdh.jjkk_2.common;

import org.springframework.http.HttpStatus;

/**
 * 可预期的业务异常。
 *
 * 当错误属于“用户可以理解并处理”的情况时使用它，例如未登录、无权限、
 * 基金不存在、重复添加自选等。抛出该异常后，全局异常处理器会按照这里保存的
 * HTTP 状态码和业务文案返回，不会把这类正常业务失败包装成笼统的 500。
 */
public class BusinessException extends RuntimeException {
    private final HttpStatus status;

    public BusinessException(String message) {
        this(HttpStatus.BAD_REQUEST, message);
    }

    public BusinessException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}

