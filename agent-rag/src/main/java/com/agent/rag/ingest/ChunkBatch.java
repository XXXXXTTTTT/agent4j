package com.agent.rag.ingest;

import com.agent.rag.domain.ParentChunk;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

record ChunkBatch(List<ParentChunk> parents, List<ChildDraft> children) {

    ChunkBatch {
        parents = List.copyOf(Objects.requireNonNull(parents, "parents 不能为空"));
        children = List.copyOf(Objects.requireNonNull(children, "children 不能为空"));
    }
}

record ChildDraft(
        UUID childId,
        UUID parentId,
        String repositoryId,
        String path,
        String symbol,
        int ordinal,
        String content,
        int startLine,
        int endLine) {

    ChildDraft {
        Objects.requireNonNull(childId, "childId 不能为空");
        Objects.requireNonNull(parentId, "parentId 不能为空");
        Objects.requireNonNull(repositoryId, "repositoryId 不能为空");
        Objects.requireNonNull(path, "path 不能为空");
        Objects.requireNonNull(content, "content 不能为空");
        if (ordinal < 0 || startLine <= 0 || endLine < startLine) {
            throw new IllegalArgumentException("子块范围无效");
        }
    }
}
