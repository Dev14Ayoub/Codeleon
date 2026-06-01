package com.codeleon.room.peerchat;

import com.codeleon.common.exception.BadRequestException;
import com.codeleon.common.exception.NotFoundException;
import com.codeleon.room.Room;
import com.codeleon.room.RoomFileService;
import com.codeleon.room.RoomRepository;
import com.codeleon.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RoomPeerChatService {

    /** Last 200 messages by default — matches the frontend's Y.Array prune. */
    private static final int DEFAULT_HISTORY_LIMIT = 200;

    /** Hard cap on file size — 5 MB. Keeps the bytea column reasonable
     *  and stops a single bad upload from filling the disk on
     *  pg_dump. */
    public static final int MAX_FILE_BYTES = 5 * 1024 * 1024;

    /** Allowed MIME types. Conservative on purpose — images, PDFs,
     *  and plain-text-ish formats. JS / executables not allowed even
     *  though the runner sandbox would catch them, because there's no
     *  reason for a user to share an .exe via the chat. */
    private static final Set<String> ALLOWED_MIME_PREFIXES = Set.of(
            "image/",
            "text/",
            "application/pdf",
            "application/json",
            "application/xml",
            "application/zip"
    );

    private final RoomPeerChatMessageRepository repository;
    private final RoomRepository roomRepository;
    private final RoomFileService roomFileService;

    /**
     * Persist a plain-text message. The room membership check is
     * delegated to {@link RoomFileService#canRead} because the same
     * rule applies — anyone who can read the room can chat in it.
     */
    @Transactional
    public RoomPeerChatMessage postText(UUID roomId, User author, String content) {
        if (content == null || content.isBlank()) {
            throw new BadRequestException("Message content is required");
        }
        if (content.length() > 4000) {
            throw new BadRequestException("Message is too long (max 4000 chars)");
        }
        Room room = ensureMember(roomId, author);
        return repository.save(RoomPeerChatMessage.builder()
                .id(UUID.randomUUID())
                .room(room)
                .user(author)
                .userName(author.getFullName())
                .content(content.trim())
                .createdAt(Instant.now())
                .build());
    }

    /**
     * Persist a message with a file attachment. The caption is optional
     * (empty string when the user only sent the file). MIME prefix is
     * validated against {@link #ALLOWED_MIME_PREFIXES}.
     */
    @Transactional
    public RoomPeerChatMessage postWithFile(UUID roomId, User author, String caption,
                                            MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("File is required for attachment messages");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new BadRequestException("File exceeds the " + (MAX_FILE_BYTES / 1024 / 1024)
                    + " MB attachment limit");
        }
        String mime = file.getContentType() == null ? "application/octet-stream" : file.getContentType();
        if (ALLOWED_MIME_PREFIXES.stream().noneMatch(mime::startsWith)) {
            throw new BadRequestException("File type not allowed: " + mime);
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException ex) {
            throw new BadRequestException("Could not read uploaded file: " + ex.getMessage());
        }
        String safeCaption = caption == null ? "" : caption.trim();
        if (safeCaption.length() > 4000) {
            throw new BadRequestException("Caption is too long (max 4000 chars)");
        }
        Room room = ensureMember(roomId, author);
        return repository.save(RoomPeerChatMessage.builder()
                .id(UUID.randomUUID())
                .room(room)
                .user(author)
                .userName(author.getFullName())
                .content(safeCaption)
                .fileName(sanitiseFileName(file.getOriginalFilename()))
                .fileType(mime)
                .fileSize((int) file.getSize())
                .fileBytes(bytes)
                .createdAt(Instant.now())
                .build());
    }

    /** History fetch — last {@code limit} messages, oldest first. */
    @Transactional(readOnly = true)
    public List<RoomPeerChatMessage> getHistory(UUID roomId, User viewer, Integer limit) {
        Room room = ensureMember(roomId, viewer);
        int effectiveLimit = limit == null || limit <= 0 || limit > DEFAULT_HISTORY_LIMIT
                ? DEFAULT_HISTORY_LIMIT
                : limit;
        List<RoomPeerChatMessage> recent = repository.findRecentForRoom(room,
                PageRequest.of(0, effectiveLimit));
        // The query returns newest first to take advantage of the index;
        // flip it so the frontend can append in order.
        List<RoomPeerChatMessage> ordered = new ArrayList<>(recent);
        Collections.reverse(ordered);
        return ordered;
    }

    /**
     * Fetch a single message's file bytes for download. Membership is
     * re-checked against the message's room so a stale link from a
     * left member cannot exfiltrate after they're kicked.
     */
    @Transactional(readOnly = true)
    public RoomPeerChatMessage getFile(UUID messageId, User viewer) {
        RoomPeerChatMessage msg = repository.findById(messageId)
                .orElseThrow(() -> new NotFoundException("Message not found"));
        if (msg.getFileBytes() == null) {
            throw new NotFoundException("Message has no attachment");
        }
        ensureMember(msg.getRoom().getId(), viewer);
        return msg;
    }

    /** Membership gate — anyone who can read the room can chat in it. */
    private Room ensureMember(UUID roomId, User user) {
        if (!roomFileService.canRead(roomId, user)) {
            throw new NotFoundException("Room not found");
        }
        return roomRepository.findById(roomId)
                .orElseThrow(() -> new NotFoundException("Room not found"));
    }

    /** Strips path components and control chars from the user-supplied
     *  filename, leaving only the basename. Defensive against header
     *  smuggling on Content-Disposition downloads. */
    private static String sanitiseFileName(String raw) {
        if (raw == null || raw.isBlank()) return "attachment";
        String basename = raw.replace('\\', '/');
        int slash = basename.lastIndexOf('/');
        if (slash >= 0) basename = basename.substring(slash + 1);
        // Replace anything that isn't safe with underscore.
        return basename.replaceAll("[\\p{Cntrl}\"\\\\]", "_");
    }
}
