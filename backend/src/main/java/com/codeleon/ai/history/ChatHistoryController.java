package com.codeleon.ai.history;

import com.codeleon.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Read-only access to a room's persisted AI chat history.
 *
 * Two privacy levels enforced by {@link RoomChatHistoryService}:
 *   - Any room member can fetch /chat/history (their own thread) and
 *     gets exactly their own messages back.
 *   - The room owner can additionally pass ?userId=X to fetch another
 *     member's thread (the disclosure label in ChatPanel tells members
 *     this is possible), and can call /chat/threads to list every
 *     author. Non-owners passing a foreign ?userId get 403.
 */
@RestController
@RequestMapping("/rooms/{roomId}/chat")
@RequiredArgsConstructor
public class ChatHistoryController {

    private final RoomChatHistoryService historyService;

    @GetMapping("/history")
    public List<ChatHistoryMessage> listHistory(
            @PathVariable UUID roomId,
            @AuthenticationPrincipal User user,
            @RequestParam(name = "userId", required = false) UUID targetUserId
    ) {
        if (targetUserId == null) {
            return historyService.listForCaller(roomId, user);
        }
        return historyService.listForUser(roomId, user, targetUserId);
    }

    @GetMapping("/threads")
    public List<ChatThreadSummary> listThreads(
            @PathVariable UUID roomId,
            @AuthenticationPrincipal User user
    ) {
        return historyService.listThreads(roomId, user);
    }
}
