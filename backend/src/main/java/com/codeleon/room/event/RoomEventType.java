package com.codeleon.room.event;

/**
 * The closed set of activity-feed event types. Stored as the enum
 * name() in room_events.type; the frontend switches on the same
 * strings to pick an icon and phrasing for each line. Adding a value
 * here is the contract change — keep the frontend renderer in sync.
 */
public enum RoomEventType {
    FILE_CREATED,
    FILE_RENAMED,
    FILE_DELETED,
    MEMBER_JOINED,
    CODE_RAN,
    AI_ASKED
}
