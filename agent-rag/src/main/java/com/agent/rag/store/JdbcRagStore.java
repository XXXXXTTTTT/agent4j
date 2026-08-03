package com.agent.rag.store;

import com.agent.rag.domain.ChildChunk;
import com.agent.rag.domain.ParentChunk;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 基于 Spring JDBC 的 PostgreSQL RAG 存储。 */
public final class JdbcRagStore implements RagStore {

    private static final String SELECT_ROWS = """
            select c.child_id, c.parent_id, c.repository_id, c.path, c.symbol,
                   c.ordinal, c.content, c.start_line, c.end_line, c.embedding::text,
                   p.parent_id as p_parent_id, p.repository_id as p_repository_id,
                   p.path as p_path, p.symbol as p_symbol, p.content as p_content,
                   p.start_line as p_start_line, p.end_line as p_end_line,
                   p.metadata_json::text as p_metadata_json
            from rag_child_chunks c
            join rag_parent_chunks p on p.parent_id = c.parent_id
            """;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    /** 创建不拥有 DataSource 生命周期的 JDBC 存储。 */
    public JdbcRagStore(DataSource dataSource, Clock clock) {
        Objects.requireNonNull(dataSource, "dataSource 不能为空");
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.transactionTemplate = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
    }

    @Override
    public void replaceRepository(
            String repositoryId,
            List<ParentChunk> parents,
            List<ChildChunk> children) {
        requireRepositoryId(repositoryId);
        Objects.requireNonNull(parents, "parents 不能为空");
        Objects.requireNonNull(children, "children 不能为空");
        try {
            transactionTemplate.executeWithoutResult(status -> {
                jdbcTemplate.update(
                        "delete from rag_child_chunks where repository_id = ?", repositoryId);
                jdbcTemplate.update(
                        "delete from rag_parent_chunks where repository_id = ?", repositoryId);
                Timestamp createdAt = Timestamp.from(clock.instant());
                for (ParentChunk parent : parents) {
                    if (!repositoryId.equals(parent.repositoryId())) {
                        throw new IllegalArgumentException("父块 repositoryId 不一致");
                    }
                    jdbcTemplate.update("""
                            insert into rag_parent_chunks(
                                parent_id, repository_id, path, symbol, content,
                                start_line, end_line, metadata_json, created_at)
                            values (?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?)
                            """,
                            parent.parentId(), parent.repositoryId(), parent.path(),
                            parent.symbol(), parent.content(), parent.startLine(),
                            parent.endLine(), parent.metadataJson(), createdAt);
                }
                for (ChildChunk child : children) {
                    if (!repositoryId.equals(child.repositoryId())) {
                        throw new IllegalArgumentException("子块 repositoryId 不一致");
                    }
                    jdbcTemplate.update("""
                            insert into rag_child_chunks(
                                child_id, parent_id, repository_id, path, symbol,
                                ordinal, content, start_line, end_line, embedding, created_at)
                            values (?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as vector), ?)
                            """,
                            child.childId(), child.parentId(), child.repositoryId(), child.path(),
                            child.symbol(), child.ordinal(), child.content(), child.startLine(),
                            child.endLine(), vectorLiteral(child.embedding()), createdAt);
                }
            });
        } catch (RagStoreException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new RagStoreException("替换 RAG 索引失败: " + repositoryId, exception);
        }
    }

    @Override
    public List<RetrievalRow> findByVector(
            String repositoryId, float[] queryEmbedding, int limit) {
        requireRepositoryId(repositoryId);
        validateEmbedding(queryEmbedding);
        validateLimit(limit);
        try {
            return jdbcTemplate.query("""
                    with query_vector as (select cast(? as vector) as value)
                    """ + SELECT_ROWS.replace(
                            "select c.child_id",
                            "select greatest(0.0, 1 - (c.embedding <=> q.value)) "
                                    + "as retrieval_score, c.child_id") + """
                    cross join query_vector q
                    where c.repository_id = ?
                    order by c.embedding <=> q.value
                    limit ?
                    """, this::mapRow, vectorLiteral(queryEmbedding), repositoryId, limit);
        } catch (DataAccessException exception) {
            throw new RagStoreException("向量召回失败: " + repositoryId, exception);
        }
    }

    @Override
    public List<RetrievalRow> findByLexical(
            String repositoryId, String query, int limit) {
        requireRepositoryId(repositoryId);
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query 不能为空");
        }
        validateLimit(limit);
        try {
            return jdbcTemplate.query(SELECT_ROWS.replace(
                            "select c.child_id",
                            "select 1.0::double precision as retrieval_score, c.child_id") + """
                    where c.repository_id = ?
                      and c.search_vector @@ websearch_to_tsquery('simple', ?)
                    order by ts_rank_cd(c.search_vector,
                        websearch_to_tsquery('simple', ?)) desc,
                        c.path asc, c.ordinal asc, c.child_id asc
                    limit ?
                    """, this::mapRow, repositoryId, query, query, limit);
        } catch (DataAccessException exception) {
            throw new RagStoreException("词法召回失败: " + repositoryId, exception);
        }
    }

    @Override
    public long countChildren(String repositoryId) {
        requireRepositoryId(repositoryId);
        try {
            Long count = jdbcTemplate.queryForObject(
                    "select count(*) from rag_child_chunks where repository_id = ?",
                    Long.class, repositoryId);
            return Objects.requireNonNull(count);
        } catch (DataAccessException exception) {
            throw new RagStoreException("统计子块数量失败: " + repositoryId, exception);
        }
    }

    @Override
    public double averageDocumentLength(String repositoryId) {
        requireRepositoryId(repositoryId);
        try {
            Double average = jdbcTemplate.queryForObject("""
                    select coalesce(avg(token_count), 0)::double precision
                    from (
                        select c.child_id,
                               count(nullif(t.token, ''))::double precision as token_count
                        from rag_child_chunks c
                        left join lateral regexp_split_to_table(
                            lower(c.content), '[^[:alnum:]]+') t(token) on true
                        where c.repository_id = ?
                        group by c.child_id
                    ) lengths
                    """, Double.class, repositoryId);
            return Objects.requireNonNull(average);
        } catch (DataAccessException exception) {
            throw new RagStoreException("统计平均文档长度失败: " + repositoryId, exception);
        }
    }

    @Override
    public Map<String, Long> documentFrequencies(String repositoryId, List<String> terms) {
        requireRepositoryId(repositoryId);
        Objects.requireNonNull(terms, "terms 不能为空");
        Map<String, Long> frequencies = new LinkedHashMap<>();
        try {
            for (String term : terms) {
                if (term == null || term.isBlank()) {
                    continue;
                }
                Long frequency = jdbcTemplate.queryForObject("""
                        select count(*)
                        from rag_child_chunks
                        where repository_id = ?
                          and search_vector @@ plainto_tsquery('simple', ?)
                        """, Long.class, repositoryId, term);
                frequencies.put(term, Objects.requireNonNull(frequency));
            }
            return Map.copyOf(frequencies);
        } catch (DataAccessException exception) {
            throw new RagStoreException("统计文档频率失败: " + repositoryId, exception);
        }
    }

    List<String> tableNames() {
        return jdbcTemplate.queryForList("""
                select table_name from information_schema.tables
                where table_schema = 'public'
                  and table_name like 'rag_%_chunks'
                order by table_name
                """, String.class);
    }

    List<String> indexNames() {
        return jdbcTemplate.queryForList("""
                select indexname from pg_indexes
                where schemaname = 'public' and tablename = 'rag_child_chunks'
                """, String.class);
    }

    int vectorDimension() {
        return jdbcTemplate.queryForObject("""
                select atttypmod
                from pg_attribute
                where attrelid = 'rag_child_chunks'::regclass
                  and attname = 'embedding'
                """, Integer.class);
    }

    private RetrievalRow mapRow(ResultSet resultSet, int rowNumber) throws SQLException {
        ParentChunk parent = new ParentChunk(
                resultSet.getObject("p_parent_id", UUID.class),
                resultSet.getString("p_repository_id"),
                resultSet.getString("p_path"),
                resultSet.getString("p_symbol"),
                resultSet.getString("p_content"),
                resultSet.getInt("p_start_line"),
                resultSet.getInt("p_end_line"),
                resultSet.getString("p_metadata_json"));
        ChildChunk child = new ChildChunk(
                resultSet.getObject("child_id", UUID.class),
                resultSet.getObject("parent_id", UUID.class),
                resultSet.getString("repository_id"),
                resultSet.getString("path"),
                resultSet.getString("symbol"),
                resultSet.getInt("ordinal"),
                resultSet.getString("content"),
                resultSet.getInt("start_line"),
                resultSet.getInt("end_line"),
                parseVector(resultSet.getString("embedding")));
        return new RetrievalRow(child, parent, resultSet.getDouble("retrieval_score"));
    }

    private float[] parseVector(String value) {
        if (value == null || value.length() < 2
                || value.charAt(0) != '[' || value.charAt(value.length() - 1) != ']') {
            throw new RagStoreException("数据库向量格式无效", null);
        }
        String body = value.substring(1, value.length() - 1);
        String[] parts = body.split(",", -1);
        if (parts.length != ChildChunk.EMBEDDING_DIMENSIONS) {
            throw new RagStoreException("数据库向量维度无效", null);
        }
        float[] embedding = new float[parts.length];
        try {
            for (int index = 0; index < parts.length; index++) {
                embedding[index] = Float.parseFloat(parts[index].trim());
            }
            return embedding;
        } catch (NumberFormatException exception) {
            throw new RagStoreException("数据库向量格式无效", exception);
        }
    }

    private String vectorLiteral(float[] embedding) {
        validateEmbedding(embedding);
        StringBuilder literal = new StringBuilder("[");
        for (int index = 0; index < embedding.length; index++) {
            if (index > 0) {
                literal.append(',');
            }
            literal.append(Float.toString(embedding[index]));
        }
        return literal.append(']').toString();
    }

    private void validateEmbedding(float[] embedding) {
        if (embedding == null || embedding.length != ChildChunk.EMBEDDING_DIMENSIONS) {
            throw new IllegalArgumentException("embedding 维度必须为 8");
        }
    }

    private void validateLimit(int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit 必须在 1 到 100 之间");
        }
    }

    private void requireRepositoryId(String repositoryId) {
        if (repositoryId == null || repositoryId.isBlank()) {
            throw new IllegalArgumentException("repositoryId 不能为空");
        }
    }
}
