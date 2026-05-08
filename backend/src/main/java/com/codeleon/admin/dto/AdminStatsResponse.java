package com.codeleon.admin.dto;

import java.util.Map;

public record AdminStatsResponse(
        long totalUsers,
        Map<String, Long> usersByRole,
        Map<String, Long> usersByAuthMethod,
        long usersJoinedLast7Days,
        long totalRooms,
        Map<String, Long> roomsByVisibility,
        long totalFiles,
        long totalMembers,
        long totalRagChunks,
        boolean ragInfrastructureUp
) {
}
