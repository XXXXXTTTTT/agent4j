package com.agent.web.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** 使用 GitHub REST API 检索仓库并取得固定提交的单个 SKILL.md。 */
public final class GitHubSkillCatalogClient {

    private static final Pattern COMMIT_SHA = Pattern.compile("[0-9a-f]{40}");
    private static final String SKILL_PATH = "SKILL.md";

    private final HttpExchange exchange;
    private final ObjectMapper objectMapper;
    private final URI apiBase;
    private final Duration timeout;
    private final int maxBytes;

    /** 创建使用注入 HTTP 端口的 GitHub Skill 客户端。 */
    public GitHubSkillCatalogClient(
            HttpExchange exchange,
            ObjectMapper objectMapper,
            URI apiBase,
            Duration timeout,
            int maxBytes) {
        this.exchange = Objects.requireNonNull(exchange, "exchange 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        this.apiBase = Objects.requireNonNull(apiBase, "apiBase 不能为空");
        this.timeout = positive(timeout, "timeout");
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes 必须大于零");
        }
        this.maxBytes = maxBytes;
    }

    /** 搜索公开 GitHub 仓库；不完整结果不得作为安装来源。 */
    public List<GitHubSkillRepository> search(String query) {
        String normalized = required(query, "query").strip();
        JsonNode response = object(request("search/repositories?q="
                + URLEncoder.encode(normalized, StandardCharsets.UTF_8) + "&per_page=20"), "GitHub 搜索响应");
        if (response.path("incomplete_results").asBoolean(false)) {
            throw new IllegalStateException("GitHub 搜索结果不完整");
        }
        JsonNode items = response.path("items");
        if (!items.isArray()) {
            throw new IllegalArgumentException("GitHub 搜索响应缺少 items 数组");
        }
        List<GitHubSkillRepository> results = new ArrayList<>(items.size());
        for (JsonNode item : items) {
            results.add(repository(item));
        }
        return List.copyOf(results);
    }

    /** 读取仓库默认分支解析出的固定 commit 下精确路径 `SKILL.md`。 */
    public GitHubSkillSnapshot readSkill(String repository, Set<String> registeredToolNames) {
        String exactRepository = repositoryName(repository);
        GitHubSkillRepository metadata = repository(object(request(
                "repos/" + exactRepository), "GitHub 仓库响应"));
        String commitSha = commitSha(object(request("repos/" + exactRepository + "/commits/"
                + encodePath(metadata.defaultBranch())), "GitHub commit 响应"));
        JsonNode content = object(request("repos/" + exactRepository + "/contents/" + SKILL_PATH
                + "?ref=" + commitSha), "GitHub Skill 内容响应");
        if (!SKILL_PATH.equals(text(content, "path"))) {
            throw new IllegalArgumentException("GitHub Skill 路径必须精确为 SKILL.md");
        }
        if (!"file".equals(text(content, "type"))) {
            throw new IllegalArgumentException("GitHub Skill 必须是文件");
        }
        if (!"base64".equals(text(content, "encoding"))) {
            throw new IllegalArgumentException("GitHub Skill 编码必须为 base64");
        }
        String blobSha = text(content, "sha");
        byte[] bytes = decode(text(content, "content"));
        if (bytes.length > maxBytes) {
            throw new IllegalArgumentException("GitHub Skill 内容超过大小限制");
        }
        String source = decodeUtf8(bytes);
        GitHubSkillContent parsed = GitHubSkillContent.parse(source, registeredToolNames);
        return new GitHubSkillSnapshot(
                metadata.repositoryUrl(), exactRepository, commitSha, blobSha, SKILL_PATH,
                metadata.license(), sha256(bytes), parsed.summary(), parsed.requestedToolNames(), source);
    }

    private GitHubSkillRepository repository(JsonNode node) {
        JsonNode license = node.path("license");
        String licenseId = license.isObject() ? license.path("spdx_id").asText("") : "";
        return new GitHubSkillRepository(
                text(node, "full_name"), URI.create(text(node, "html_url")),
                text(node, "default_branch"), node.path("description").asText(""), licenseId);
    }

    private String commitSha(JsonNode node) {
        String value = text(node, "sha");
        if (!COMMIT_SHA.matcher(value).matches()) {
            throw new IllegalArgumentException("GitHub commit SHA 格式不合法");
        }
        return value;
    }

    private String request(String path) {
        HttpResponse response = Objects.requireNonNull(exchange.exchange(
                apiBase.resolve(path), timeout, maxBytes), "GitHub HTTP 响应不能为空");
        if (response.statusCode() == 429) {
            throw new IllegalStateException("GitHub API 限流");
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("GitHub HTTP " + response.statusCode());
        }
        String body = Objects.requireNonNull(response.body(), "GitHub HTTP 响应体不能为空");
        if (body.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new IllegalArgumentException("GitHub HTTP 响应超过大小限制");
        }
        return body;
    }

    private JsonNode object(String source, String resource) {
        try {
            JsonNode node = objectMapper.readTree(source);
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException(resource + " 必须是 JSON object");
            }
            return node;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException(resource + " 不是有效 JSON", exception);
        }
    }

    private static String repositoryName(String value) {
        String repository = required(value, "repository");
        String[] parts = repository.split("/", -1);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException("repository 必须是 GitHub owner/repository");
        }
        return repository;
    }

    private static String text(JsonNode node, String field) {
        return required(node.path(field).asText(), field);
    }

    private static byte[] decode(String value) {
        try {
            return Base64.getMimeDecoder().decode(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("GitHub Skill base64 内容不合法", exception);
        }
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes))
                    .toString();
        } catch (java.nio.charset.CharacterCodingException exception) {
            throw new IllegalArgumentException("GitHub Skill 内容不是 UTF-8", exception);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private static Duration positive(Duration value, String field) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(field + " 必须大于零");
        }
        return value;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }

    private static String encodePath(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** GitHub HTTP 传输端口。 */
    public interface HttpExchange {
        HttpResponse exchange(URI uri, Duration timeout, int maxBytes);
    }

    /** GitHub HTTP 响应。 */
    public record HttpResponse(int statusCode, String body) {
    }
}
