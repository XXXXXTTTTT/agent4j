package com.agent.web.config;

import com.agent.core.context.TokenEstimator;
import com.agent.core.context.Utf8TokenEstimator;
import com.agent.core.knowledge.KnowledgeContextProvider;
import com.agent.core.llm.ModelRouter;
import com.agent.rag.embedding.EmbeddingModel;
import com.agent.rag.index.CodebaseIndexCoordinator;
import com.agent.rag.ingest.CodebaseIngestionService;
import com.agent.rag.ingest.RepositorySourceScanner;
import com.agent.rag.knowledge.IndexingKnowledgeContextProvider;
import com.agent.rag.knowledge.ProjectFileKnowledgeContextProvider;
import com.agent.rag.knowledge.ProjectKnowledgeCompiler;
import com.agent.rag.knowledge.RagKnowledgeContextProvider;
import com.agent.rag.pipeline.LexicalCoverageReranker;
import com.agent.rag.pipeline.ModelHypotheticalDocumentGenerator;
import com.agent.rag.pipeline.ModelQueryRewriter;
import com.agent.rag.pipeline.RagRetrievalPipeline;
import com.agent.rag.pipeline.RagRetrievalPolicy;
import com.agent.rag.search.HybridRagRetriever;
import com.agent.rag.store.JdbcRagStore;
import com.agent.rag.store.RagStore;
import com.agent.sandbox.ast.AstService;
import com.agent.web.rag.OpenAiEmbeddingModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationInitializer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.Objects;

/** 装配项目文件知识与可选 Codebase RAG 生产链。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
        KnowledgeProperties.class,
        RagProperties.class,
        ModelGatewayProperties.class
})
public class KnowledgeRagConfiguration {

    /** 创建确定性的知识 token 估算器。 */
    @Bean
    @ConditionalOnMissingBean(TokenEstimator.class)
    TokenEstimator knowledgeTokenEstimator() {
        return new Utf8TokenEstimator();
    }

    /** 创建项目规则文件编译器。 */
    @Bean
    ProjectKnowledgeCompiler projectKnowledgeCompiler(TokenEstimator tokenEstimator) {
        return new ProjectKnowledgeCompiler(tokenEstimator);
    }

    /** 创建不依赖数据库的文件知识 Provider。 */
    @Bean
    ProjectFileKnowledgeContextProvider projectFileKnowledgeContextProvider(
            ProjectKnowledgeCompiler compiler,
            TokenEstimator tokenEstimator) {
        return new ProjectFileKnowledgeContextProvider(compiler, tokenEstimator);
    }

    /** 根据总开关与 RAG 开关暴露唯一首选知识 Provider。 */
    @Bean
    @Primary
    KnowledgeContextProvider productionKnowledgeContextProvider(
            KnowledgeProperties knowledgeProperties,
            RagProperties ragProperties,
            ProjectFileKnowledgeContextProvider fileProvider,
            ObjectProvider<IndexingKnowledgeContextProvider> indexingProvider) {
        knowledgeProperties.validate();
        ragProperties.validate();
        if (!knowledgeProperties.enabled()) {
            return KnowledgeContextProvider.empty();
        }
        if (!ragProperties.enabled()) {
            return fileProvider;
        }
        return Objects.requireNonNull(
                indexingProvider.getIfAvailable(),
                "RAG 已启用但 IndexingKnowledgeContextProvider 不存在");
    }

    KnowledgeContextProvider knowledgeContextProvider(
            KnowledgeProperties properties,
            ProjectFileKnowledgeContextProvider fileProvider) {
        properties.validate();
        return properties.enabled() ? fileProvider : KnowledgeContextProvider.empty();
    }

    /** 使用共享超时 HTTP 客户端创建 OpenAI 兼容 Embedding。 */
    @Bean
    @ConditionalOnProperty(name = "agent.rag.enabled", havingValue = "true")
    EmbeddingModel codebaseEmbeddingModel(
            RagProperties ragProperties,
            ModelGatewayProperties llmProperties,
            ObjectMapper objectMapper,
            CloseableHttpClient modelGatewayHttpClient) {
        ragProperties.validate();
        llmProperties.validate();
        RestClient restClient = RestClient.builder()
                .baseUrl(llmProperties.baseUrl())
                .requestFactory(new HttpComponentsClientHttpRequestFactory(modelGatewayHttpClient))
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + llmProperties.apiKey())
                .build();
        return new OpenAiEmbeddingModel(
                restClient,
                objectMapper,
                ragProperties.embeddingsPath(),
                ragProperties.embeddingModel(),
                llmProperties.baseUrl() + ragProperties.embeddingsPath());
    }

    /** 启动独立 RAG 迁移，同时保留 Spring Boot 默认 Web Flyway。 */
    @Bean
    @ConditionalOnProperty(name = "agent.rag.enabled", havingValue = "true")
    FlywayMigrationInitializer ragFlywayMigrationInitializer(DataSource dataSource) {
        return new FlywayMigrationInitializer(ragFlyway(dataSource), Flyway::migrate);
    }

    Flyway ragFlyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(Objects.requireNonNull(dataSource, "dataSource 不能为空"))
                .locations("classpath:db/rag-migration")
                .table("flyway_rag_schema_history")
                .load();
    }

    /** 创建 PostgreSQL RAG 存储。 */
    @Bean
    @DependsOn("ragFlywayMigrationInitializer")
    @ConditionalOnProperty(name = "agent.rag.enabled", havingValue = "true")
    RagStore ragStore(DataSource dataSource) {
        return new JdbcRagStore(dataSource, Clock.systemUTC());
    }

    /** 创建三路混合代码召回器。 */
    @Bean
    @ConditionalOnProperty(name = "agent.rag.enabled", havingValue = "true")
    HybridRagRetriever hybridRagRetriever(
            RagStore ragStore,
            EmbeddingModel embeddingModel) {
        return new HybridRagRetriever(ragStore, embeddingModel);
    }

    /** 创建查询增强、召回、融合、精排和 token 门禁流水线。 */
    @Bean
    @ConditionalOnProperty(name = "agent.rag.enabled", havingValue = "true")
    RagRetrievalPipeline ragRetrievalPipeline(
            HybridRagRetriever retriever,
            EmbeddingModel embeddingModel,
            ModelRouter modelRouter,
            ObjectMapper objectMapper,
            TokenEstimator tokenEstimator) {
        return new RagRetrievalPipeline(
                retriever,
                embeddingModel,
                new ModelQueryRewriter(modelRouter, objectMapper),
                new ModelHypotheticalDocumentGenerator(modelRouter, objectMapper),
                new LexicalCoverageReranker(),
                tokenEstimator);
    }

    /** 创建项目规则与代码证据组合 Provider。 */
    @Bean
    @ConditionalOnProperty(name = "agent.rag.enabled", havingValue = "true")
    RagKnowledgeContextProvider ragKnowledgeContextProvider(
            ProjectKnowledgeCompiler compiler,
            RagRetrievalPipeline pipeline,
            RagProperties ragProperties,
            KnowledgeProperties knowledgeProperties,
            TokenEstimator tokenEstimator) {
        return new RagKnowledgeContextProvider(
                compiler,
                pipeline,
                new RagRetrievalPolicy(
                        ragProperties.rewriteEnabled() ? 3 : 1,
                        ragProperties.hydeEnabled(),
                        20,
                        8,
                        knowledgeProperties.maxTokens()),
                tokenEstimator,
                ragProperties.strict());
    }

    /** 创建同源快照 ingest 服务。 */
    @Bean
    @ConditionalOnProperty(name = "agent.rag.enabled", havingValue = "true")
    CodebaseIngestionService codebaseIngestionService(
            EmbeddingModel embeddingModel,
            RagStore ragStore) {
        return new CodebaseIngestionService(new AstService(), embeddingModel, ragStore);
    }

    /** 创建按 repositoryId 合并并发索引的虚拟线程协调器。 */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "agent.rag.enabled", havingValue = "true")
    CodebaseIndexCoordinator codebaseIndexCoordinator(
            CodebaseIngestionService ingestionService,
            RagStore ragStore) {
        return new CodebaseIndexCoordinator(
                new RepositorySourceScanner(), ingestionService, ragStore);
    }

    /** 创建带有界索引等待的生产知识 Provider。 */
    @Bean
    @ConditionalOnProperty(name = "agent.rag.enabled", havingValue = "true")
    IndexingKnowledgeContextProvider indexingKnowledgeContextProvider(
            CodebaseIndexCoordinator coordinator,
            RagKnowledgeContextProvider delegate,
            RagProperties properties) {
        return new IndexingKnowledgeContextProvider(
                coordinator, delegate, properties.indexTimeout());
    }
}
