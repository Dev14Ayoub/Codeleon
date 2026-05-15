package com.codeleon.ai.history;

import com.codeleon.common.exception.NotFoundException;
import com.codeleon.room.Room;
import com.codeleon.room.RoomFileService;
import com.codeleon.room.RoomRepository;
import com.codeleon.user.User;
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
     * The owner-side review path that lists other members' threads
     * lands in a follow-up commit (AI-3b).
     */
    @Transactional(readOnly = true)
    public List<ChatHistoryMessage> listForCaller(UUID roomId, User caller) {
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new NotFoundException("Room not found"));
        if (!roomFileService.canRead(roomId, caller)) {
            throw new NotFoundException("Room not found");
        }
        return repository.findByRoomAndUserOrderByCreatedAtAsc(room, caller)
                .stream()
                .map(ChatHistoryMessage::of)
                .toList();
    }
}
