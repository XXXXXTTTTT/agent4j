package com.agent.rag.memory;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.UUID;

/** 基于 Spring JDBC 的 PostgreSQL 长期记忆存储。 */
public final class JdbcMemoryStore implements MemoryStore {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    /** 创建不拥有 DataSource 生命周期的 JDBC 存储。 */
    public JdbcMemoryStore(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource 不能为空");
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.transactionTemplate = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
    }

    @Override
    public List<MemoryEntry> upsertAll(List<MemoryEntry> entries) {
        Objects.requireNonNull(entries, "entries 不能为空");
        try {
            return Objects.requireNonNull(transactionTemplate.execute(status -> {
                List<MemoryEntry> saved = new ArrayList<>(entries.size());
                for (MemoryEntry entry : entries) {
                    saved.add(upsert(entry));
                }
                return List.copyOf(saved);
            }));
        } catch (MemoryStoreException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MemoryStoreException("批量保存长期记忆失败", exception);
        }
    }

    @Override
    public List<MemoryRetrievalRow> findByVector(
            MemoryQuery query, float[] queryEmbedding, int limit) {
        Objects.requireNonNull(query, "query 不能为空");
        validateEmbedding(queryEmbedding);
        validateLimit(limit);
        try {
            TypeFilter filter = typeFilter(query);
            String sql = "with query_vector as (select cast(? as vector) as value) "
                    + "select greatest(0.0, 1 - (m.embedding <=> q.value)) as retrieval_score, "
                    + "m.memory_id, m.repository_id, m.user_id, m.memory_type, "
                    + "m.title, m.content, m.content_hash, m.embedding::text, "
                    + "m.created_at, m.updated_at "
                    + "from rag_memories m cross join query_vector q "
                    + "where m.repository_id = ? and m.user_id = ? "
                    + "and m.memory_type in (" + filter.placeholders() + ") "
                    + "order by m.embedding <=> q.value, m.updated_at desc, m.memory_id asc "
                    + "limit ?";
            List<Object> parameters = new ArrayList<>();
            parameters.add(vectorLiteral(queryEmbedding));
            parameters.add(query.repositoryId());
            parameters.add(query.userId());
            parameters.addAll(filter.values());
            parameters.add(limit);
            return jdbcTemplate.query(sql, this::mapRow, parameters.toArray());
        } catch (DataAccessException exception) {
            throw new MemoryStoreException("长期记忆向量召回失败", exception);
        }
    }

    @Override
    public List<MemoryRetrievalRow> findByLexical(MemoryQuery query, int limit) {
        Objects.requireNonNull(query, "query 不能为空");
        validateLimit(limit);
        try {
            TypeFilter filter = typeFilter(query);
            String sql = "select ts_rank_cd(m.search_vector, "
                    + "websearch_to_tsquery('simple', ?))::double precision as retrieval_score, "
                    + "m.memory_id, m.repository_id, m.user_id, m.memory_type, "
                    + "m.title, m.content, m.content_hash, m.embedding::text, "
                    + "m.created_at, m.updated_at "
                    + "from rag_memories m "
                    + "where m.repository_id = ? and m.user_id = ? "
                    + "and m.memory_type in (" + filter.placeholders() + ") "
                    + "and m.search_vector @@ websearch_to_tsquery('simple', ?) "
                    + "order by retrieval_score desc, m.updated_at desc, m.memory_id asc "
                    + "limit ?";
            List<Object> parameters = new ArrayList<>();
            parameters.add(query.query());
            parameters.add(query.repositoryId());
            parameters.add(query.userId());
            parameters.addAll(filter.values());
            parameters.add(query.query());
            parameters.add(limit);
            return jdbcTemplate.query(sql, this::mapRow, parameters.toArray());
        } catch (DataAccessException exception) {
            throw new MemoryStoreException("长期记忆词法召回失败", exception);
        }
    }

    List<String> tableNames() {
        return jdbcTemplate.queryForList("""
                select table_name from information_schema.tables
                where table_schema = 'public' and table_name = 'rag_memories'
                order by table_name
                """, String.class);
    }

    List<String> indexNames() {
        return jdbcTemplate.queryForList("""
                select indexname from pg_indexes
                where schemaname = 'public' and tablename = 'rag_memories'
                """, String.class);
    }

    int vectorDimension() {
        return jdbcTemplate.queryForObject("""
                select atttypmod
                from pg_attribute
                where attrelid = 'rag_memories'::regclass
                  and attname = 'embedding'
                """, Integer.class);
    }

    private MemoryEntry upsert(MemoryEntry entry) {
        return jdbcTemplate.queryForObject("""
                insert into rag_memories(
                    memory_id, repository_id, user_id, memory_type, title, content,
                    content_hash, embedding, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, cast(? as vector), ?, ?)
                on conflict (repository_id, user_id, memory_type, content_hash)
                do update set title = excluded.title,
                              content = excluded.content,
                              embedding = excluded.embedding,
                              updated_at = excluded.updated_at
                returning memory_id, repository_id, user_id, memory_type, title, content,
                          content_hash, embedding::text, created_at, updated_at
                """, this::mapEntry,
                entry.memoryId(), entry.repositoryId(), entry.userId(), entry.type().name(),
                entry.title(), entry.content(), entry.contentHash(), vectorLiteral(entry.embedding()),
                Timestamp.from(entry.createdAt()), Timestamp.from(entry.updatedAt()));
    }

    private MemoryRetrievalRow mapRow(ResultSet resultSet, int rowNumber) throws SQLException {
        return new MemoryRetrievalRow(mapEntry(resultSet), resultSet.getDouble("retrieval_score"));
    }

    private MemoryEntry mapEntry(ResultSet resultSet, int rowNumber) throws SQLException {
        return mapEntry(resultSet);
    }

    private MemoryEntry mapEntry(ResultSet resultSet) throws SQLException {
        return new MemoryEntry(
                resultSet.getObject("memory_id", UUID.class),
                resultSet.getString("repository_id"),
                resultSet.getString("user_id"),
                MemoryType.valueOf(resultSet.getString("memory_type")),
                resultSet.getString("title"),
                resultSet.getString("content"),
                resultSet.getString("content_hash"),
                parseVector(resultSet.getString("embedding")),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant());
    }

    private TypeFilter typeFilter(MemoryQuery query) {
        List<MemoryType> types = query.types().stream()
                .sorted()
                .toList();
        StringJoiner placeholders = new StringJoiner(", ");
        types.forEach(type -> placeholders.add("?"));
        return new TypeFilter(placeholders.toString(), types.stream().map(MemoryType::name).toList());
    }

    private float[] parseVector(String value) {
        if (value == null || value.length() < 2
                || value.charAt(0) != '[' || value.charAt(value.length() - 1) != ']') {
            throw new MemoryStoreException("数据库 embedding 格式无效", null);
        }
        String[] parts = value.substring(1, value.length() - 1).split(",", -1);
        if (parts.length != 8) {
            throw new MemoryStoreException("数据库 embedding 维度无效", null);
        }
        float[] embedding = new float[8];
        try {
            for (int index = 0; index < parts.length; index++) {
                embedding[index] = Float.parseFloat(parts[index].trim());
            }
            return embedding;
        } catch (NumberFormatException exception) {
            throw new MemoryStoreException("数据库 embedding 格式无效", exception);
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
        if (embedding == null || embedding.length != 8) {
            throw new IllegalArgumentException("embedding 维度必须为 8");
        }
        for (float element : embedding) {
            if (!Float.isFinite(element)) {
                throw new IllegalArgumentException("embedding 必须只包含有限数");
            }
        }
    }

    private void validateLimit(int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit 必须在 1 到 100 之间");
        }
    }

    private record TypeFilter(String placeholders, List<String> values) {
    }
}
