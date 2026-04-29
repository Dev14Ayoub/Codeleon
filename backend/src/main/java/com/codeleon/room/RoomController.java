package com.codeleon.room;

import com.codeleon.room.dto.CreateRoomRequest;
import com.codeleon.room.dto.RoomResponse;
import com.codeleon.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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

    @GetMapping
    public List<RoomResponse> myRooms(@AuthenticationPrincipal User user) {
        return roomService.getMyRooms(user);
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
}
