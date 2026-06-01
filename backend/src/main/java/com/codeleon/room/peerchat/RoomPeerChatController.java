package com.codeleon.room.peerchat;

import com.codeleon.user.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/rooms/{roomId}/peer-chat")
@RequiredArgsConstructor
public class RoomPeerChatController {

    private final RoomPeerChatService service;

    /** History — last N messages oldest-first. */
    @GetMapping("/messages")
    public List<RoomPeerChatMessageResponse> history(
            @PathVariable UUID roomId,
            @RequestParam(required = false) Integer limit,
            @AuthenticationPrincipal User user
    ) {
        return service.getHistory(roomId, user, limit).stream()
                .map(RoomPeerChatController::toResponse)
                .toList();
    }

    /** Plain-text message send. JSON body. */
    @PostMapping("/messages")
    public RoomPeerChatMessageResponse send(
            @PathVariable UUID roomId,
            @Valid @RequestBody SendMessageRequest body,
            @AuthenticationPrincipal User user
    ) {
        RoomPeerChatMessage saved = service.postText(roomId, user, body.content());
        return toResponse(saved);
    }

    /**
     * Message with file attachment. Multipart form-data: a "file" part
     * for the file itself, an optional "caption" part for the text
     * accompanying the file.
     */
    @PostMapping(value = "/messages/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public RoomPeerChatMessageResponse sendWithFile(
            @PathVariable UUID roomId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "caption", required = false) String caption,
            @AuthenticationPrincipal User user
    ) {
        RoomPeerChatMessage saved = service.postWithFile(roomId, user, caption, file);
        return toResponse(saved);
    }

    /** Download a file attachment. Inline disposition lets the browser
     *  preview images / PDFs directly in a new tab. */
    @GetMapping("/messages/{messageId}/file")
    public ResponseEntity<ByteArrayResource> downloadFile(
            @PathVariable UUID roomId,
            @PathVariable UUID messageId,
            @AuthenticationPrincipal User user
    ) {
        RoomPeerChatMessage msg = service.getFile(messageId, user);
        ByteArrayResource body = new ByteArrayResource(msg.getFileBytes());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(msg.getFileType() == null
                        ? MediaType.APPLICATION_OCTET_STREAM_VALUE
                        : msg.getFileType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + msg.getFileName() + "\"")
                .contentLength(msg.getFileBytes().length)
                .body(body);
    }

    private static RoomPeerChatMessageResponse toResponse(RoomPeerChatMessage m) {
        return new RoomPeerChatMessageResponse(
                m.getId(),
                m.getUser() == null ? null : m.getUser().getId(),
                m.getUserName(),
                m.getContent(),
                m.getFileName(),
                m.getFileType(),
                m.getFileSize(),
                m.getCreatedAt()
        );
    }

    /** JSON body for plain-text sends. */
    public record SendMessageRequest(
            @NotBlank @Size(max = 4000) String content
    ) {}

    /** Wire shape returned to the frontend. {@code fileBytes} is never
     *  included — files are fetched on demand via the dedicated
     *  download endpoint. */
    public record RoomPeerChatMessageResponse(
            UUID id,
            UUID userId,
            String userName,
            String content,
            String fileName,
            String fileType,
            Integer fileSize,
            Instant createdAt
    ) {}
}
