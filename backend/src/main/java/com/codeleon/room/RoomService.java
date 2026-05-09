package com.codeleon.room;

import com.codeleon.common.exception.BadRequestException;
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
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RoomService {

    private final RoomRepository roomRepository;
    private final RoomMemberRepository roomMemberRepository;
    private final RoomFileRepository roomFileRepository;
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

        return toResponse(savedRoom, RoomMemberRole.OWNER);
    }

    @Transactional(readOnly = true)
    public List<RoomResponse> getMyRooms(User user) {
        return roomMemberRepository.findByUserOrderByJoinedAtDesc(user)
                .stream()
                .map(member -> toResponse(member.getRoom(), member.getRole()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<RoomResponse> getPublicRooms(User user) {
        return roomRepository.findByVisibilityOrderByCreatedAtDesc(RoomVisibility.PUBLIC)
                .stream()
                .map(room -> toResponse(room, findRole(room, user)))
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
        return toResponse(room, role);
    }

    @Transactional
    public RoomResponse joinByInviteCode(String inviteCode, User user) {
        Room room = roomRepository.findByInviteCode(inviteCode)
                .orElseThrow(() -> new NotFoundException("Invalid invite code"));

        return roomMemberRepository.findByRoomAndUser(room, user)
                .map(member -> toResponse(room, member.getRole()))
                .orElseGet(() -> {
                    RoomMember member = roomMemberRepository.save(RoomMember.builder()
                            .room(room)
                            .user(user)
                            .role(RoomMemberRole.EDITOR)
                            .build());
                    return toResponse(room, member.getRole());
                });
    }

    private RoomMemberRole findRole(Room room, User user) {
        return roomMemberRepository.findByRoomAndUser(room, user)
                .map(RoomMember::getRole)
                .orElse(null);
    }

    private RoomResponse toResponse(Room room, RoomMemberRole currentUserRole) {
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
