package com.agent.web.mcp.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 固定 GitHub commit 的官方 MCP 目录只读客户端。 */
public final class OfficialMcpCatalogClient {
    private static final Set<String> CONTENT_FIELDS = Set.of("name", "path", "type", "sha", "url", "download_url");
    private static final Pattern JSON_COMMAND = Pattern.compile("\\\"command\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*?\\\"args\\\"\\s*:\\s*\\[([^]]*)\\]", Pattern.DOTALL);
    private static final Pattern ARG = Pattern.compile("\\\"([^\\\"]*)\\\"");

    private final HttpExchange exchange;
    private final ObjectMapper objectMapper;
    private final URI apiBase;
    private final String commitSha;
    private final Duration timeout;
    private final int maxBytes;
    private final Duration ttl;
    private volatile Snapshot snapshot;
    private volatile String lastEtag;

    public OfficialMcpCatalogClient(HttpExchange exchange, ObjectMapper objectMapper, URI apiBase,
                                    Duration timeout, int maxBytes) {
        this(exchange, objectMapper, apiBase, "main", timeout, maxBytes, Duration.ZERO);
    }

    public OfficialMcpCatalogClient(HttpExchange exchange, ObjectMapper objectMapper, URI apiBase,
                                    String commitSha, Duration timeout, int maxBytes, Duration ttl) {
        this.exchange = exchange;
        this.objectMapper = objectMapper;
        this.apiBase = apiBase;
        this.commitSha = commitSha;
        this.timeout = timeout;
        this.maxBytes = maxBytes;
        this.ttl = ttl;
    }

    public OfficialMcpCatalogClient(ObjectMapper objectMapper, URI apiBase, String commitSha,
                                    Duration timeout, int maxBytes, Duration ttl) {
        this(new JdkHttpExchange(), objectMapper, apiBase, commitSha, timeout, maxBytes, ttl);
    }

    /** 获取目录；刷新失败且存在未过期快照时返回旧快照。 */
    public List<OfficialMcpServerRecord> fetchCatalog() {
        Snapshot cached = snapshot;
        if (cached != null && !cached.expired()) return cached.records;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<List<OfficialMcpServerRecord>> future = executor.submit((Callable<List<OfficialMcpServerRecord>>) this::load);
            List<OfficialMcpServerRecord> records = future.get();
            snapshot = new Snapshot(records, System.nanoTime(), lastEtag, ttl.toNanos());
            return records;
        } catch (Exception failure) {
            if (failure.getCause() instanceof NotModifiedException && cached != null) return cached.records;
            if (cached != null) return cached.records;
            throw new IllegalStateException("CATALOG_UNAVAILABLE", failure);
        }
    }

    private List<OfficialMcpServerRecord> load() throws Exception {
        JsonNode root = objectMapper.readTree(read("/contents?ref=" + commitSha));
        validateContents(root);
        JsonNode src = findDirectory(root, "src");
        JsonNode services = objectMapper.readTree(read("/contents/src?ref=" + commitSha));
        validateContents(services);
        List<OfficialMcpServerRecord> result = new ArrayList<>();
        for (JsonNode service : services) {
            if (!"dir".equals(service.path("type").asText())) continue;
            String id = service.path("name").asText();
            String path = service.path("path").asText();
            JsonNode files = objectMapper.readTree(read("/contents/" + path + "?ref=" + commitSha));
            validateContents(files);
            Map<String, String> blobs = new HashMap<>();
            for (JsonNode file : files) blobs.put(file.path("name").asText(), file.path("sha").asText());
            String metadataName = blobs.containsKey("package.json") ? "package.json" : "pyproject.toml";
            if (!blobs.containsKey(metadataName)) throw new IllegalArgumentException("missing service metadata: " + id);
            String metadata = decodeContent(read("/contents/" + path + "/" + metadataName + "?ref=" + commitSha), metadataName);
            Metadata parsed = metadataName.equals("package.json") ? parsePackage(metadata) : parsePython(metadata);
            String readme = blobs.containsKey("README.md") ? decodeContent(read("/contents/" + path + "/README.md?ref=" + commitSha), "README.md") : "";
            Launch launch = launch(parsed, readme);
            result.add(new OfficialMcpServerRecord(id, path, URI.create("https://github.com/modelcontextprotocol/servers/tree/" + commitSha + "/" + path), commitSha, blobs, parsed.version, parsed.description, parsed.license, launch.command, launch.arguments, List.of(), summarize(readme)));
        }
        return List.copyOf(result);
    }

    private String read(String path) {
        HttpResponse response = exchange.exchange(apiBase.resolve(path.startsWith("/") ? path.substring(1) : path), timeout, maxBytes, snapshot == null ? null : snapshot.etag);
        lastEtag = response.etag();
        if (response.statusCode() == 304 && snapshot != null) throw new NotModifiedException();
        if (response.statusCode() >= 400) throw new IllegalStateException("GitHub HTTP " + response.statusCode());
        return response.body();
    }

    private void validateContents(JsonNode entries) {
        if (!entries.isArray()) throw new IllegalArgumentException("invalid GitHub Contents response");
        for (JsonNode entry : entries) {
            var fields = entry.fieldNames();
            while (fields.hasNext()) if (!CONTENT_FIELDS.contains(fields.next())) throw new IllegalArgumentException("unknown contents field");
        }
    }

    private JsonNode findDirectory(JsonNode entries, String name) {
        for (JsonNode n : entries) if (name.equals(n.path("name").asText())) return n;
        throw new IllegalArgumentException("missing src directory");
    }

    private String decodeContent(String json, String file) throws Exception {
        JsonNode node = objectMapper.readTree(json);
        String content = node.path("content").asText();
        if (content.isBlank()) return json;
        return new String(Base64.getMimeDecoder().decode(content), StandardCharsets.UTF_8);
    }

    private Metadata parsePackage(String json) throws Exception {
        JsonNode n = objectMapper.readTree(json);
        Set<String> allowed = Set.of("name", "version", "description", "license", "bin");
        var fields = n.fieldNames();
        while (fields.hasNext()) if (!allowed.contains(fields.next())) throw new IllegalArgumentException("unknown package metadata field");
        if (n.path("name").asText().isBlank() || n.path("version").asText().isBlank()
                || n.path("description").asText().isBlank() || n.path("license").asText().isBlank()
                || !n.path("bin").isObject() || n.path("bin").isEmpty()) {
            throw new IllegalArgumentException("missing service metadata");
        }
        return new Metadata(n.path("version").asText(), n.path("description").asText(), n.path("license").asText(), n.path("name").asText());
    }

    private Metadata parsePython(String text) {
        String name = value(text, "name");
        String version = value(text, "version");
        String license = value(text, "license");
        return new Metadata(version, value(text, "description"), license, name);
    }

    private String value(String text, String key) {
        Matcher m = Pattern.compile("(?m)^\\s*" + Pattern.quote(key) + "\\s*=\\s*[\\\"]([^\\\"]+)[\\\"]").matcher(text);
        return m.find() ? m.group(1) : "";
    }

    private Launch launch(Metadata metadata, String readme) {
        Matcher m = JSON_COMMAND.matcher(readme);
        if (m.find()) {
            List<String> args = new ArrayList<>(); Matcher a = ARG.matcher(m.group(2)); while (a.find()) args.add(a.group(1));
            return new Launch(m.group(1), args);
        }
        if (metadata.name.startsWith("mcp-server-")) return new Launch("uvx", List.of(metadata.name));
        throw new IllegalArgumentException("missing launch metadata: " + metadata.name);
    }

    private String summarize(String readme) { return readme.lines().filter(line -> !line.isBlank()).findFirst().orElse(""); }

    public interface HttpExchange {
        default String get(URI uri, Duration timeout, int maxBytes) { return exchange(uri, timeout, maxBytes, null).body(); }
        default HttpResponse exchange(URI uri, Duration timeout, int maxBytes, String etag) { return new HttpResponse(200, get(uri, timeout, maxBytes), null); }
    }
    public record HttpResponse(int statusCode, String body, String etag) {}
    private record Metadata(String version, String description, String license, String name) {}
    private record Launch(String command, List<String> arguments) {}
    private record Snapshot(List<OfficialMcpServerRecord> records, long createdNanos, String etag, long ttlNanos) { boolean expired() { return ttlNanos > 0 && System.nanoTime() - createdNanos >= ttlNanos; } }
    private static final class NotModifiedException extends RuntimeException {}
    private static final class JdkHttpExchange implements HttpExchange {
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        @Override public HttpResponse exchange(URI uri, Duration timeout, int maxBytes, String etag) {
            try { var request = HttpRequest.newBuilder(uri).timeout(timeout).header("Accept", "application/vnd.github+json").header("User-Agent", "agent4j").build(); var response = client.send(request, HttpResponse.BodyHandlers.ofString()); if (response.body().getBytes(StandardCharsets.UTF_8).length > maxBytes) throw new IllegalStateException("response too large"); return new HttpResponse(response.statusCode(), response.body(), response.headers().firstValue("ETag").orElse(null)); } catch (Exception e) { throw new IllegalStateException(e); }
        }
    }
}
