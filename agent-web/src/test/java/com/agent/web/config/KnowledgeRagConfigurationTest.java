package com.agent.web.config;

import com.agent.core.context.Utf8TokenEstimator;
import com.agent.core.knowledge.KnowledgeContextProvider;
import com.agent.rag.knowledge.ProjectFileKnowledgeContextProvider;
import com.agent.rag.knowledge.IndexingKnowledgeContextProvider;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.time.Duration;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class KnowledgeRagConfigurationTest {

    @Test
    void choosesEmptyProviderWhenKnowledgeIsDisabled() {
        KnowledgeRagConfiguration configuration = new KnowledgeRagConfiguration();

        KnowledgeContextProvider provider = configuration.knowledgeContextProvider(
                new KnowledgeProperties(false, 4_000),
                new ProjectFileKnowledgeContextProvider(
                        new com.agent.rag.knowledge.ProjectKnowledgeCompiler(
                                new Utf8TokenEstimator()),
                        new Utf8TokenEstimator()));

        assertThat(provider).isSameAs(KnowledgeContextProvider.empty());
    }

    @Test
    void choosesProjectFileProviderWhenRagIsDisabled() {
        KnowledgeRagConfiguration configuration = new KnowledgeRagConfiguration();
        ProjectFileKnowledgeContextProvider fileProvider = new ProjectFileKnowledgeContextProvider(
                new com.agent.rag.knowledge.ProjectKnowledgeCompiler(new Utf8TokenEstimator()),
                new Utf8TokenEstimator());

        assertThat(configuration.knowledgeContextProvider(
                new KnowledgeProperties(true, 4_000), fileProvider))
                .isSameAs(fileProvider);
    }

    @Test
    void choosesIndexingProviderWhenKnowledgeAndRagAreEnabled() {
        KnowledgeRagConfiguration configuration = new KnowledgeRagConfiguration();
        ProjectFileKnowledgeContextProvider fileProvider = mock(
                ProjectFileKnowledgeContextProvider.class);
        IndexingKnowledgeContextProvider indexingProvider = mock(
                IndexingKnowledgeContextProvider.class);
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("indexingKnowledgeContextProvider", indexingProvider);

        KnowledgeContextProvider selected = configuration.productionKnowledgeContextProvider(
                new KnowledgeProperties(true, 4_000),
                new RagProperties(
                        true, "/v1/embeddings", "embed-test", true, true,
                        false, Duration.ofMinutes(5)),
                fileProvider,
                beanFactory.getBeanProvider(IndexingKnowledgeContextProvider.class));

        assertThat(selected).isSameAs(indexingProvider);
    }

    @Test
    void configuresRagFlywayInAnIndependentHistoryTableAndLocation() {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:postgresql://localhost:5432/agent", "agent", "secret");

        Flyway flyway = new KnowledgeRagConfiguration().ragFlyway(dataSource);

        assertThat(flyway.getConfiguration().getLocations())
                .extracting(location -> location.getDescriptor())
                .containsExactly("classpath:db/rag-migration");
        assertThat(flyway.getConfiguration().getTable())
                .isEqualTo("flyway_rag_schema_history");
    }
}
