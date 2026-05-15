package com.codeleon.ai.history;

import com.codeleon.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Read-only access to a room's persisted AI chat history.
 *
 * For AI-3a this returns just the caller's own conversation, which is
 * what an invited member ever needs. The owner-side override that lets
 * the room owner read another member's thread arrives in AI-3b.
 *
 * Privacy contract: an invited member can NEVER read another invited
 * member's chat — only their own. The reverse, the owner reading a
 * member's thread, is the explicit owner-only superpower delivered by
 * the AI-3b commit; until then everyone sees their own.
 */
@RestController
@RequestMapping("/rooms/{roomId}/chat/history")
@RequiredArgsConstructor
public class ChatHistoryController {

    private final RoomChatHistoryService historyService;

    @GetMapping
    public List<ChatHistoryMessage> listMyHistory(
            @PathVariable UUID roomId,
            @AuthenticationPrincipal User user
    ) {
        return historyService.listForCaller(roomId, user);
    }
}
