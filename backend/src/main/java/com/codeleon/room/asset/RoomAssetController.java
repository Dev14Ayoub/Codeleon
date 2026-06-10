package com.codeleon.room.asset;

import com.codeleon.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Binary assets of a room (images, fonts, media). Upload/delete need edit
 * rights; list/content need read access. The asset bytes returned here feed the
 * editor's thumbnail; project runtime gets them from workspace materialization.
 */
@RestController
@RequestMapping("/rooms/{roomId}/assets")
@RequiredArgsConstructor
public class RoomAssetController {

    private final RoomAssetService service;

    @GetMapping
    public List<RoomAssetResponse> list(
            @PathVariable UUID roomId,
            @AuthenticationPrincipal User user
    ) {
        return service.list(roomId, user).stream().map(RoomAssetController::toResponse).toList();
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public RoomAssetResponse upload(
            @PathVariable UUID roomId,
            @RequestParam("path") String path,
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal User user
    ) {
        return toResponse(service.upload(roomId, user, path, file));
    }

    /** Raw bytes for the editor thumbnail. The SPA fetches this with its bearer
     *  token (axios) and builds a blob URL — so it stays behind auth. */
    @GetMapping("/content")
    public ResponseEntity<ByteArrayResource> content(
            @PathVariable UUID roomId,
            @RequestParam("path") String path,
            @AuthenticationPrincipal User user
    ) {
        RoomAsset asset = service.getForRead(roomId, user, path);
        String type = asset.getContentType() == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : asset.getContentType();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(type))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .contentLength(asset.getBytes().length)
                .body(new ByteArrayResource(asset.getBytes()));
    }

    @DeleteMapping
    public void delete(
            @PathVariable UUID roomId,
            @RequestParam("path") String path,
            @AuthenticationPrincipal User user
    ) {
        service.delete(roomId, user, path);
    }

    private static RoomAssetResponse toResponse(RoomAsset asset) {
        return new RoomAssetResponse(
                asset.getId(),
                asset.getPath(),
                asset.getContentType(),
                asset.getSizeBytes(),
                asset.getUpdatedAt()
        );
    }

    /** Wire shape — bytes are never inlined; fetched via /content on demand. */
    public record RoomAssetResponse(
            UUID id,
            String path,
            String contentType,
            int sizeBytes,
            Instant updatedAt
    ) {
    }
}
