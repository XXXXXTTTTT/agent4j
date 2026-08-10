package com.agent.web.audit;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** 对审计正文中的配置凭据和常见令牌格式执行脱敏。 */
public final class AuditTextRedactor {

    private static final String REDACTED = "[REDACTED]";
    private static final String SENSITIVE_KEY =
            "[A-Za-z0-9_-]*(?:api[_-]?key|authorization|password|secret|token)";
    private static final Pattern BEARER = Pattern.compile(
            "(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern OPEN_AI_KEY = Pattern.compile(
            "(?<![A-Za-z0-9])sk-[A-Za-z0-9_-]{4,}");
    private static final Pattern QUOTED_SENSITIVE_ASSIGNMENT = Pattern.compile(
            "(?i)((?<![A-Za-z0-9])" + SENSITIVE_KEY
                    + "[\\\"']?\\s*[:=]\\s*[\\\"'])(.*?)([\\\"'])");
    private static final Pattern SENSITIVE_ASSIGNMENT = Pattern.compile(
            "(?i)((?<![A-Za-z0-9])" + SENSITIVE_KEY
                    + "[\\\"']?\\s*[:=]\\s*)([^\\s,;，。}\\\"']+)");

    private final List<String> configuredSecrets;

    public AuditTextRedactor(Collection<String> configuredSecrets) {
        this.configuredSecrets = Objects.requireNonNull(
                        configuredSecrets, "configuredSecrets 不能为空").stream()
                .filter(Objects::nonNull)
                .filter(value -> !value.isBlank())
                .distinct()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
    }

    /** 返回不包含已知凭据的审计文本。 */
    public String redact(String value) {
        if (value == null) {
            return null;
        }
        String redacted = value;
        for (String secret : configuredSecrets) {
            redacted = redacted.replace(secret, REDACTED);
        }
        redacted = BEARER.matcher(redacted).replaceAll("Bearer " + REDACTED);
        redacted = OPEN_AI_KEY.matcher(redacted).replaceAll(REDACTED);
        redacted = QUOTED_SENSITIVE_ASSIGNMENT.matcher(redacted)
                .replaceAll("$1" + REDACTED + "$3");
        return SENSITIVE_ASSIGNMENT.matcher(redacted)
                .replaceAll("$1" + REDACTED);
    }
}
