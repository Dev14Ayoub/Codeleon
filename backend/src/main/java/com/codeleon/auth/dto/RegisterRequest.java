package com.codeleon.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank
        @Size(min = 2, max = 120)
        String fullName,

        @NotBlank
        @Email
        @Size(max = 180)
        String email,

        @NotBlank
        @Size(min = 8, max = 120)
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "Password must contain at least one letter and one number")
        String password
) {
}
