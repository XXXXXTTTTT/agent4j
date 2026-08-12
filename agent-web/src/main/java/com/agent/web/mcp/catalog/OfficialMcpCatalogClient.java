package com.agent.web.mcp.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

/** 从 GitHub 固定 commit 读取官方 MCP 服务目录。 */
public final class OfficialMcpCatalogClient {
    private static final Pattern COMMIT_SHA = Pattern.compile("[0-9a-f]{40}");

    private final HttpExchange exchange;
    private final ObjectMapper objectMapper;
    private final URI apiBase;
    private final String revision;
    private final Duration timeout;
    private final int maxBytes;
    private final Duration ttl;
    private volatile Snapshot snapshot;

    public OfficialMcpCatalogClient(HttpExchange exchange, ObjectMapper objectMapper, URI apiBase,
                                    String revision, Duration timeout, int maxBytes, Duration ttl) {
        this.exchange = Objects.requireNonNull(exchange, "exchange 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        this.apiBase = Objects.requireNonNull(apiBase, "apiBase 不能为空");
        this.revision = required(revision, "revision");
        this.timeout = positive(timeout, "timeout");
        if (maxBytes <= 0) throw new IllegalArgumentException("maxBytes 必须大于零");
        this.maxBytes = maxBytes;
        this.ttl = Objects.requireNonNull(ttl, "ttl 不能为空");
    }

    public OfficialMcpCatalogClient(ObjectMapper objectMapper, URI apiBase, String revision,
                                    Duration timeout, int maxBytes, Duration ttl) {
        this(new JdkHttpExchange(), objectMapper, apiBase, revision, timeout, maxBytes, ttl);
    }

    /** 在 TTL 内返回缓存；过期刷新失败时返回旧快照。 */
    public List<OfficialMcpServerRecord> fetchCatalog() {
        return fetchCatalogResult().records();
    }

    /** 获取目录并隔离单个服务解析错误。 */
    public CatalogResult fetchCatalogResult() {
        Snapshot cached = snapshot;
        if (cached != null && !cached.expired()) return cached.result();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Snapshot> future = executor.submit((Callable<Snapshot>) () -> refresh(cached));
            Snapshot refreshed = future.get();
            if (refreshed != null) snapshot = refreshed;
            Snapshot result = refreshed == null ? cached : refreshed;
            return result == null ? new CatalogResult(List.of(), Map.of()) : result.result();
        } catch (Exception failure) {
            if (cached != null) return cached.result();
            throw new IllegalStateException("CATALOG_UNAVAILABLE", failure);
        }
    }

    private Snapshot refresh(Snapshot cached) throws Exception {
        String commit = resolveCommit();
        HttpResponse rootResponse = request("/contents?ref=" + commit, cached == null ? null : cached.rootEtag());
        if (rootResponse.statusCode() == 304) {
            if (cached == null) throw new IllegalStateException("unexpected root 304");
            return new Snapshot(cached.result(), System.nanoTime(), cached.rootEtag(), ttl.toNanos());
        }
        JsonNode root = array(rootResponse.body(), "root contents");
        JsonNode src = directory(root, "src");
        JsonNode services = array(request("/contents/" + text(src, "path") + "?ref=" + commit, null).body(), "src contents");
        List<OfficialMcpServerRecord> records = new ArrayList<>();
        Map<String, String> errors = new LinkedHashMap<>();
        for (JsonNode service : services) {
            if (!"dir".equals(text(service, "type"))) continue;
            String id = text(service, "name");
            try { records.add(readService(commit, service)); }
            catch (Exception failure) { errors.put(id, failureMessage(failure)); }
        }
        return new Snapshot(new CatalogResult(List.copyOf(records), Map.copyOf(errors)), System.nanoTime(), rootResponse.etag(), ttl.toNanos());
    }

    private String resolveCommit() throws Exception {
        JsonNode node = objectMapper.readTree(request("/commits/" + revision, null).body());
        String commit = text(node, "sha");
        if (!COMMIT_SHA.matcher(commit).matches()) throw new IllegalArgumentException("invalid commit SHA");
        return commit;
    }

    private OfficialMcpServerRecord readService(String commit, JsonNode service) throws Exception {
        String id = text(service, "name");
        String path = text(service, "path");
        JsonNode files = array(request("/contents/" + path + "?ref=" + commit, null).body(), "service contents");
        Map<String, String> blobs = blobs(files);
        String metadataFile = blobs.containsKey("package.json") ? "package.json" : "pyproject.toml";
        if (!blobs.containsKey(metadataFile)) throw new IllegalArgumentException("missing service metadata: " + id);
        FileContent metadata = readFile(commit, path, metadataFile, blobs.get(metadataFile));
        Metadata parsed = "package.json".equals(metadataFile) ? packageMetadata(metadata.text()) : pythonMetadata(metadata.text());
        String readme = blobs.containsKey("README.md") ? readFile(commit, path, "README.md", blobs.get("README.md")).text() : "";
        return new OfficialMcpServerRecord(id, path,
                URI.create("https://github.com/modelcontextprotocol/servers/tree/" + commit + "/" + path),
                commit, blobs, sha256(metadata.bytes()), parsed.version(), parsed.description(), parsed.license(),
                parsed.command(), parsed.arguments(), List.of(), summary(readme));
    }

    private FileContent readFile(String commit, String path, String name, String expectedBlobSha) throws Exception {
        JsonNode node = objectMapper.readTree(request("/contents/" + path + "/" + name + "?ref=" + commit, null).body());
        if (!expectedBlobSha.equals(text(node, "sha"))) throw new IllegalArgumentException("blob SHA mismatch: " + name);
        if (!"base64".equals(text(node, "encoding"))) throw new IllegalArgumentException("unsupported content encoding: " + name);
        String encoded = text(node, "content");
        byte[] bytes = Base64.getMimeDecoder().decode(encoded);
        if (bytes.length > maxBytes) throw new IllegalArgumentException("response too large");
        try { return new FileContent(bytes, StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()); }
        catch (CharacterCodingException failure) { throw new IllegalArgumentException("content is not valid UTF-8", failure); }
    }

    private Metadata packageMetadata(String source) throws Exception {
        JsonNode node;
        try { node = objectMapper.readTree(source); }
        catch (Exception failure) { throw new IllegalArgumentException("invalid package.json", failure); }
        if (node == null || !node.isObject()) throw new IllegalArgumentException("invalid package.json");
        String name = text(node, "name");
        String version = text(node, "version");
        JsonNode bin = node.path("bin");
        if (!bin.isObject() || bin.isEmpty()) throw new IllegalArgumentException("missing package bin");
        if (bin.size() != 1) throw new IllegalArgumentException("package bin must contain exactly one entry");
        var entry = bin.fields().next();
        if (!entry.getValue().isTextual() || entry.getValue().asText().isBlank()) throw new IllegalArgumentException("missing package bin");
        if (!expectedBinKey(name).equals(entry.getKey())) throw new IllegalArgumentException("package bin key does not match package identity");
        return new Metadata(version, node.path("description").asText(""), node.path("license").asText(""), "npx", List.of("-y", name + "@" + version));
    }

    private Metadata pythonMetadata(String source) {
        TomlTables tables = TomlTables.parse(source);
        Map<String, String> project = tables.values("project");
        Map<String, String> scripts = tables.values("project.scripts");
        String name = unquote(required(project.get("name"), "project.name"));
        String version = unquote(required(project.get("version"), "project.version"));
        if (!scripts.containsKey(name) || unquote(scripts.get(name)).isBlank()) throw new IllegalArgumentException("missing project script: " + name);
        return new Metadata(version, unquote(project.getOrDefault("description", "")), license(project.get("license")), "uvx", List.of(name + "==" + version));
    }

    private HttpResponse request(String path, String etag) {
        HttpResponse response = exchange.exchange(apiBase.resolve(path.startsWith("/") ? path.substring(1) : path), timeout, maxBytes, etag);
        if (response.statusCode() == 429 || (response.statusCode() == 403 && response.rateLimitRemaining() != null && response.rateLimitRemaining() == 0)) throw new IllegalStateException("GitHub rate limited");
        if (response.statusCode() != 200 && response.statusCode() != 304) throw new IllegalStateException("GitHub HTTP " + response.statusCode());
        return response;
    }

    private JsonNode array(String body, String resource) throws Exception { JsonNode node = objectMapper.readTree(body); if (!node.isArray()) throw new IllegalArgumentException("invalid " + resource); return node; }
    private JsonNode directory(JsonNode entries, String name) { for (JsonNode entry : entries) if (name.equals(entry.path("name").asText()) && "dir".equals(entry.path("type").asText())) return entry; throw new IllegalArgumentException("missing directory: " + name); }
    private Map<String, String> blobs(JsonNode files) { Map<String, String> values = new LinkedHashMap<>(); for (JsonNode file : files) if ("file".equals(file.path("type").asText())) values.put(text(file, "name"), text(file, "sha")); return Map.copyOf(values); }
    private String text(JsonNode node, String field) { String value = node.path(field).asText(); return required(value, field); }
    private String summary(String readme) { return readme.lines().filter(line -> !line.isBlank()).findFirst().orElse(""); }
    private String failureMessage(Exception failure) { String message = failure.getMessage(); return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message; }
    private String expectedBinKey(String packageName) {
        int slash = packageName.lastIndexOf('/');
        String unscopedName = slash < 0 ? packageName : packageName.substring(slash + 1);
        if (unscopedName.isBlank()) throw new IllegalArgumentException("invalid package name");
        return "mcp-" + unscopedName;
    }
    private String license(String value) { if (value == null || value.isBlank()) return ""; String trimmed = value.trim(); if (!trimmed.startsWith("{")) return unquote(trimmed); int marker = trimmed.indexOf("text"); int quote = marker < 0 ? -1 : trimmed.indexOf('"', marker); int end = quote < 0 ? -1 : trimmed.indexOf('"', quote + 1); return quote >= 0 && end > quote ? trimmed.substring(quote + 1, end) : ""; }
    private String sha256(byte[] bytes) throws Exception { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static String unquote(String value) { return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"") ? value.substring(1, value.length() - 1) : value; }
    private static String required(String value, String field) { if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " 不能为空"); return value; }
    private static Duration positive(Duration value, String field) { if (value == null || value.isNegative() || value.isZero()) throw new IllegalArgumentException(field + " 必须大于零"); return value; }

    public interface HttpExchange { HttpResponse exchange(URI uri, Duration timeout, int maxBytes, String etag); }
    public record CatalogResult(List<OfficialMcpServerRecord> records, Map<String, String> errors) { public CatalogResult { records = List.copyOf(records); errors = Map.copyOf(errors); } }
    public record HttpResponse(int statusCode, String body, String etag, Integer rateLimitRemaining) { public HttpResponse(int statusCode, String body, String etag) { this(statusCode, body, etag, null); } }
    private record FileContent(byte[] bytes, String text) {}
    private record Metadata(String version, String description, String license, String command, List<String> arguments) {}
    private record Snapshot(CatalogResult result, long createdNanos, String rootEtag, long ttlNanos) { boolean expired() { return ttlNanos <= 0 || System.nanoTime() - createdNanos >= ttlNanos; } }

    /** 仅支持本目录使用的 TOML table、字符串和值对象，避免跨 table 的键匹配。 */
    private static final class TomlTables {
        private final Map<String, Map<String, String>> tables = new LinkedHashMap<>();
        static TomlTables parse(String source) {
            TomlTables result = new TomlTables(); String table = "";
            for (String raw : source.lines().toList()) {
                String line = raw.strip(); if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("[") && line.endsWith("]")) { table = line.substring(1, line.length() - 1); result.tables.putIfAbsent(table, new LinkedHashMap<>()); continue; }
                int equals = line.indexOf('='); if (equals <= 0 || table.isEmpty()) continue;
                result.tables.computeIfAbsent(table, ignored -> new LinkedHashMap<>()).put(line.substring(0, equals).strip(), line.substring(equals + 1).strip());
            }
            return result;
        }
        Map<String, String> values(String table) { return tables.getOrDefault(table, Map.of()); }
    }

    private static final class JdkHttpExchange implements HttpExchange {
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        @Override public HttpResponse exchange(URI uri, Duration timeout, int maxBytes, String etag) {
            try {
                HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(timeout).header("Accept", "application/vnd.github+json").header("User-Agent", "agent4j");
                if (etag != null) request.header("If-None-Match", etag);
                var response = client.send(request.build(), BodyHandlers.ofInputStream());
                String body = readBounded(response.body(), maxBytes);
                Integer remaining = response.headers().firstValue("X-RateLimit-Remaining").map(Integer::valueOf).orElse(null);
                return new HttpResponse(response.statusCode(), body, response.headers().firstValue("ETag").orElse(null), remaining);
            } catch (Exception failure) { throw new IllegalStateException("GitHub request failed", failure); }
        }
        private String readBounded(InputStream input, int maxBytes) throws Exception { try (input) { byte[] bytes = input.readNBytes(maxBytes + 1); if (bytes.length > maxBytes) throw new IllegalArgumentException("response too large"); return new String(bytes, StandardCharsets.UTF_8); } }
    }
}
