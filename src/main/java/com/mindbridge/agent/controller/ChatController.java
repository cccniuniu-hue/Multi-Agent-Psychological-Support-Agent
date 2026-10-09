package com.mindbridge.agent.controller;

import com.mindbridge.agent.dto.ApiMessage;
import com.mindbridge.agent.dto.ChatRequest;
import com.mindbridge.agent.dto.ChatStreamEvent;
import com.mindbridge.agent.security.CurrentUser;
import com.mindbridge.agent.service.ChatService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/chat")
/**
 * 学生聊天接口。
 *
 * <p>只允许学生账号发起对话，返回 SSE 流式事件供前端逐字显示。</p>
 */
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ChatStreamEvent>> stream(
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody ChatRequest request
    ) {
        requireStudent(currentUser);
        return chatService.streamChat(currentUser.getId(), request);
    }

    @PostMapping("/sessions/{sessionId}/end")
    public Mono<ApiMessage> endSession(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable String sessionId
    ) {
        requireStudent(currentUser);
        return Mono.fromCallable(() -> {
            chatService.endSession(currentUser.getId(), sessionId);
            return new ApiMessage("会话已整理，可以开始新会话。");
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private void requireStudent(CurrentUser currentUser) {
        // 管理员后台只用于查看记录和工具状态，不能以管理员身份生成学生对话。
        boolean isAdmin = currentUser.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
        if (isAdmin) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "管理员账号只能查看后台记录，不能操作学生对话。");
        }
    }
}
