package com.mindbridge.agent.controller;

import com.mindbridge.agent.dto.UserMemoryItemResponse;
import com.mindbridge.agent.security.CurrentUser;
import com.mindbridge.agent.service.memory.UserProfileMemoryService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/profile/memory")
/**
 * 当前用户画像记忆接口。
 */
public class UserMemoryController {

    private final UserProfileMemoryService userProfileMemoryService;

    public UserMemoryController(UserProfileMemoryService userProfileMemoryService) {
        this.userProfileMemoryService = userProfileMemoryService;
    }

    @GetMapping
    public Mono<List<UserMemoryItemResponse>> memories(@AuthenticationPrincipal CurrentUser currentUser) {
        return Mono.fromCallable(() -> userProfileMemoryService.memoriesForUser(currentUser.getId()).stream()
                .map(UserMemoryItemResponse::from)
                .toList()).subscribeOn(Schedulers.boundedElastic());
    }

    @DeleteMapping("/{memoryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable Long memoryId
    ) {
        return Mono.<Void>fromRunnable(() -> userProfileMemoryService.deleteMemory(currentUser.getId(), memoryId))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
