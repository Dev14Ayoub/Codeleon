package com.codeleon.room;

import com.codeleon.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/rooms/{roomId}/snapshot")
@RequiredArgsConstructor
public class RoomFileController {

    private final RoomFileService roomFileService;

    @GetMapping(produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> getSnapshot(
            @PathVariable UUID roomId,
            @AuthenticationPrincipal User user
    ) {
        byte[] snapshot = roomFileService.loadOrInitDefaultSnapshot(roomId, user);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(snapshot);
    }

    @PutMapping(consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<Void> putSnapshot(
            @PathVariable UUID roomId,
            @AuthenticationPrincipal User user,
            @RequestBody byte[] body
    ) {
        roomFileService.saveDefaultSnapshot(roomId, user, body);
        return ResponseEntity.noContent().build();
    }
}
