package com.codeleon.admin;

import com.codeleon.admin.dto.AdminRoomResponse;
import com.codeleon.admin.dto.AdminStatsResponse;
import com.codeleon.admin.dto.AdminUserResponse;
import com.codeleon.ai.QdrantClient;
import com.codeleon.common.exception.BadRequestException;
import com.codeleon.common.exception.NotFoundException;
import com.codeleon.room.Room;
import com.codeleon.room.RoomFileRepository;
import com.codeleon.room.RoomMemberRepository;
import com.codeleon.room.RoomRepository;
import com.codeleon.room.enums.RoomVisibility;
import com.codeleon.user.User;
import com.codeleon.user.UserRepository;
import com.codeleon.user.UserRole;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdminService {

    private static final Logger log = LoggerFactory.getLogger(AdminService.class);

    private final UserRepository userRepository;
    private final RoomRepository roomRepository;
    private final RoomMemberRepository roomMemberRepository;
    private final RoomFileRepository roomFileRepository;
    private final ObjectProvider<QdrantClient> qdrantClientProvider;

    // -----------------------------------------------------------------
    // Users
    // -----------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<AdminUserResponse> listUsers() {
        return userRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toAdminUserResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public AdminUserResponse getUser(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        return toAdminUserResponse(user);
    }

    @Transactional
    public AdminUserResponse updateRole(UUID userId, UserRole newRole, User actingAdmin) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        if (user.getId().equals(actingAdmin.getId()) && newRole != UserRole.ADMIN) {
            throw new BadRequestException("Admins cannot demote themselves");
        }
        if (user.getRole() == UserRole.ADMIN && newRole != UserRole.ADMIN
                && countAdmins() <= 1) {
            throw new BadRequestException("Cannot demote the last remaining admin");
        }
        user.setRole(newRole);
        userRepository.save(user);
        log.info("Admin {} changed role of {} to {}", actingAdmin.getEmail(), user.getEmail(), newRole);
        return toAdminUserResponse(user);
    }

    @Transactional
    public void deleteUser(UUID userId, User actingAdmin) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        if (user.getId().equals(actingAdmin.getId())) {
            throw new BadRequestException("Admins cannot delete their own account from this screen");
        }
        if (user.getRole() == UserRole.ADMIN && countAdmins() <= 1) {
            throw new BadRequestException("Cannot delete the last remaining admin");
        }
        userRepository.delete(user);
        log.info("Admin {} deleted user {}", actingAdmin.getEmail(), user.getEmail());
    }

    // -----------------------------------------------------------------
    // Rooms
    // -----------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<AdminRoomResponse> listRooms() {
        return roomRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(room -> AdminRoomResponse.of(
                        room,
                        roomMemberRepository.countByRoom(room),
                        roomFileRepository.countByRoom(room)
                ))
                .toList();
    }

    @Transactional
    public void deleteRoom(UUID roomId, User actingAdmin) {
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new NotFoundException("Room not found"));
        roomRepository.delete(room);
        log.info("Admin {} deleted room {} ({})", actingAdmin.getEmail(), room.getName(), roomId);
    }

    // -----------------------------------------------------------------
    // Stats
    // -----------------------------------------------------------------

    @Transactional(readOnly = true)
    public AdminStatsResponse stats() {
        long totalUsers = userRepository.count();
        Map<String, Long> usersByRole = new LinkedHashMap<>();
        for (UserRole role : UserRole.values()) {
            usersByRole.put(role.name(), userRepository.countByRole(role));
        }

        Map<String, Long> usersByMethod = new LinkedHashMap<>();
        usersByMethod.put("password", userRepository.countByOauthProviderIsNull());
        usersByMethod.put("github", userRepository.countByOauthProvider("github"));
        usersByMethod.put("google", userRepository.countByOauthProvider("google"));

        long usersJoined7d = userRepository.countByCreatedAtAfter(
                Instant.now().minus(7, ChronoUnit.DAYS));

        long totalRooms = roomRepository.count();
        Map<String, Long> roomsByVisibility = new LinkedHashMap<>();
        for (RoomVisibility v : RoomVisibility.values()) {
            roomsByVisibility.put(v.name(), roomRepository.countByVisibility(v));
        }

        long totalFiles = roomFileRepository.count();
        long totalMembers = roomMemberRepository.count();

        long ragChunks = 0L;
        boolean ragUp = false;
        QdrantClient qdrant = qdrantClientProvider.getIfAvailable();
        if (qdrant != null) {
            try {
                ragChunks = qdrant.countPoints();
                ragUp = true;
            } catch (RuntimeException ex) {
                log.debug("Qdrant unreachable while computing stats: {}", ex.getMessage());
            }
        }

        return new AdminStatsResponse(
                totalUsers,
                usersByRole,
                usersByMethod,
                usersJoined7d,
                totalRooms,
                roomsByVisibility,
                totalFiles,
                totalMembers,
                ragChunks,
                ragUp
        );
    }

    // -----------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------

    private AdminUserResponse toAdminUserResponse(User user) {
        long owned = roomRepository.countByOwner(user);
        long member = roomMemberRepository.countByUser(user);
        return AdminUserResponse.of(user, owned, member);
    }

    private long countAdmins() {
        return userRepository.countByRole(UserRole.ADMIN);
    }
}
