package com.codeleon.room;

import com.codeleon.common.exception.BadRequestException;
import com.codeleon.common.exception.ForbiddenException;
import com.codeleon.common.exception.NotFoundException;
import com.codeleon.room.dto.CreateRoomRequest;
import com.codeleon.room.dto.RoomResponse;
import com.codeleon.room.enums.RoomMemberRole;
import com.codeleon.room.enums.RoomVisibility;
import com.codeleon.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RoomService {

    private final RoomRepository roomRepository;
    private final RoomMemberRepository roomMemberRepository;
    private final RoomFileRepository roomFileRepository;
    private final RoomPinRepository roomPinRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional
    public RoomResponse createRoom(CreateRoomRequest request, User owner) {
        Room room = Room.builder()
                .name(request.name().trim())
                .description(normalizeDescription(request.description()))
                .visibility(request.visibility())
                .inviteCode(generateUniqueInviteCode())
                .owner(owner)
                .build();

        Room savedRoom = roomRepository.save(room);
        roomMemberRepository.save(RoomMember.builder()
                .room(savedRoom)
                .user(owner)
                .role(RoomMemberRole.OWNER)
                .build());

        return toResponse(savedRoom, RoomMemberRole.OWNER, false);
    }

    /**
     * Returns the rooms the user is a member of. Pinned rooms come first
     * (newest pin on top), then unpinned rooms ordered by most recent join.
     * Archived rooms are excluded unless includeArchived is true — the
     * dashboard's "Archived" filter is the only path that opts in.
     */
    @Transactional(readOnly = true)
    public List<RoomResponse> getMyRooms(User user, boolean includeArchived) {
        Set<UUID> pinnedIds = roomPinRepository.findPinnedRoomIds(user);
        return roomMemberRepository.findByUserOrderByJoinedAtDesc(user)
                .stream()
                .filter(member -> includeArchived || member.getRoom().getArchivedAt() == null)
                .sorted(Comparator
                        .comparing((RoomMember m) -> !pinnedIds.contains(m.getRoom().getId()))
                        .thenComparing(m -> m.getRoom().getUpdatedAt(), Comparator.reverseOrder()))
                .map(member -> toResponse(member.getRoom(), member.getRole(), pinnedIds.contains(member.getRoom().getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<RoomResponse> getPublicRooms(User user) {
        Set<UUID> pinnedIds = roomPinRepository.findPinnedRoomIds(user);
        return roomRepository.findByVisibilityOrderByCreatedAtDesc(RoomVisibility.PUBLIC)
                .stream()
                .filter(room -> room.getArchivedAt() == null)
                .map(room -> toResponse(room, findRole(room, user), pinnedIds.contains(room.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public RoomResponse getRoom(UUID roomId, User user) {
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new NotFoundException("Room not found"));
        RoomMemberRole role = findRole(room, user);
        if (room.getVisibility() == RoomVisibility.PRIVATE && role == null) {
            throw new NotFoundException("Room not found");
        }
        boolean pinned = roomPinRepository.existsByUserAndRoom(user, room);
        return toResponse(room, role, pinned);
    }

    @Transactional
    public RoomResponse joinByInviteCode(String inviteCode, User user) {
        Room room = roomRepository.findByInviteCode(inviteCode)
                .orElseThrow(() -> new NotFoundException("Invalid invite code"));

        boolean pinned = roomPinRepository.existsByUserAndRoom(user, room);
        return roomMemberRepository.findByRoomAndUser(room, user)
                .map(member -> toResponse(room, member.getRole(), pinned))
                .orElseGet(() -> {
                    RoomMember member = roomMemberRepository.save(RoomMember.builder()
                            .room(room)
                            .user(user)
                            .role(RoomMemberRole.EDITOR)
                            .build());
                    return toResponse(room, member.getRole(), pinned);
                });
    }

    @Transactional
    public void pinRoom(UUID roomId, User user) {
        Room room = mustReadRoom(roomId, user);
        if (roomPinRepository.existsByUserAndRoom(user, room)) {
            return;
        }
        roomPinRepository.save(RoomPin.builder()
                .user(user)
                .room(room)
                .pinnedAt(Instant.now())
                .build());
    }

    @Transactional
    public void unpinRoom(UUID roomId, User user) {
        Room room = mustReadRoom(roomId, user);
        roomPinRepository.deleteByUserAndRoom(user, room);
    }

    @Transactional
    public RoomResponse archiveRoom(UUID roomId, User user) {
        Room room = mustOwnRoom(roomId, user);
        if (room.getArchivedAt() == null) {
            room.setArchivedAt(Instant.now());
            roomRepository.save(room);
        }
        return toResponse(room, findRole(room, user), roomPinRepository.existsByUserAndRoom(user, room));
    }

    @Transactional
    public RoomResponse unarchiveRoom(UUID roomId, User user) {
        Room room = mustOwnRoom(roomId, user);
        if (room.getArchivedAt() != null) {
            room.setArchivedAt(null);
            roomRepository.save(room);
        }
        return toResponse(room, findRole(room, user), roomPinRepository.existsByUserAndRoom(user, room));
    }

    private Room mustReadRoom(UUID roomId, User user) {
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new NotFoundException("Room not found"));
        RoomMemberRole role = findRole(room, user);
        if (role == null && room.getVisibility() == RoomVisibility.PRIVATE) {
            throw new NotFoundException("Room not found");
        }
        return room;
    }

    private Room mustOwnRoom(UUID roomId, User user) {
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new NotFoundException("Room not found"));
        if (!room.getOwner().getId().equals(user.getId())) {
            throw new ForbiddenException("Only the room owner can archive or unarchive");
        }
        return room;
    }

    private RoomMemberRole findRole(Room room, User user) {
        return roomMemberRepository.findByRoomAndUser(room, user)
                .map(RoomMember::getRole)
                .orElse(null);
    }

    private RoomResponse toResponse(Room room, RoomMemberRole currentUserRole, boolean pinned) {
        long fileCount = roomFileRepository.countByRoom(room);
        long memberCount = roomMemberRepository.countByRoom(room);
        return new RoomResponse(
                room.getId(),
                room.getName(),
                room.getDescription(),
                room.getVisibility(),
                room.getInviteCode(),
                room.getOwner().getId(),
                room.getOwner().getFullName(),
                currentUserRole,
                fileCount,
                memberCount,
                pinned,
                room.getArchivedAt() != null,
                room.getCreatedAt(),
                room.getUpdatedAt()
        );
    }

    private String normalizeDescription(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        return description.trim();
    }

    private String generateUniqueInviteCode() {
        for (int attempt = 0; attempt < 5; attempt++) {
            byte[] bytes = new byte[12];
            secureRandom.nextBytes(bytes);
            String code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            if (roomRepository.findByInviteCode(code).isEmpty()) {
                return code;
            }
        }
        throw new BadRequestException("Could not generate invite code");
    }
}
