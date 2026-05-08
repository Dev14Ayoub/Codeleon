package com.codeleon.admin.dto;

import com.codeleon.user.UserRole;
import jakarta.validation.constraints.NotNull;

public record UpdateRoleRequest(
        @NotNull UserRole role
) {
}
