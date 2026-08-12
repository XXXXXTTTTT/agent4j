package com.agent.web.mcp.runtime;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import com.agent.web.mcp.installation.WorkspaceMountMode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.DockerClientFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Docker 运行器真实引擎验证；没有 Docker 时明确跳过。 */
class DockerMcpStdioRunnerTest {
    private static final String IMAGE = "alpine:3.20";
    private static DockerClient verificationClient;

    @BeforeAll
    static void verifyDocker() {
        try {
            assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                    "Docker Engine 不可用，跳过真实 Docker MCP runner 测试");
            verificationClient = createDockerClient();
            verificationClient.pingCmd().exec();
            verificationClient.inspectImageCmd(IMAGE).exec();
        } catch (Exception exception) {
            assumeTrue(false, "需要可用的 Docker Engine 与 " + IMAGE + " 镜像: " + exception.getMessage());
        }
    }

    @AfterAll
    static void closeClient() throws IOException {
        if (verificationClient != null) {
            verificationClient.close();
        }
    }

    @Test
    void createsHardenedContainerWithLabelsAndReadOnlyWorkspace(@TempDir Path workspace) throws Exception {
        UUID installationId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        McpDockerLaunchSpec spec = new McpDockerLaunchSpec(
                installationId, snapshotId, IMAGE, "sh", List.of("-c", "printf ready"),
                "/workspace", WorkspaceMountMode.READ_ONLY, "none",
                128L * 1024 * 1024, 100_000_000L, 64L,
                4096, 4096, Set.of());

        try (DockerMcpStdioRunner runner = new DockerMcpStdioRunner()) {
            var process = runner.start(spec, Map.of(), workspace);
            var containers = verificationClient.listContainersCmd().withShowAll(true)
                    .withLabelFilter(Map.of("com.agent.runtime.installation-id", installationId.toString())).exec();
            assertThat(containers).hasSize(1);
            String id = containers.getFirst().getId();
            var inspected = verificationClient.inspectContainerCmd(id).exec();
            assertThat(inspected.getConfig().getTty()).isFalse();
            assertThat(inspected.getConfig().getStdinOpen()).isTrue();
            assertThat(inspected.getHostConfig().getNetworkMode()).isEqualTo("none");
            assertThat(inspected.getHostConfig().getReadonlyRootfs()).isTrue();
            assertThat(inspected.getHostConfig().getPrivileged()).isFalse();
            assertThat(inspected.getConfig().getLabels())
                    .containsEntry("com.agent.runtime.managed", "true")
                    .containsEntry("com.agent.runtime.kind", "mcp")
                    .containsEntry("com.agent.runtime.snapshot-id", snapshotId.toString());
            assertThat(inspected.getMounts()).anySatisfy(mount -> {
                assertThat(mount.getDestination()).isEqualTo("/workspace");
                assertThat(mount.getMode()).isEqualTo("ro");
            });
            process.destroy();
            process.destroy();
            assertThat(verificationClient.listContainersCmd().withShowAll(true)
                    .withIdFilter(Set.of(id)).exec()).isEmpty();
        }
    }

    private static DockerClient createDockerClient() {
        DefaultDockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        DockerHttpClient http = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost()).sslConfig(config.getSSLConfig()).build();
        return DockerClientImpl.getInstance(config, http);
    }
}
