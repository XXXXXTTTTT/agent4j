package com.agent.core.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** 使用固定规则和固定摘要的确定性 Prompt Injection 检测器。 */
public final class DefaultPromptInjectionDetector implements PromptInjectionDetector {

    @Override
    public PromptSecurityAssessment inspect(PromptSecurityContext context, String text) {
        Objects.requireNonNull(context, "context 不能为空");
        if (text == null || text.isBlank()) {
            return new PromptSecurityAssessment(SecurityDecision.ALLOW, List.of());
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        List<SecurityFinding> findings = new ArrayList<>();
        if (containsAny(normalized, "忽略之前", "ignore previous instructions")) {
            findings.add(finding(
                    "prompt.ignore-previous-instructions",
                    SecuritySeverity.HIGH,
                    SecurityDecision.BLOCK,
                    "检测到要求改变既有控制规则的内容"));
        }
        if (containsAny(normalized, "隐藏 prompt", "系统 prompt", "system prompt", "hidden prompt")) {
            findings.add(finding(
                    "prompt.reveal-hidden-instructions",
                    SecuritySeverity.HIGH,
                    SecurityDecision.BLOCK,
                    "检测到要求披露内部指令的内容"));
        }
        if (containsAny(normalized, "输出 api key", "output api key", "输出 token", "输出token", "环境变量", "凭据")) {
            findings.add(finding(
                    "prompt.exfiltrate-secrets",
                    SecuritySeverity.CRITICAL,
                    SecurityDecision.BLOCK,
                    "检测到要求披露凭据或运行时秘密的内容"));
        }
        if (containsAny(normalized, "绕过审批", "绕过权限", "绕过工作区", "bypass approval", "bypass authorization")) {
            findings.add(finding(
                    "prompt.redirect-tool-authority",
                    SecuritySeverity.CRITICAL,
                    SecurityDecision.BLOCK,
                    "检测到要求绕过工具治理边界的内容"));
        }
        if (!"user.task".equals(context.source())
                && containsAny(normalized, "要求 agent", "要求助手", "修改审批策略")) {
            findings.add(finding(
                    "prompt.untrusted-content-instruction",
                    SecuritySeverity.MEDIUM,
                    SecurityDecision.FLAG,
                    "检测到外部内容试图影响 Agent 行为"));
        }
        SecurityDecision decision = findings.stream()
                .anyMatch(finding -> finding.decision() == SecurityDecision.BLOCK)
                ? SecurityDecision.BLOCK
                : findings.isEmpty() ? SecurityDecision.ALLOW : SecurityDecision.FLAG;
        return new PromptSecurityAssessment(decision, findings);
    }

    private static SecurityFinding finding(
            String ruleId,
            SecuritySeverity severity,
            SecurityDecision decision,
            String summary) {
        return new SecurityFinding(ruleId, severity, decision, summary);
    }

    private static boolean containsAny(String value, String... fragments) {
        for (String fragment : fragments) {
            if (value.contains(fragment)) {
                return true;
            }
        }
        return false;
    }
}
