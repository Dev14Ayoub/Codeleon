package com.codeleon.room;

import com.codeleon.room.dto.CreateRoomFileRequest;
import com.codeleon.room.dto.RenameRoomFileRequest;
import com.codeleon.room.dto.RoomFileResponse;
import com.codeleon.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/rooms/{roomId}")
@RequiredArgsConstructor
public class RoomFileController {

    private final RoomFileService roomFileService;

    // -----------------------------------------------------------------
    // Whole-room Yjs snapshot (one Y.Doc per room, one Y.Text per file
    // path inside it).
    // -----------------------------------------------------------------

    @GetMapping(value = "/snapshot", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> getSnapshot(
            @PathVariable UUID roomId,
            @AuthenticationPrincipal User user
    ) {
        byte[] snapshot = roomFileService.loadOrInitRoomSnapshot(roomId, user);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(snapshot);
    }

    @PutMapping(value = "/snapshot", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<Void> putSnapshot(
            @PathVariable UUID roomId,
            @AuthenticationPrincipal User user,
            @RequestBody byte[] body
    ) {
        roomFileService.saveRoomSnapshot(roomId, user, body);
        return ResponseEntity.noContent().build();
    }

    // -----------------------------------------------------------------
    // File metadata CRUD (path, language, timestamps). The actual
    // content lives inside the room's Y.Doc as Y.Text("<path>").
    // -----------------------------------------------------------------

    @GetMapping("/files")
    public List<RoomFileResponse> listFiles(
            @PathVariable UUID roomId,
            @AuthenticationPrincipal User user
    ) {
        return roomFileService.listFiles(roomId, user).stream()
                .map(RoomFileResponse::from)
                .toList();
    }

    @PostMapping("/files")
    @ResponseStatus(HttpStatus.CREATED)
    public RoomFileResponse createFile(
            @PathVariable UUID roomId,
            @AuthenticationPrincipal User user,
            @Valid @RequestBody CreateRoomFileRequest request
    ) {
        return RoomFileResponse.from(roomFileService.createFile(roomId, user, request.path()));
    }

    @PatchMapping("/files/{fileId}")
    public RoomFileResponse renameFile(
            @PathVariable UUID roomId,
            @PathVariable UUID fileId,
            @AuthenticationPrincipal User user,
            @Valid @RequestBody RenameRoomFileRequest request
    ) {
        return RoomFileResponse.from(roomFileService.renameFile(roomId, fileId, user, request.path()));
    }

    @DeleteMapping("/files/{fileId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteFile(
            @PathVariable UUID roomId,
            @PathVariable UUID fileId,
            @AuthenticationPrincipal User user
    ) {
        roomFileService.deleteFile(roomId, fileId, user);
    }
}
