package com.agent.web.mcp.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OfficialMcpCatalogClientTest {
    private static final String COMMIT = "76d64c822f5125032f89eb71dbdb94e42b434821";

    @Test
    void resolvesCommitUsesFixedRefRetainsBlobAndMetadataSha256() throws Exception {
        String packageJson = "{\"name\":\"@modelcontextprotocol/server-everything\",\"version\":\"2.0.0\",\"description\":\"demo\",\"license\":\"MIT\",\"bin\":{\"mcp-server-everything\":\"dist/index.js\"},\"repository\":{\"type\":\"git\"}}";
        var exchange = new Exchange(Map.of(
                "/commits/release", "{\"sha\":\"" + COMMIT + "\"}",
                "/contents?ref=" + COMMIT, contents("src", "src", "tree-root", "dir"),
                "/contents/src?ref=" + COMMIT, contents("everything", "src/everything", "tree-service", "dir"),
                "/contents/src/everything?ref=" + COMMIT, "[" + content("package.json", "src/everything/package.json", "blob-package", "file") + "," + content("README.md", "src/everything/README.md", "blob-readme", "file") + "]",
                "/contents/src/everything/package.json?ref=" + COMMIT, encoded(packageJson, "blob-package"),
                "/contents/src/everything/README.md?ref=" + COMMIT, encoded("# Everything\n\n```json\n{\"command\":\"evil\"}\n```,", "blob-readme")
        ));
        var client = new OfficialMcpCatalogClient(exchange, new ObjectMapper(), URI.create("https://api.github.test/"), "release", Duration.ofSeconds(2), 100_000, Duration.ZERO);

        var record = client.fetchCatalog().getFirst();

        assertThat(exchange.paths).contains("/contents?ref=" + COMMIT, "/contents/src?ref=" + COMMIT);
        assertThat(record.commitSha()).isEqualTo(COMMIT);
        assertThat(record.blobShas()).containsEntry("package.json", "blob-package");
        assertThat(record.metadataSha256()).isEqualTo(sha256(packageJson));
        assertThat(record.command()).isEqualTo("npx");
        assertThat(record.arguments()).containsExactly("-y", "@modelcontextprotocol/server-everything@2.0.0");
        assertThat(record.readmeSummary()).isEqualTo("# Everything");
    }

    @Test
    void parsesPythonProjectAndScriptsWithLicenseObject() {
        String pyproject = "[project]\nname = \"mcp-server-time\"\nversion = \"0.6.2\"\ndescription = \"time\"\nlicense = { text = \"MIT\" }\n\n[project.scripts]\nmcp-server-time = \"mcp_server_time:main\"\n";
        var exchange = new Exchange(Map.of(
                "/commits/release", "{\"sha\":\"" + COMMIT + "\"}",
                "/contents?ref=" + COMMIT, contents("src", "src", "root", "dir"),
                "/contents/src?ref=" + COMMIT, contents("time", "src/time", "tree", "dir"),
                "/contents/src/time?ref=" + COMMIT, "[" + content("pyproject.toml", "src/time/pyproject.toml", "blob-py", "file") + "]",
                "/contents/src/time/pyproject.toml?ref=" + COMMIT, encoded(pyproject, "blob-py")
        ));

        var record = new OfficialMcpCatalogClient(exchange, new ObjectMapper(), URI.create("https://api.github.test/"), "release", Duration.ofSeconds(2), 100_000, Duration.ZERO).fetchCatalog().getFirst();

        assertThat(record.license()).isEqualTo("MIT");
        assertThat(record.command()).isEqualTo("uvx");
        assertThat(record.arguments()).containsExactly("mcp-server-time==0.6.2");
    }

    @Test
    void usesRootEtagAndReturnsSnapshotForNotModifiedAndRefreshFailure() {
        var exchange = new Exchange(Map.of(
                "/commits/release", "{\"sha\":\"" + COMMIT + "\"}",
                "/contents?ref=" + COMMIT, contents("src", "src", "root", "dir"),
                "/contents/src?ref=" + COMMIT, "[]"
        ));
        var client = new OfficialMcpCatalogClient(exchange, new ObjectMapper(), URI.create("https://api.github.test/"), "release", Duration.ofSeconds(2), 100_000, Duration.ofNanos(1));

        assertThat(client.fetchCatalog()).isEmpty();
        exchange.notModified = true;
        assertThat(client.fetchCatalog()).isEmpty();
        assertThat(exchange.etags).contains("root-etag");
        exchange.notModified = false;
        exchange.fail = true;
        assertThatThrownBy(client::fetchCatalog).hasMessageContaining("CATALOG_UNAVAILABLE");
    }

    @Test
    void rejectsRateLimitOversizedContentAndMissingMetadata() {
        var limited = new Exchange(Map.of("/commits/release", "{\"sha\":\"" + COMMIT + "\"}"));
        limited.rateLimited = true;
        var client = new OfficialMcpCatalogClient(limited, new ObjectMapper(), URI.create("https://api.github.test/"), "release", Duration.ofSeconds(2), 16, Duration.ZERO);
        assertThatThrownBy(client::fetchCatalog).hasMessageContaining("CATALOG_UNAVAILABLE");
    }

    @Test
    void keepsVerifiedServiceWhenAnotherServiceHasInvalidMetadata() {
        String packageJson = "{\"name\":\"@modelcontextprotocol/server-everything\",\"version\":\"2.0.0\",\"bin\":{\"server\":\"dist/index.js\"}}";
        var exchange = new Exchange(Map.of(
                "/commits/release", "{\"sha\":\"" + COMMIT + "\"}",
                "/contents?ref=" + COMMIT, contents("src", "src", "root", "dir"),
                "/contents/src?ref=" + COMMIT, "[" + content("everything", "src/everything", "tree-one", "dir") + "," + content("broken", "src/broken", "tree-two", "dir") + "]",
                "/contents/src/everything?ref=" + COMMIT, contents("package.json", "src/everything/package.json", "blob-ok", "file"),
                "/contents/src/everything/package.json?ref=" + COMMIT, encoded(packageJson, "blob-ok"),
                "/contents/src/broken?ref=" + COMMIT, "[]"
        ));

        var result = new OfficialMcpCatalogClient(exchange, new ObjectMapper(), URI.create("https://api.github.test/"), "release", Duration.ofSeconds(2), 100_000, Duration.ZERO).fetchCatalogResult();

        assertThat(result.records()).extracting(OfficialMcpServerRecord::serviceId).containsExactly("everything");
        assertThat(result.errors()).containsKey("broken");
    }

    @Test
    void treatsRateLimited403AsCatalogUnavailable() {
        var exchange = new Exchange(Map.of("/commits/release", "{\"sha\":\"" + COMMIT + "\"}"));
        exchange.rateLimited403 = true;

        var client = new OfficialMcpCatalogClient(exchange, new ObjectMapper(), URI.create("https://api.github.test/"), "release", Duration.ofSeconds(2), 100_000, Duration.ZERO);

        assertThatThrownBy(client::fetchCatalog).hasMessageContaining("CATALOG_UNAVAILABLE");
    }

    private static String contents(String name, String path, String sha, String type) { return "[" + content(name, path, sha, type) + "]"; }
    private static String content(String name, String path, String sha, String type) { return "{\"name\":\"" + name + "\",\"path\":\"" + path + "\",\"sha\":\"" + sha + "\",\"size\":0,\"git_url\":\"x\",\"html_url\":\"x\",\"url\":\"x\",\"download_url\":null,\"type\":\"" + type + "\",\"_links\":{\"self\":\"x\"}}"; }
    private static String encoded(String source, String sha) { return "{\"type\":\"file\",\"sha\":\"" + sha + "\",\"encoding\":\"base64\",\"content\":\"" + Base64.getEncoder().encodeToString(source.getBytes(StandardCharsets.UTF_8)) + "\"}"; }
    private static String sha256(String source) throws Exception { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8))); }

    private static final class Exchange implements OfficialMcpCatalogClient.HttpExchange {
        private final Map<String, String> values; private final List<String> paths = new ArrayList<>(); private final List<String> etags = new ArrayList<>(); private boolean fail; private boolean notModified; private boolean rateLimited; private boolean rateLimited403;
        private Exchange(Map<String, String> values) { this.values = values; }
        @Override public OfficialMcpCatalogClient.HttpResponse exchange(URI uri, Duration timeout, int maxBytes, String etag) {
            paths.add(uri.getPath() + (uri.getQuery() == null ? "" : "?" + uri.getQuery())); if (etag != null) etags.add(etag);
            if (fail) throw new IllegalStateException("offline"); if (rateLimited) return new OfficialMcpCatalogClient.HttpResponse(429, "{}", null); if (rateLimited403) return new OfficialMcpCatalogClient.HttpResponse(403, "{}", null, 0);
            if (notModified && uri.getPath().equals("/contents")) return new OfficialMcpCatalogClient.HttpResponse(304, "", "root-etag");
            return new OfficialMcpCatalogClient.HttpResponse(200, values.get(uri.getPath() + (uri.getQuery() == null ? "" : "?" + uri.getQuery())), "root-etag");
        }
    }
}
