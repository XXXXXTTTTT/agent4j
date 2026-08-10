package com.agent.cli;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

/** Agent4J 服务端 REST/SSE 客户端端口。 */
public interface Agent4jClient {

    Actor identity();

    List<Workspace> listWorkspaces();

    Workspace createWorkspace(String displayName, String workspacePath, String repositoryId);

    List<Conversation> listConversations(UUID workspaceId);

    Conversation createConversation(UUID workspaceId);

    List<Turn> listTurns(UUID conversationId);

    Turn submitTurn(UUID conversationId, String content, String reviewerUrl);

    Run getRun(UUID runId);

    List<SseEventReader.SseEvent> readTrace(UUID runId);

    List<SseEventReader.SseEvent> readLogs(UUID runId);

    record Actor(String userId, String displayName) {
    }

    record Workspace(
            UUID workspaceId,
            String ownerUserId,
            String displayName,
            String workspacePath,
            String repositoryId,
            String permission,
            String createdAt,
            String updatedAt) {
    }

    record Conversation(
            UUID conversationId,
            UUID workspaceId,
            String createdBy,
            String title,
            String status,
            String createdAt,
            String updatedAt) {
    }

    record Turn(
            UUID turnId,
            UUID conversationId,
            long turnIndex,
            String userContent,
            String assistantContent,
            UUID runId,
            String status,
            String error,
            String createdAt,
            String completedAt) {
    }

    record Run(
            UUID runId,
            long version,
            String graphId,
            String status,
            JsonNode state,
            String nextNode,
            JsonNode interruptRequest,
            String error) {
    }
}
