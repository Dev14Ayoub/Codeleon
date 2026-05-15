package com.codeleon.ai.history;

import com.codeleon.common.exception.ForbiddenException;
import com.codeleon.common.exception.NotFoundException;
import com.codeleon.room.Room;
import com.codeleon.room.RoomFileService;
import com.codeleon.room.RoomRepository;
import com.codeleon.user.User;
import com.codeleon.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Single read/write entry point for persisted AI chat history. Lives
 * separately from RoomChatService so that streaming logic and database
 * persistence stay decoupled — streamChat just calls {@link #record}
 * twice per turn and forgets, exactly the way it calls RoomEventService.
 *
 * Writes are failure-swallowing: a database hiccup must never be the
 * reason a user's chat answer disappears. The conversation streams
 * regardless; we log the persistence failure and move on.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RoomChatHistoryService {

    private final RoomRepository roomRepository;
    private final RoomChatMessageRepository repository;
    private final RoomFileService roomFileService;
    private final UserRepository userRepository;

    /**
     * Persists one turn of a conversation. {@code user} is the asker for
     * both USER and ASSISTANT roles — the assistant's reply is tagged
     * with the same user so an indexed fetch by (room, user) returns the
     * full thread.
     */
    @Transactional
    public void record(UUID roomId, User user, RoomChatRole role, String content) {
        if (content == null || content.isEmpty()) {
            return;
        }
        try {
            Room room = roomRepository.findById(roomId).orElse(null);
            if (room == null) {
                return;
            }
            repository.save(RoomChatMessage.builder()
                    .room(room)
                    .user(user)
                    .role(role)
                    .content(content)
                    .build());
        } catch (RuntimeException ex) {
            log.warn("Failed to persist chat message role={} room={}: {}", role, roomId, ex.getMessage());
        }
    }

    /**
     * Returns the caller's own conversation in this room, chronological.
     * For the owner-side review of another member's thread, see
     * {@link #listForUser}.
     */
    @Transactional(readOnly = true)
    public List<ChatHistoryMessage> listForCaller(UUID roomId, User caller) {
        Room room = mustRead(roomId, caller);
        return repository.findByRoomAndUserOrderByCreatedAtAsc(room, caller)
                .stream()
                .map(ChatHistoryMessage::of)
                .toList();
    }

    /**
     * Returns the conversation owned by {@code targetUserId} in this
     * room. Privacy contract: the caller may only read their own
     * thread unless they are the room owner, in which case they may
     * read any member's thread. Any other combination is a 403.
     */
    @Transactional(readOnly = true)
    public List<ChatHistoryMessage> listForUser(UUID roomId, User caller, UUID targetUserId) {
        Room room = mustRead(roomId, caller);
        boolean isOwner = room.getOwner().getId().equals(caller.getId());
        boolean self = caller.getId().equals(targetUserId);
        if (!self && !isOwner) {
            throw new ForbiddenException("Only the room owner can read another member's chat");
        }
        User target = self ? caller : userRepository.findById(targetUserId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        return repository.findByRoomAndUserOrderByCreatedAtAsc(room, target)
                .stream()
                .map(ChatHistoryMessage::of)
                .toList();
    }

    /**
     * Lists the members who have written in this room — used by the
     * owner's "review someone's chat" picker. Owner-only.
     */
    @Transactional(readOnly = true)
    public List<ChatThreadSummary> listThreads(UUID roomId, User caller) {
        Room room = mustRead(roomId, caller);
        if (!room.getOwner().getId().equals(caller.getId())) {
            throw new ForbiddenException("Only the room owner can browse member chats");
        }
        return repository.findThreadsByRoom(room);
    }

    private Room mustRead(UUID roomId, User caller) {
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new NotFoundException("Room not found"));
        if (!roomFileService.canRead(roomId, caller)) {
            throw new NotFoundException("Room not found");
        }
        return room;
    }
}
