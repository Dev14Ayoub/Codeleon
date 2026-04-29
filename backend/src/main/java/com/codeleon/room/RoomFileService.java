package com.codeleon.room;

import com.codeleon.common.exception.NotFoundException;
import com.codeleon.room.enums.RoomMemberRole;
import com.codeleon.room.enums.RoomVisibility;
import com.codeleon.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RoomFileService {

    static final String DEFAULT_PATH = "main";
    static final String DEFAULT_LANGUAGE = "java";

    private final RoomRepository roomRepository;
    private final RoomMemberRepository roomMemberRepository;
    private final RoomFileRepository roomFileRepository;

    @Transactional
    public byte[] loadOrInitDefaultSnapshot(UUID roomId, User user) {
        Room room = ensureReadAccess(roomId, user);
        RoomFile file = findOrCreate(room);
        byte[] update = file.getStateUpdate();
        return update == null ? new byte[0] : update;
    }

    @Transactional
    public void saveDefaultSnapshot(UUID roomId, User user, byte[] update) {
        Room room = ensureWriteAccess(roomId, user);
        RoomFile file = findOrCreate(room);
        file.setStateUpdate(update);
        roomFileRepository.save(file);
    }

    public boolean canEdit(UUID roomId, User user) {
        Room room = roomRepository.findById(roomId).orElse(null);
        if (room == null) {
            return false;
        }
        RoomMemberRole role = roomMemberRepository.findByRoomAndUser(room, user)
                .map(RoomMember::getRole)
                .orElse(null);
        return role == RoomMemberRole.OWNER || role == RoomMemberRole.EDITOR;
    }

    public boolean canRead(UUID roomId, User user) {
        Room room = roomRepository.findById(roomId).orElse(null);
        if (room == null) {
            return false;
        }
        RoomMemberRole role = roomMemberRepository.findByRoomAndUser(room, user)
                .map(RoomMember::getRole)
                .orElse(null);
        if (role != null) {
            return true;
        }
        return room.getVisibility() == RoomVisibility.PUBLIC;
    }

    private Room ensureReadAccess(UUID roomId, User user) {
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new NotFoundException("Room not found"));
        if (!isMember(room, user) && room.getVisibility() != RoomVisibility.PUBLIC) {
            throw new NotFoundException("Room not found");
        }
        return room;
    }

    private Room ensureWriteAccess(UUID roomId, User user) {
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new NotFoundException("Room not found"));
        RoomMemberRole role = roomMemberRepository.findByRoomAndUser(room, user)
                .map(RoomMember::getRole)
                .orElse(null);
        if (role != RoomMemberRole.OWNER && role != RoomMemberRole.EDITOR) {
            throw new NotFoundException("Room not found");
        }
        return room;
    }

    private boolean isMember(Room room, User user) {
        return roomMemberRepository.findByRoomAndUser(room, user).isPresent();
    }

    private RoomFile findOrCreate(Room room) {
        return roomFileRepository.findByRoomAndPath(room, DEFAULT_PATH)
                .orElseGet(() -> roomFileRepository.save(RoomFile.builder()
                        .room(room)
                        .path(DEFAULT_PATH)
                        .language(DEFAULT_LANGUAGE)
                        .build()));
    }
}
