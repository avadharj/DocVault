package com.docvault.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JwtResponse {

    private String token;
    private String type;
    private Long id;
    private String username;
    private String email;
    private Set<String> roles;

    public static JwtResponse of(String token, Long id, String username, String email, Set<String> roles) {
        return JwtResponse.builder()
                .token(token)
                .type("Bearer")
                .id(id)
                .username(username)
                .email(email)
                .roles(roles)
                .build();
    }
}
