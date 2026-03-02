package com.docvault.service;

import com.docvault.dto.*;
import com.docvault.entity.Role;
import com.docvault.entity.User;
import com.docvault.enums.ERole;
import com.docvault.exception.UserAlreadyExistsException;
import com.docvault.repository.RoleRepository;
import com.docvault.repository.UserRepository;
import com.docvault.security.JwtUtils;
import com.docvault.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtUtils jwtUtils;

    @Transactional
    public MessageResponse signup(SignupRequest request) {
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new UserAlreadyExistsException("Username is already taken");
        }

        if (userRepository.existsByEmail(request.getEmail())) {
            throw new UserAlreadyExistsException("Email is already in use");
        }

        User user = User.builder()
                .username(request.getUsername())
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .build();

        Role defaultRole = roleRepository.findByName(resolveRole(request.getRole()))
                .orElseThrow(() -> new RuntimeException("Default role not found in database"));

        user.getRoles().add(defaultRole);
        userRepository.save(user);

        return MessageResponse.of("User registered successfully");
    }

    public JwtResponse login(LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        request.getUsername(),
                        request.getPassword()
                )
        );

        UserDetailsImpl userDetails = (UserDetailsImpl) authentication.getPrincipal();
        String token = jwtUtils.generateToken(userDetails);

        Set<String> roles = userDetails.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());

        return JwtResponse.of(
                token,
                userDetails.getId(),
                userDetails.getUsername(),
                userDetails.getEmail(),
                roles
        );
    }

    @Transactional(readOnly = true)
    public UserProfileResponse getCurrentUser(UserDetailsImpl userDetails) {
        User user = userRepository.findByUsername(userDetails.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"));

        Set<String> roles = user.getRoles().stream()
                .map(role -> role.getName().name())
                .collect(Collectors.toSet());

        return UserProfileResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .roles(roles)
                .createdAt(user.getCreatedAt())
                .build();
    }

    private ERole resolveRole(String role) {
        if (role == null || role.isBlank()) {
            return ERole.ROLE_VIEWER;
        }
        return switch (role.toUpperCase()) {
            case "ADMIN" -> ERole.ROLE_ADMIN;
            case "EDITOR" -> ERole.ROLE_EDITOR;
            default -> ERole.ROLE_VIEWER;
        };
    }
}
