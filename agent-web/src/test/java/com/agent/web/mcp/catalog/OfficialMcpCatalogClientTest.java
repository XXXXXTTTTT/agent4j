package com.agent.web.mcp.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OfficialMcpCatalogClientTest {
    @Test
    void parsesVerifiedServicesAndRetainsSourceSha() {
        var exchange = new FixtureExchange(Map.of(
                "/contents", "[{\"name\":\"src\",\"type\":\"dir\",\"path\":\"src\",\"sha\":\"root-sha\"}]",
                "/contents/src", "[{\"name\":\"everything\",\"type\":\"dir\",\"path\":\"src/everything\",\"sha\":\"svc-sha\"}]",
                "/contents/src/everything/package.json", "{\"name\":\"@modelcontextprotocol/server-everything\",\"version\":\"2.0.0\",\"license\":\"MIT\",\"description\":\"demo\",\"bin\":{\"mcp-server-everything\":\"dist/index.js\"}}",
                "/contents/src/everything/README.md", "{\"content\":\"IyBFeGFtcGxl\\n\\n\\`\\`\\`json\\n{\\\"command\\\":\\\"npx\\\",\\\"args\\\":[\\\"-y\\\",\\\"@modelcontextprotocol/server-everything\\\"]}\\n\\`\\`\\`\"}"
        ));
        var client = new OfficialMcpCatalogClient(exchange, new ObjectMapper(), URI.create("https://api.github.test"), Duration.ofSeconds(2), 100_000);

        var records = client.fetchCatalog();

        assertThat(records).singleElement().satisfies(record -> {
            assertThat(record.serviceId()).isEqualTo("everything");
            assertThat(record.sourceSha()).isEqualTo("svc-sha");
            assertThat(record.version()).isEqualTo("2.0.0");
            assertThat(record.command()).isEqualTo("npx");
            assertThat(record.arguments()).containsExactly("-y", "@modelcontextprotocol/server-everything");
        });
    }

    @Test
    void rejectsServiceWithoutPackageMetadata() {
        var exchange = new FixtureExchange(Map.of(
                "/contents", "[{\"name\":\"src\",\"type\":\"dir\",\"path\":\"src\",\"sha\":\"root\"}]",
                "/contents/src", "[{\"name\":\"everything\",\"type\":\"dir\",\"path\":\"src/everything\",\"sha\":\"svc\"}]",
                "/contents/src/everything/package.json", "{}",
                "/contents/src/everything/README.md", "{\"content\":\"\"}"
        ));
        var client = new OfficialMcpCatalogClient(exchange, new ObjectMapper(), URI.create("https://api.github.test"), Duration.ofSeconds(2), 100_000);

        assertThatThrownBy(client::fetchCatalog).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("service metadata");
    }

    @Test
    void retainsFixedCommitAndPackageBlobSha() {
        var exchange = new FixtureExchange(Map.of(
                "/contents", "[{\"name\":\"src\",\"type\":\"dir\",\"path\":\"src\",\"sha\":\"root\"}]",
                "/contents/src", "[{\"name\":\"fetch\",\"type\":\"dir\",\"path\":\"src/fetch\",\"sha\":\"tree\"}]",
                "/contents/src/fetch", "[{\"name\":\"pyproject.toml\",\"type\":\"file\",\"path\":\"src/fetch/pyproject.toml\",\"sha\":\"blob-1\"},{\"name\":\"README.md\",\"type\":\"file\",\"path\":\"src/fetch/README.md\",\"sha\":\"blob-2\"}]",
                "/contents/src/fetch/pyproject.toml", "{\"content\":\"W3Byb2plY3RdXG5uYW1lID0gXCJtY3Atc2VydmVyLWZldGNoXCJcblZlcnNpb24gPSBcIjAuNi4zXCI=\"}",
                "/contents/src/fetch/README.md", "{\"content\":\"IyBGZXRjaA==\"}"
        ));
        var client = new OfficialMcpCatalogClient(exchange, new ObjectMapper(), URI.create("https://api.github.test"), "commit-123", Duration.ofSeconds(2), 100_000, Duration.ZERO);

        var record = client.fetchCatalog().getFirst();

        assertThat(record.commitSha()).isEqualTo("commit-123");
        assertThat(record.blobShas()).containsEntry("pyproject.toml", "blob-1");
    }

    @Test
    void returnsStaleSnapshotWhenRefreshFailsWithinTtlWindow() {
        var exchange = new FixtureExchange(Map.of(
                "/contents", "[{\"name\":\"src\",\"type\":\"dir\",\"path\":\"src\",\"sha\":\"root\"}]",
                "/contents/src", "[]"
        ));
        var client = new OfficialMcpCatalogClient(exchange, new ObjectMapper(), URI.create("https://api.github.test"), "commit", Duration.ofSeconds(2), 100_000, Duration.ofMinutes(5));

        assertThat(client.fetchCatalog()).isEmpty();
        exchange.fail = true;
        assertThat(client.fetchCatalog()).isEmpty();
    }

    private static final class FixtureExchange implements OfficialMcpCatalogClient.HttpExchange {
        private final Map<String, String> values;
        private boolean fail;
        private FixtureExchange(Map<String, String> values) { this.values = values; }
        @Override public String get(URI uri, Duration timeout, int maxBytes) { return values.get(uri.getPath()); }
        @Override public OfficialMcpCatalogClient.HttpResponse exchange(URI uri, Duration timeout, int maxBytes, String etag) {
            if (fail) throw new IllegalStateException("offline");
            var value = values.get(uri.getPath());
            return new OfficialMcpCatalogClient.HttpResponse(200, value, "etag-1");
        }
    }
}
