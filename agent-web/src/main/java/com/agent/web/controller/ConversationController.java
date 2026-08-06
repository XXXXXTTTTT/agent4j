package com.agent.web.controller;

import com.agent.web.conversation.ConversationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 持久化会话和轮次 REST API。 */
@RestController
@RequestMapping("/api/conversations")
public final class ConversationController {

    private final ObjectProvider<ConversationService> conversationService;

    public ConversationController(ObjectProvider<ConversationService> conversationService) {
        this.conversationService = Objects.requireNonNull(
                conversationService, "conversationService 不能为空");
    }

    @GetMapping("/{conversationId}")
    public ConversationView get(@PathVariable UUID conversationId) {
        return ConversationView.from(service().getConversation(conversationId));
    }

    @GetMapping("/{conversationId}/turns")
    public List<ConversationTurnView> turns(@PathVariable UUID conversationId) {
        return service().listTurns(conversationId).stream()
                .map(ConversationTurnView::from)
                .toList();
    }

    @PostMapping("/{conversationId}/turns")
    public ResponseEntity<ConversationTurnView> submit(
            @PathVariable UUID conversationId,
            @Valid @RequestBody SubmitConversationTurnRequest request) {
        return ResponseEntity.accepted().body(ConversationTurnView.from(
                service().submitTurn(
                        conversationId, request.content(), request.reviewerUrl())));
    }

    @PostMapping("/{conversationId}/archive")
    public ConversationView archive(@PathVariable UUID conversationId) {
        return ConversationView.from(service().archive(conversationId));
    }

    private ConversationService service() {
        ConversationService service = conversationService.getIfAvailable();
        if (service == null) {
            throw new IllegalStateException("持久化会话服务未启用");
        }
        return service;
    }
}
