package com.agent.web.controller;

import com.agent.core.engine.CheckpointConflictException;
import com.agent.core.engine.GraphNotFoundException;
import com.agent.core.engine.RunNotFoundException;
import com.agent.core.profile.AgentProfileNotFoundException;
import com.agent.web.persistence.JdbcConversationRepository;
import com.agent.web.persistence.JdbcModelConfigurationRepository;
import com.agent.web.conversation.ConversationService;
import com.agent.web.workspace.WorkspaceAccessService;
import com.agent.web.workspace.WorkspaceImportService;
import com.agent.web.workspace.WorkspaceFileService;
import com.agent.web.mcp.installation.McpInstallationService;
import com.agent.web.mcp.installation.McpInstallationConflictException;
import com.agent.web.mcp.runtime.McpMaterialNotPreparedException;
import com.agent.web.mcp.runtime.McpMaterialPreparationImageNotConfiguredException;
import com.agent.web.mcp.runtime.McpMaterialPreparationTimeoutException;
import com.agent.web.skill.GitHubSkillInstallationService;
import com.agent.web.skill.SkillInstallationConflictException;
import com.agent.web.audit.AuditTextRedactor;
import org.springframework.core.codec.DecodingException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;

import java.net.URI;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

/** 将 Run API 异常映射为稳定的 ProblemDetail。 */
@RestControllerAdvice
public final class RunExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(RunExceptionHandler.class);
    private final AuditTextRedactor redactor;

    /** 使用运行配置中的精确敏感值创建异常处理器，测试切片使用空配置脱敏器。 */
    @Autowired
    public RunExceptionHandler(ObjectProvider<AuditTextRedactor> redactorProvider) {
        this(redactorProvider.getIfAvailable(() -> new AuditTextRedactor(List.of())));
    }

    /** 供直接单元测试注入脱敏器。 */
    public RunExceptionHandler(AuditTextRedactor redactor) {
        this.redactor = java.util.Objects.requireNonNull(redactor, "redactor 不能为空");
    }

    /** 映射请求解码、类型转换与 Bean Validation 错误。 */
    @ExceptionHandler({
            ServerWebInputException.class,
            WebExchangeBindException.class,
            DecodingException.class,
            IllegalArgumentException.class
    })
    public ResponseEntity<ProblemDetail> badRequest(
            Exception exception,
            ServerWebExchange exchange) {
        return problem(HttpStatus.BAD_REQUEST, detail(exception), exchange);
    }

    @ExceptionHandler({
            McpInstallationService.InvalidConfirmationException.class,
            GitHubSkillInstallationService.InvalidConfirmationException.class
    })
    public ResponseEntity<ProblemDetail> invalidCapabilityConfirmation(
            RuntimeException exception, ServerWebExchange exchange) {
        return problem(HttpStatus.BAD_REQUEST, exception.getMessage(), exchange);
    }

    @ExceptionHandler({
            McpInstallationService.InstallationNotFoundException.class,
            GitHubSkillInstallationService.InstallationNotFoundException.class
    })
    public ResponseEntity<ProblemDetail> capabilityNotFound(
            RuntimeException exception, ServerWebExchange exchange) {
        return problem(HttpStatus.NOT_FOUND, exception.getMessage(), exchange);
    }

    /** 映射 ZIP 格式、条目路径和压缩内容错误。 */
    @ExceptionHandler(WorkspaceImportService.ImportFormatException.class)
    public ResponseEntity<ProblemDetail> invalidWorkspaceImport(
            RuntimeException exception,
            ServerWebExchange exchange) {
        return problem(HttpStatus.BAD_REQUEST, exception.getMessage(), exchange);
    }

    /** 映射未注册图和不存在 Run。 */
    @ExceptionHandler({
            GraphNotFoundException.class,
            RunNotFoundException.class,
            AgentProfileNotFoundException.class
    })
    public ResponseEntity<ProblemDetail> notFound(
            RuntimeException exception,
            ServerWebExchange exchange) {
        return problem(HttpStatus.NOT_FOUND, exception.getMessage(), exchange);
    }

    /** 映射工作区或会话不可见。 */
    @ExceptionHandler({
            WorkspaceAccessService.WorkspaceNotFoundException.class,
            JdbcConversationRepository.ConversationNotFoundException.class,
            JdbcConversationRepository.ConversationTurnNotFoundException.class,
            ConversationService.ConversationNotFoundException.class,
            JdbcModelConfigurationRepository.ModelConfigurationNotFoundException.class
    })
    public ResponseEntity<ProblemDetail> resourceNotFound(
            RuntimeException exception,
            ServerWebExchange exchange) {
        return problem(HttpStatus.NOT_FOUND, exception.getMessage(), exchange);
    }

    /** 映射成员权限不足。 */
    @ExceptionHandler(WorkspaceAccessService.WorkspaceAccessDeniedException.class)
    public ResponseEntity<ProblemDetail> accessDenied(
            RuntimeException exception,
            ServerWebExchange exchange) {
        return problem(HttpStatus.FORBIDDEN, exception.getMessage(), exchange);
    }

    /** 映射 Checkpoint 版本或状态冲突。 */
    @ExceptionHandler(CheckpointConflictException.class)
    public ResponseEntity<ProblemDetail> conflict(
            CheckpointConflictException exception,
            ServerWebExchange exchange) {
        return problem(HttpStatus.CONFLICT, exception.getMessage(), exchange);
    }

    /** 映射会话归档和活动轮次冲突。 */
    @ExceptionHandler(JdbcConversationRepository.ConversationConflictException.class)
    public ResponseEntity<ProblemDetail> conversationConflict(
            RuntimeException exception,
            ServerWebExchange exchange) {
        return problem(HttpStatus.CONFLICT, exception.getMessage(), exchange);
    }

    /** 映射文件乐观并发冲突。 */
    @ExceptionHandler(WorkspaceFileService.FileConflictException.class)
    public ResponseEntity<ProblemDetail> workspaceFileConflict(
            WorkspaceFileService.FileConflictException exception,
            ServerWebExchange exchange) {
        return problem(HttpStatus.CONFLICT, exception.getMessage(), exchange);
    }

    /** 映射文件大小与文本格式限制。 */
    @ExceptionHandler({
            WorkspaceFileService.FileTooLargeException.class,
            WorkspaceFileService.BinaryFileException.class
    })
    public ResponseEntity<ProblemDetail> workspaceFileContentInvalid(
            RuntimeException exception,
            ServerWebExchange exchange) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, exception.getMessage(), exchange);
    }

    /** 映射模型 Provider 被引用等配置冲突。 */
    @ExceptionHandler(JdbcModelConfigurationRepository.ModelConfigurationConflictException.class)
    public ResponseEntity<ProblemDetail> modelConfigurationConflict(
            RuntimeException exception,
            ServerWebExchange exchange) {
        return problem(HttpStatus.CONFLICT, exception.getMessage(), exchange);
    }

    /** 映射 Skill 安装的乐观锁与生命周期冲突。 */
    @ExceptionHandler(SkillInstallationConflictException.class)
    public ResponseEntity<ProblemDetail> skillInstallationConflict(
            SkillInstallationConflictException exception,
            ServerWebExchange exchange) {
        return problem(HttpStatus.CONFLICT, exception.getMessage(), exchange);
    }

    /** 映射 MCP 安装的乐观锁与生命周期冲突。 */
    @ExceptionHandler(McpInstallationConflictException.class)
    public ResponseEntity<ProblemDetail> mcpInstallationConflict(
            McpInstallationConflictException exception,
            ServerWebExchange exchange) {
        return problem(HttpStatus.CONFLICT, exception.getMessage(), exchange);
    }

    /** 物料未准备时仅返回稳定错误码，避免暴露目录和源地址。 */
    @ExceptionHandler(McpMaterialNotPreparedException.class)
    public ResponseEntity<ProblemDetail> mcpMaterialNotPrepared(
            McpMaterialNotPreparedException exception,
            ServerWebExchange exchange) {
        return problem(HttpStatus.CONFLICT, "MATERIAL_NOT_PREPARED", exchange);
    }

    /** Python 物料准备镜像未由部署者明确配置时返回稳定错误码。 */
    @ExceptionHandler(McpMaterialPreparationImageNotConfiguredException.class)
    public ResponseEntity<ProblemDetail> mcpMaterialPreparationImageNotConfigured(
            McpMaterialPreparationImageNotConfiguredException exception,
            ServerWebExchange exchange) {
        return problem(HttpStatus.CONFLICT, "MATERIAL_PREPARATION_IMAGE_NOT_CONFIGURED", exchange);
    }

    /** 物料准备超时不会回显容器或包管理器细节。 */
    @ExceptionHandler(McpMaterialPreparationTimeoutException.class)
    public ResponseEntity<ProblemDetail> mcpMaterialPreparationTimeout(
            McpMaterialPreparationTimeoutException exception,
            ServerWebExchange exchange) {
        return problem(HttpStatus.CONFLICT, "MATERIAL_PREPARATION_TIMEOUT", exchange);
    }

    /** 将数据库唯一约束冲突转换为稳定的客户端错误。 */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ProblemDetail> duplicateConfiguration(
            DuplicateKeyException exception,
            ServerWebExchange exchange) {
        return problem(HttpStatus.CONFLICT, "模型配置与已有记录冲突", exchange);
    }

    /** 映射工作区导入的体积与文件数量上限。 */
    @ExceptionHandler(WorkspaceImportService.ImportLimitExceededException.class)
    public ResponseEntity<ProblemDetail> payloadTooLarge(
            RuntimeException exception,
            ServerWebExchange exchange) {
        return problem(HttpStatus.PAYLOAD_TOO_LARGE, exception.getMessage(), exchange);
    }

    /** 映射工作区导入目标冲突。 */
    @ExceptionHandler(WorkspaceImportService.ImportConflictException.class)
    public ResponseEntity<ProblemDetail> workspaceImportConflict(
            RuntimeException exception,
            ServerWebExchange exchange) {
        return problem(HttpStatus.CONFLICT, exception.getMessage(), exchange);
    }

    /** 隐藏未处理基础设施异常的内部细节。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> internalServerError(
            Exception exception,
            ServerWebExchange exchange) {
        LOGGER.error(
                "Unhandled request failure method={} path={} exceptionType={} stackTrace={}",
                exchange.getRequest().getMethod(),
                exchange.getRequest().getPath().value(),
                exception.getClass().getName(),
                redactor.redact(stackTrace(exception)));
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "内部服务器错误", exchange);
    }

    private static ResponseEntity<ProblemDetail> problem(
            HttpStatus status,
            String detail,
            ServerWebExchange exchange) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setInstance(URI.create(exchange.getRequest().getPath().value()));
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    private static String detail(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "请求格式错误" : message;
    }

    private static String stackTrace(Throwable throwable) {
        StringWriter writer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
