package com.codeleon.room.asset;

import com.codeleon.common.exception.BadRequestException;
import com.codeleon.common.exception.NotFoundException;
import com.codeleon.room.Room;
import com.codeleon.room.RoomFileService;
import com.codeleon.room.RoomRepository;
import com.codeleon.user.User;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Stores and serves a room's binary assets, and materializes them onto disk so
 * runners and the live preview find a project's images/fonts/media. Access is
 * gated by {@link RoomFileService} (edit to write, read to fetch).
 */
@Service
@RequiredArgsConstructor
public class RoomAssetService {

    private static final Logger log = LoggerFactory.getLogger(RoomAssetService.class);

    private static final long MAX_ASSET_BYTES = 5 * 1024 * 1024; // 5 MB per asset
    private static final int MAX_ASSETS_PER_ROOM = 300;

    private final RoomAssetRepository assetRepository;
    private final RoomRepository roomRepository;
    private final RoomFileService roomFileService;

    @Transactional
    public RoomAsset upload(UUID roomId, User user, String rawPath, MultipartFile file) {
        if (!roomFileService.canEdit(roomId, user)) {
            throw new NotFoundException("Room not found");
        }
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("Empty upload");
        }
        if (file.getSize() > MAX_ASSET_BYTES) {
            throw new BadRequestException("Asset is larger than 5 MB");
        }
        String path = normalize(rawPath != null && !rawPath.isBlank() ? rawPath : file.getOriginalFilename());
        Room room = room(roomId);

        boolean exists = assetRepository.existsByRoomAndPath(room, path);
        if (!exists && assetRepository.countByRoom(room) >= MAX_ASSETS_PER_ROOM) {
            throw new BadRequestException("This room already has the maximum number of assets");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException ex) {
            throw new BadRequestException("Failed to read the uploaded file");
        }

        RoomAsset asset = assetRepository.findByRoomAndPath(room, path).orElseGet(RoomAsset::new);
        asset.setRoom(room);
        asset.setPath(path);
        asset.setContentType(file.getContentType());
        asset.setSizeBytes((int) file.getSize());
        asset.setBytes(bytes);
        return assetRepository.save(asset);
    }

    @Transactional(readOnly = true)
    public List<RoomAsset> list(UUID roomId, User user) {
        if (!roomFileService.canRead(roomId, user)) {
            throw new NotFoundException("Room not found");
        }
        return assetRepository.findByRoomOrderByPathAsc(room(roomId));
    }

    @Transactional(readOnly = true)
    public RoomAsset getForRead(UUID roomId, User user, String rawPath) {
        if (!roomFileService.canRead(roomId, user)) {
            throw new NotFoundException("Room not found");
        }
        return assetRepository.findByRoomAndPath(room(roomId), normalize(rawPath))
                .orElseThrow(() -> new NotFoundException("Asset not found"));
    }

    @Transactional
    public void delete(UUID roomId, User user, String rawPath) {
        if (!roomFileService.canEdit(roomId, user)) {
            throw new NotFoundException("Room not found");
        }
        assetRepository.findByRoomAndPath(room(roomId), normalize(rawPath))
                .ifPresent(assetRepository::delete);
    }

    /**
     * Writes every binary asset of the room into {@code workspace} so a runner
     * or the preview container finds them on disk. Best-effort and silent on
     * per-file errors — a missing asset must not abort a run.
     */
    @Transactional(readOnly = true)
    public void materializeInto(UUID roomId, Path workspace) {
        if (roomId == null) {
            return;
        }
        Room room = roomRepository.findById(roomId).orElse(null);
        if (room == null) {
            return;
        }
        for (RoomAsset asset : assetRepository.findByRoomOrderByPathAsc(room)) {
            try {
                Path target = safeResolve(workspace, asset.getPath());
                if (target == null) {
                    continue;
                }
                Path parent = target.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.write(target, asset.getBytes());
            } catch (IOException ex) {
                log.debug("Failed to materialize asset {} for room {}", asset.getPath(), roomId, ex);
            }
        }
    }

    private Room room(UUID roomId) {
        return roomRepository.findById(roomId).orElseThrow(() -> new NotFoundException("Room not found"));
    }

    private String normalize(String rawPath) {
        if (rawPath == null) {
            throw new BadRequestException("Asset path is required");
        }
        String normalized = rawPath.replace('\\', '/').trim();
        // strip a leading "./" and any leading slashes
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isBlank()
                || normalized.length() > 255
                || normalized.contains("\0")
                || normalized.contains(":")
                || normalized.contains("//")
                || normalized.contains("..")) {
            throw new BadRequestException("Invalid asset path: " + rawPath);
        }
        return normalized;
    }

    /** Resolves {@code relativePath} under {@code workspace}, or null if unsafe. */
    private Path safeResolve(Path workspace, String relativePath) {
        try {
            Path relative = Path.of(relativePath).normalize();
            if (relative.isAbsolute() || relative.startsWith("..")) {
                return null;
            }
            Path target = workspace.resolve(relative).normalize();
            return target.startsWith(workspace) ? target : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
