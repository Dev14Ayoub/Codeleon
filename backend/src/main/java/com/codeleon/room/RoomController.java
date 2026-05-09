package com.codeleon.room;

import com.codeleon.room.dto.CreateRoomRequest;
import com.codeleon.room.dto.RoomResponse;
import com.codeleon.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/rooms")
@RequiredArgsConstructor
public class RoomController {

    private final RoomService roomService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RoomResponse createRoom(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody CreateRoomRequest request
    ) {
        return roomService.createRoom(request, user);
    }

    /**
     * Lists rooms the user is a member of. By default archived rooms are
     * hidden so the dashboard's "All" view stays focused on active work;
     * passing ?archived=true is the only way to see them, used by the
     * "Archived" filter dropdown.
     */
    @GetMapping
    public List<RoomResponse> myRooms(
            @AuthenticationPrincipal User user,
            @RequestParam(name = "archived", required = false, defaultValue = "false") boolean includeArchived
    ) {
        return roomService.getMyRooms(user, includeArchived);
    }

    @GetMapping("/public")
    public List<RoomResponse> publicRooms(@AuthenticationPrincipal User user) {
        return roomService.getPublicRooms(user);
    }

    @GetMapping("/{roomId}")
    public RoomResponse getRoom(@PathVariable UUID roomId, @AuthenticationPrincipal User user) {
        return roomService.getRoom(roomId, user);
    }

    @PostMapping("/join/{inviteCode}")
    public RoomResponse joinRoom(@PathVariable String inviteCode, @AuthenticationPrincipal User user) {
        return roomService.joinByInviteCode(inviteCode, user);
    }

    @PostMapping("/{roomId}/pin")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void pinRoom(@PathVariable UUID roomId, @AuthenticationPrincipal User user) {
        roomService.pinRoom(roomId, user);
    }

    @DeleteMapping("/{roomId}/pin")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unpinRoom(@PathVariable UUID roomId, @AuthenticationPrincipal User user) {
        roomService.unpinRoom(roomId, user);
    }

    @PostMapping("/{roomId}/archive")
    public RoomResponse archiveRoom(@PathVariable UUID roomId, @AuthenticationPrincipal User user) {
        return roomService.archiveRoom(roomId, user);
    }

    @DeleteMapping("/{roomId}/archive")
    public RoomResponse unarchiveRoom(@PathVariable UUID roomId, @AuthenticationPrincipal User user) {
        return roomService.unarchiveRoom(roomId, user);
    }
}
