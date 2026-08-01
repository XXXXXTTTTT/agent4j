package com.agent.web.controller;

import com.agent.core.engine.CheckpointConflictException;
import com.agent.core.engine.GraphNotFoundException;
import com.agent.core.engine.RunNotFoundException;
import org.springframework.core.codec.DecodingException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;

import java.net.URI;

/** 将 Run API 异常映射为稳定的 ProblemDetail。 */
@RestControllerAdvice
public final class RunExceptionHandler {

    /** 映射请求解码、类型转换与 Bean Validation 错误。 */
    @ExceptionHandler({
            ServerWebInputException.class,
            WebExchangeBindException.class,
            DecodingException.class
    })
    public ProblemDetail badRequest(Exception exception, ServerWebExchange exchange) {
        return problem(HttpStatus.BAD_REQUEST, detail(exception), exchange);
    }

    /** 映射未注册图和不存在 Run。 */
    @ExceptionHandler({GraphNotFoundException.class, RunNotFoundException.class})
    public ProblemDetail notFound(RuntimeException exception, ServerWebExchange exchange) {
        return problem(HttpStatus.NOT_FOUND, exception.getMessage(), exchange);
    }

    /** 映射 Checkpoint 版本或状态冲突。 */
    @ExceptionHandler(CheckpointConflictException.class)
    public ProblemDetail conflict(
            CheckpointConflictException exception,
            ServerWebExchange exchange) {
        return problem(HttpStatus.CONFLICT, exception.getMessage(), exchange);
    }

    /** 隐藏未处理基础设施异常的内部细节。 */
    @ExceptionHandler(Exception.class)
    public ProblemDetail internalServerError(Exception exception, ServerWebExchange exchange) {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "内部服务器错误", exchange);
    }

    private static ProblemDetail problem(
            HttpStatus status,
            String detail,
            ServerWebExchange exchange) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setInstance(URI.create(exchange.getRequest().getPath().value()));
        return problem;
    }

    private static String detail(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "请求格式错误" : message;
    }
}
