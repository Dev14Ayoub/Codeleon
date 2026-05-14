package com.codeleon.room;

import com.codeleon.common.exception.BadRequestException;
import com.codeleon.common.exception.NotFoundException;
import com.codeleon.room.enums.RoomMemberRole;
import com.codeleon.room.enums.RoomVisibility;
import com.codeleon.room.event.RoomEventService;
import com.codeleon.room.event.RoomEventType;
import com.codeleon.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RoomFileService {

    static final String DEFAULT_PATH = "main";
    static final String DEFAULT_LANGUAGE = "plaintext";

    private final RoomRepository roomRepository;
    private final RoomMemberRepository roomMemberRepository;
    private final RoomFileRepository roomFileRepository;
    private final RoomEventService roomEventService;

    // ---------------------------------------------------------------------
    // Snapshots — now scoped to the entire Room (one Y.Doc per room, with
    // one Y.Text per file path inside it). Files can come and go without
    // touching the snapshot.
    // ---------------------------------------------------------------------

    @Transactional
    public byte[] loadOrInitRoomSnapshot(UUID roomId, User user) {
        Room room = ensureReadAccess(roomId, user);
        // Make sure the room has at least one file so the editor has
        // something to bind to on first open.
        if (roomFileRepository.findByRoomOrderByPathAsc(room).isEmpty()) {
            roomFileRepository.save(RoomFile.builder()
                    .room(room)
                    .path(DEFAULT_PATH)
                    .language(DEFAULT_LANGUAGE)
                    .build());
        }
        byte[] update = room.getStateUpdate();
        return update == null ? new byte[0] : update;
    }

    @Transactional
    public void saveRoomSnapshot(UUID roomId, User user, byte[] update) {
        Room room = ensureWriteAccess(roomId, user);
        room.setStateUpdate(update);
        roomRepository.save(room);
    }

    // ---------------------------------------------------------------------
    // File CRUD.
    // ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<RoomFile> listFiles(UUID roomId, User user) {
        Room room = ensureReadAccess(roomId, user);
        return roomFileRepository.findByRoomOrderByPathAsc(room);
    }

    @Transactional
    public RoomFile createFile(UUID roomId, User user, String path) {
        Room room = ensureWriteAccess(roomId, user);
        String trimmed = normalizePath(path);
        if (roomFileRepository.existsByRoomAndPath(room, trimmed)) {
            throw new BadRequestException("A file named '" + trimmed + "' already exists in this room");
        }
        RoomFile saved = roomFileRepository.save(RoomFile.builder()
                .room(room)
                .path(trimmed)
                .language(detectLanguage(trimmed))
                .build());
        roomEventService.emit(roomId, user, RoomEventType.FILE_CREATED, Map.of("path", trimmed));
        return saved;
    }

    @Transactional
    public RoomFile renameFile(UUID roomId, UUID fileId, User user, String newPath) {
        Room room = ensureWriteAccess(roomId, user);
        RoomFile file = roomFileRepository.findByIdAndRoom(fileId, room)
                .orElseThrow(() -> new NotFoundException("File not found"));
        String trimmed = normalizePath(newPath);
        if (trimmed.equals(file.getPath())) {
            return file;
        }
        if (roomFileRepository.existsByRoomAndPath(room, trimmed)) {
            throw new BadRequestException("A file named '" + trimmed + "' already exists in this room");
        }
        String oldPath = file.getPath();
        file.setPath(trimmed);
        file.setLanguage(detectLanguage(trimmed));
        RoomFile saved = roomFileRepository.save(file);
        roomEventService.emit(roomId, user, RoomEventType.FILE_RENAMED,
                Map.of("from", oldPath, "to", trimmed));
        return saved;
    }

    @Transactional
    public void deleteFile(UUID roomId, UUID fileId, User user) {
        Room room = ensureWriteAccess(roomId, user);
        RoomFile file = roomFileRepository.findByIdAndRoom(fileId, room)
                .orElseThrow(() -> new NotFoundException("File not found"));
        // Refuse to delete the last remaining file — the editor needs at
        // least one Y.Text to bind to.
        long fileCount = roomFileRepository.findByRoomOrderByPathAsc(room).size();
        if (fileCount <= 1) {
            throw new BadRequestException("Cannot delete the only remaining file in this room");
        }
        roomFileRepository.delete(file);
        roomEventService.emit(roomId, user, RoomEventType.FILE_DELETED, Map.of("path", file.getPath()));
        // Note: the Y.Text("path") inside the room's Y.Doc is left orphaned.
        // It does not show up in the file list anymore, and is not exposed by
        // any API. A future migration can prune Y.Doc keys at idle time.
    }

    // ---------------------------------------------------------------------
    // Auth helpers (unchanged).
    // ---------------------------------------------------------------------

    public boolean canEdit(UUID roomId, User user) {
        Room room = roomRepository.findById(roomId).orElse(null);
        if (room == null) return false;
        RoomMemberRole role = roomMemberRepository.findByRoomAndUser(room, user)
                .map(RoomMember::getRole)
                .orElse(null);
        return role == RoomMemberRole.OWNER || role == RoomMemberRole.EDITOR;
    }

    public boolean canRead(UUID roomId, User user) {
        Room room = roomRepository.findById(roomId).orElse(null);
        if (room == null) return false;
        RoomMemberRole role = roomMemberRepository.findByRoomAndUser(room, user)
                .map(RoomMember::getRole)
                .orElse(null);
        if (role != null) return true;
        return room.getVisibility() == RoomVisibility.PUBLIC;
    }

    // ---------------------------------------------------------------------
    // Internals.
    // ---------------------------------------------------------------------

    private Room ensureReadAccess(UUID roomId, User user) {
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new NotFoundException("Room not found"));
        if (!isMember(room, user) && room.getVisibility() != RoomVisibility.PUBLIC) {
            throw new NotFoundException("Room not found");
        }
        return room;
    }

    private Room ensureWriteAccess(UUID roomId, User user) {
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new NotFoundException("Room not found"));
        RoomMemberRole role = roomMemberRepository.findByRoomAndUser(room, user)
                .map(RoomMember::getRole)
                .orElse(null);
        if (role != RoomMemberRole.OWNER && role != RoomMemberRole.EDITOR) {
            throw new NotFoundException("Room not found");
        }
        return room;
    }

    private boolean isMember(Room room, User user) {
        return roomMemberRepository.findByRoomAndUser(room, user).isPresent();
    }

    static String normalizePath(String path) {
        if (path == null) {
            throw new BadRequestException("File path is required");
        }
        String trimmed = path.trim();
        if (trimmed.isEmpty()) {
            throw new BadRequestException("File path is required");
        }
        if (trimmed.length() > 255) {
            throw new BadRequestException("File path is too long");
        }
        // Disallow leading/trailing slashes and consecutive slashes that would
        // confuse the file tree later on.
        if (trimmed.startsWith("/") || trimmed.endsWith("/") || trimmed.contains("//")) {
            throw new BadRequestException("Invalid file path");
        }
        return trimmed;
    }

    static String detectLanguage(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1) {
            return DEFAULT_LANGUAGE;
        }
        String ext = path.substring(dot + 1).toLowerCase(Locale.ROOT);
        return switch (ext) {
            case "java" -> "java";
            case "py" -> "python";
            case "js", "mjs", "cjs" -> "javascript";
            case "ts" -> "typescript";
            case "jsx" -> "javascriptreact";
            case "tsx" -> "typescriptreact";
            case "html", "htm" -> "html";
            case "css" -> "css";
            case "scss" -> "scss";
            case "json" -> "json";
            case "yml", "yaml" -> "yaml";
            case "xml" -> "xml";
            case "md", "markdown" -> "markdown";
            case "sh", "bash" -> "shell";
            case "sql" -> "sql";
            case "go" -> "go";
            case "rs" -> "rust";
            case "rb" -> "ruby";
            case "php" -> "php";
            case "cpp", "cxx", "cc", "hpp", "h" -> "cpp";
            case "c" -> "c";
            case "cs" -> "csharp";
            case "kt", "kts" -> "kotlin";
            case "swift" -> "swift";
            case "dockerfile" -> "dockerfile";
            case "txt", "log" -> "plaintext";
            default -> DEFAULT_LANGUAGE;
        };
    }
}
