package com.codeleon.room;

import com.codeleon.user.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface RoomPinRepository extends JpaRepository<RoomPin, RoomPin.PinId> {

    boolean existsByUserAndRoom(User user, Room room);

    void deleteByUserAndRoom(User user, Room room);

    List<RoomPin> findByUser(User user);

    /**
     * Convenience accessor returning just the pinned room ids for a user.
     * Used by the dashboard listing to bucket pinned vs non-pinned rooms
     * in one pass without a per-row JPA hit.
     */
    default Set<UUID> findPinnedRoomIds(User user) {
        return findByUser(user).stream()
                .map(pin -> pin.getRoom().getId())
                .collect(java.util.stream.Collectors.toSet());
    }
}
