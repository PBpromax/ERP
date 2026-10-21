package com.company.oms.common;

import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler({org.springframework.dao.OptimisticLockingFailureException.class,
            org.springframework.dao.PessimisticLockingFailureException.class})
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Void> concurrentUpdate(Exception ex) {
        return ApiResponse.fail("数据已被其他人修改，请刷新后重试");
    }

    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> business(BusinessException ex) {
        return ApiResponse.fail(ex.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> validation(Exception ex) {
        java.util.stream.Stream<String> messages;
        if (ex instanceof MethodArgumentNotValidException invalid) {
            messages = invalid.getBindingResult().getAllErrors().stream()
                    .map(org.springframework.context.support.DefaultMessageSourceResolvable::getDefaultMessage);
        } else if (ex instanceof ConstraintViolationException invalid) {
            messages = invalid.getConstraintViolations().stream().map(jakarta.validation.ConstraintViolation::getMessage);
        } else {
            messages = java.util.stream.Stream.empty();
        }
        String message = messages.filter(text -> text != null && !text.isBlank()).distinct()
                .collect(java.util.stream.Collectors.joining("；"));
        return ApiResponse.fail(message.isBlank() ? "请检查填写内容" : message);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> dataIntegrity(DataIntegrityViolationException ex) {
        return ApiResponse.fail("数据已存在或不符合保存要求，请检查编号是否重复");
    }

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiResponse<Void> denied(AccessDeniedException ex) {
        return ApiResponse.fail("无权限访问该资源");
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> unknown(Exception ex) {
        return ApiResponse.fail("系统异常：" + ex.getMessage());
    }
}
