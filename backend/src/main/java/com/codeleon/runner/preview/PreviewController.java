package com.codeleon.runner.preview;

import com.codeleon.common.exception.NotFoundException;
import com.codeleon.room.RoomFileService;
import com.codeleon.runner.terminal.WorkspaceMaterializer;
import com.codeleon.user.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Start / stop / status of a room's live web preview. Starting and stopping
 * require edit rights (running a server is a member action); reading status
 * requires read access. The proxy that actually serves the preview content
 * lives in {@link PreviewProxyController}.
 */
@RestController
@RequestMapping("/rooms/{roomId}/preview")
@RequiredArgsConstructor
public class PreviewController {

    private final PreviewService previewService;
    private final RoomFileService roomFileService;
    private final PreviewTokenService previewTokenService;

    @PostMapping
    public PreviewStatusResponse start(
            @PathVariable UUID roomId,
            @AuthenticationPrincipal User user,
            @Valid @RequestBody PreviewStartRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse
    ) {
        if (!roomFileService.canEdit(roomId, user)) {
            throw new NotFoundException("Room not found");
        }
        List<WorkspaceMaterializer.FileEntry> files = request.files() == null
                ? List.of()
                : request.files().stream()
                        .map(f -> new WorkspaceMaterializer.FileEntry(f.path(), f.text()))
                        .toList();
        previewService.start(roomId, request.command(), files);
        issuePreviewCookie(roomId, httpRequest, httpResponse);
        return currentStatus(roomId);
    }

    @DeleteMapping
    public PreviewStatusResponse stop(
            @PathVariable UUID roomId,
            @AuthenticationPrincipal User user
    ) {
        if (!roomFileService.canEdit(roomId, user)) {
            throw new NotFoundException("Room not found");
        }
        previewService.stop(roomId);
        return new PreviewStatusResponse(false, null, null);
    }

    @GetMapping
    public PreviewStatusResponse status(
            @PathVariable UUID roomId,
            @AuthenticationPrincipal User user,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse
    ) {
        if (!roomFileService.canRead(roomId, user)) {
            throw new NotFoundException("Room not found");
        }
        // Refresh the room-scoped preview cookie on every status poll so the
        // (unauthenticated) proxy can authorize this member's iframe requests.
        issuePreviewCookie(roomId, httpRequest, httpResponse);
        return currentStatus(roomId);
    }

    private PreviewStatusResponse currentStatus(UUID roomId) {
        return previewService.get(roomId)
                .map(session -> new PreviewStatusResponse(true, session.command(), "/api/v1/preview/" + roomId + "/"))
                .orElse(new PreviewStatusResponse(false, null, null));
    }

    /**
     * Sets the signed, HttpOnly, room-scoped preview cookie. Path-scoped to
     * {@code /api/v1/preview} so it rides the iframe's top-level load and every
     * sub-resource, but is not sent on other API calls. {@code Secure} tracks
     * the request scheme so it still works on the plain-HTTP tailnet.
     */
    private void issuePreviewCookie(UUID roomId, HttpServletRequest request, HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(PreviewTokenService.COOKIE_NAME, previewTokenService.issue(roomId))
                .httpOnly(true)
                .secure(request.isSecure())
                .path("/api/v1/preview")
                .maxAge(PreviewTokenService.TTL)
                .sameSite("Lax")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
