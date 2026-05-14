package com.codeleon.room.event;

import com.codeleon.room.Room;
import com.codeleon.room.RoomMember;
import com.codeleon.room.RoomMemberRepository;
import com.codeleon.room.RoomRepository;
import com.codeleon.user.User;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Single write path for the activity feed. Every interesting action in
 * the app calls {@link #emit} after it has succeeded; emit does two
 * things in one transaction:
 *   1. appends a RoomEvent row (the feed itself), and
 *   2. refreshes the room's denormalised lastEditedBy pointer so the
 *      dashboard listing can show "Last edited by X" without joining
 *      the events table per card.
 *
 * emit is deliberately forgiving: a feed write must never be the reason
 * a file rename or a code run fails for the user. Any failure is logged
 * and swallowed. Callers therefore do not need to wrap emit in their
 * own try/catch.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RoomEventService {

    /** Hard cap on a single feed page — keeps the dashboard poll cheap. */
    private static final int MAX_FEED_PAGE = 50;

    private final RoomEventRepository roomEventRepository;
    private final RoomRepository roomRepository;
    private final RoomMemberRepository roomMemberRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public void emit(UUID roomId, User user, RoomEventType type, Map<String, String> payload) {
        try {
            Room room = roomRepository.findById(roomId).orElse(null);
            if (room == null) {
                // Room vanished between the action and the feed write —
                // nothing meaningful to record, just drop it.
                return;
            }
            roomEventRepository.save(RoomEvent.builder()
                    .room(room)
                    .user(user)
                    .type(type.name())
                    .payload(serialize(payload))
                    .build());

            // Keep the denormalised pointer fresh. Saving the room also
            // bumps updatedAt via the @PreUpdate hook, which is what the
            // dashboard's "recent" sort already keys on.
            room.setLastEditedBy(user);
            roomRepository.save(room);
        } catch (RuntimeException ex) {
            log.warn("Failed to emit room event type={} room={}: {}", type, roomId, ex.getMessage());
        }
    }

    /** Convenience overload for events that carry no payload. */
    @Transactional
    public void emit(UUID roomId, User user, RoomEventType type) {
        emit(roomId, user, type, null);
    }

    /**
     * Cross-room activity feed for the dashboard sidebar: the newest
     * events from every room the user is a member of. When {@code since}
     * is non-null the result is limited to events strictly after it, so
     * the frontend can poll for deltas instead of re-pulling the page.
     */
    @Transactional(readOnly = true)
    public List<RoomEventResponse> listForUser(User user, Instant since) {
        List<UUID> roomIds = roomMemberRepository.findByUser(user).stream()
                .map(RoomMember::getRoom)
                .map(Room::getId)
                .toList();
        if (roomIds.isEmpty()) {
            return List.of();
        }
        var page = PageRequest.of(0, MAX_FEED_PAGE);
        List<RoomEvent> events = since != null
                ? roomEventRepository.findByRoomIdInAndCreatedAtAfterOrderByCreatedAtDesc(roomIds, since, page)
                : roomEventRepository.findByRoomIdInOrderByCreatedAtDesc(roomIds, page);
        return events.stream()
                .map(event -> RoomEventResponse.of(event, objectMapper))
                .toList();
    }

    private String serialize(Map<String, String> payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            log.warn("Failed to serialize room event payload: {}", ex.getMessage());
            return null;
        }
    }
}
