package com.agent.web.persistence;

import com.agent.web.capability.InstallationScope;
import com.agent.web.mcp.catalog.OfficialMcpServerRecord;
import com.agent.web.mcp.installation.McpInstallationRecord;
import com.agent.web.mcp.installation.McpInstallationStatus;
import com.agent.web.mcp.installation.McpSourceSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcMcpInstallationRepositoryTest {
    @Test
    void repositoryPortCanBeConstructedWithJdbcDependencies() {
        var database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).build();
        var repository = new JdbcMcpInstallationRepository(
                JdbcClient.create(database),
                new TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(database)),
                new ObjectMapper());
        assertThat(repository).isNotNull();
        database.shutdown();
    }
}
