package com.codeleon.admin;

import com.codeleon.admin.dto.AdminRoomResponse;
import com.codeleon.admin.dto.AdminStatsResponse;
import com.codeleon.admin.dto.AdminUserResponse;
import com.codeleon.admin.dto.UpdateRoleRequest;
import com.codeleon.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;

    // ---------------------------------------------------------------
    // Users
    // ---------------------------------------------------------------

    @GetMapping("/users")
    public List<AdminUserResponse> listUsers() {
        return adminService.listUsers();
    }

    @GetMapping("/users/{userId}")
    public AdminUserResponse getUser(@PathVariable UUID userId) {
        return adminService.getUser(userId);
    }

    @PatchMapping("/users/{userId}/role")
    public AdminUserResponse updateRole(
            @PathVariable UUID userId,
            @Valid @RequestBody UpdateRoleRequest request,
            @AuthenticationPrincipal User actingAdmin
    ) {
        return adminService.updateRole(userId, request.role(), actingAdmin);
    }

    @DeleteMapping("/users/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteUser(
            @PathVariable UUID userId,
            @AuthenticationPrincipal User actingAdmin
    ) {
        adminService.deleteUser(userId, actingAdmin);
    }

    // ---------------------------------------------------------------
    // Rooms
    // ---------------------------------------------------------------

    @GetMapping("/rooms")
    public List<AdminRoomResponse> listRooms() {
        return adminService.listRooms();
    }

    @DeleteMapping("/rooms/{roomId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRoom(
            @PathVariable UUID roomId,
            @AuthenticationPrincipal User actingAdmin
    ) {
        adminService.deleteRoom(roomId, actingAdmin);
    }

    // ---------------------------------------------------------------
    // Stats
    // ---------------------------------------------------------------

    @GetMapping("/stats")
    public AdminStatsResponse stats() {
        return adminService.stats();
    }
}
