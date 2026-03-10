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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private JwtUtils jwtUtils;

    @InjectMocks
    private AuthService authService;

    private SignupRequest signupRequest;
    private LoginRequest loginRequest;
    private Role viewerRole;
    private Role adminRole;
    private Role editorRole;

    @BeforeEach
    void setUp() {
        signupRequest = SignupRequest.builder()
                .username("testuser")
                .email("test@example.com")
                .password("password123")
                .build();

        loginRequest = LoginRequest.builder()
                .username("testuser")
                .password("password123")
                .build();

        viewerRole = Role.builder().id(1L).name(ERole.ROLE_VIEWER).build();
        adminRole = Role.builder().id(2L).name(ERole.ROLE_ADMIN).build();
        editorRole = Role.builder().id(3L).name(ERole.ROLE_EDITOR).build();
    }

    @Nested
    @DisplayName("Signup")
    class Signup {

        @Test
        @DisplayName("Given valid request, when signing up, then creates user with default VIEWER role")
        void givenValidRequest_whenSigningUp_thenCreatesUserWithDefaultViewerRole() {
            // Given
            when(userRepository.existsByUsername("testuser")).thenReturn(false);
            when(userRepository.existsByEmail("test@example.com")).thenReturn(false);
            when(passwordEncoder.encode("password123")).thenReturn("hashedpassword");
            when(roleRepository.findByName(ERole.ROLE_VIEWER)).thenReturn(Optional.of(viewerRole));

            // When
            MessageResponse response = authService.signup(signupRequest);

            // Then
            assertThat(response.getMessage()).isEqualTo("User registered successfully");

            ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(userCaptor.capture());

            User savedUser = userCaptor.getValue();
            assertThat(savedUser.getUsername()).isEqualTo("testuser");
            assertThat(savedUser.getEmail()).isEqualTo("test@example.com");
            assertThat(savedUser.getPassword()).isEqualTo("hashedpassword");
            assertThat(savedUser.getRoles()).containsExactly(viewerRole);
        }

        @Test
        @DisplayName("Given duplicate username, when signing up, then throws UserAlreadyExistsException")
        void givenDuplicateUsername_whenSigningUp_thenThrowsUserAlreadyExistsException() {
            // Given
            when(userRepository.existsByUsername("testuser")).thenReturn(true);

            // When / Then
            assertThatThrownBy(() -> authService.signup(signupRequest))
                    .isInstanceOf(UserAlreadyExistsException.class)
                    .hasMessage("Username is already taken");

            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("Given duplicate email, when signing up, then throws UserAlreadyExistsException")
        void givenDuplicateEmail_whenSigningUp_thenThrowsUserAlreadyExistsException() {
            // Given
            when(userRepository.existsByUsername("testuser")).thenReturn(false);
            when(userRepository.existsByEmail("test@example.com")).thenReturn(true);

            // When / Then
            assertThatThrownBy(() -> authService.signup(signupRequest))
                    .isInstanceOf(UserAlreadyExistsException.class)
                    .hasMessage("Email is already in use");

            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("Given role not found in database, when signing up, then throws RuntimeException")
        void givenRoleNotFoundInDatabase_whenSigningUp_thenThrowsRuntimeException() {
            // Given
            when(userRepository.existsByUsername("testuser")).thenReturn(false);
            when(userRepository.existsByEmail("test@example.com")).thenReturn(false);
            when(passwordEncoder.encode("password123")).thenReturn("hashedpassword");
            when(roleRepository.findByName(ERole.ROLE_VIEWER)).thenReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> authService.signup(signupRequest))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("Default role not found in database");
        }
    }

    @Nested
    @DisplayName("Login")
    class Login {

        @Test
        @DisplayName("Given valid credentials, when logging in, then returns JWT response with token and user info")
        void givenValidCredentials_whenLoggingIn_thenReturnsJwtResponseWithTokenAndUserInfo() {
            // Given
            UserDetailsImpl userDetails = new UserDetailsImpl(
                    1L, "testuser", "test@example.com", "hashedpassword", true,
                    List.of(new SimpleGrantedAuthority("ROLE_VIEWER"))
            );

            Authentication authentication = mock(Authentication.class);
            when(authentication.getPrincipal()).thenReturn(userDetails);
            when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                    .thenReturn(authentication);
            when(jwtUtils.generateToken(userDetails)).thenReturn("mock.jwt.token");

            // When
            JwtResponse response = authService.login(loginRequest);

            // Then
            assertThat(response.getToken()).isEqualTo("mock.jwt.token");
            assertThat(response.getType()).isEqualTo("Bearer");
            assertThat(response.getId()).isEqualTo(1L);
            assertThat(response.getUsername()).isEqualTo("testuser");
            assertThat(response.getEmail()).isEqualTo("test@example.com");
            assertThat(response.getRoles()).containsExactly("ROLE_VIEWER");
        }

        @Test
        @DisplayName("Given valid credentials with multiple roles, when logging in, then returns all roles")
        void givenValidCredentialsWithMultipleRoles_whenLoggingIn_thenReturnsAllRoles() {
            // Given
            UserDetailsImpl userDetails = new UserDetailsImpl(
                    2L, "adminuser", "admin@example.com", "hashedpassword", true,
                    List.of(
                            new SimpleGrantedAuthority("ROLE_ADMIN"),
                            new SimpleGrantedAuthority("ROLE_EDITOR")
                    )
            );

            Authentication authentication = mock(Authentication.class);
            when(authentication.getPrincipal()).thenReturn(userDetails);
            when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                    .thenReturn(authentication);
            when(jwtUtils.generateToken(userDetails)).thenReturn("mock.jwt.token");

            // When
            JwtResponse response = authService.login(LoginRequest.builder()
                    .username("adminuser").password("password123").build());

            // Then
            assertThat(response.getRoles()).containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_EDITOR");
        }

        @Test
        @DisplayName("Given invalid credentials, when logging in, then throws BadCredentialsException")
        void givenInvalidCredentials_whenLoggingIn_thenThrowsBadCredentialsException() {
            // Given
            when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                    .thenThrow(new BadCredentialsException("Bad credentials"));

            // When / Then
            assertThatThrownBy(() -> authService.login(loginRequest))
                    .isInstanceOf(BadCredentialsException.class);
        }
    }

    @Nested
    @DisplayName("Get Current User")
    class GetCurrentUser {

        @Test
        @DisplayName("Given authenticated user, when getting current user, then returns profile with roles")
        void givenAuthenticatedUser_whenGettingCurrentUser_thenReturnsProfileWithRoles() {
            // Given
            UserDetailsImpl userDetails = new UserDetailsImpl(
                    1L, "testuser", "test@example.com", "hashedpassword", true,
                    List.of(new SimpleGrantedAuthority("ROLE_VIEWER"))
            );

            LocalDateTime createdAt = LocalDateTime.of(2025, 3, 1, 12, 0);
            User user = User.builder()
                    .id(1L)
                    .username("testuser")
                    .email("test@example.com")
                    .password("hashedpassword")
                    .roles(Set.of(viewerRole))
                    .createdAt(createdAt)
                    .build();

            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(user));

            // When
            UserProfileResponse response = authService.getCurrentUser(userDetails);

            // Then
            assertThat(response.getId()).isEqualTo(1L);
            assertThat(response.getUsername()).isEqualTo("testuser");
            assertThat(response.getEmail()).isEqualTo("test@example.com");
            assertThat(response.getRoles()).containsExactly("ROLE_VIEWER");
            assertThat(response.getCreatedAt()).isEqualTo(createdAt);
        }

        @Test
        @DisplayName("Given authenticated user not found in database, when getting current user, then throws RuntimeException")
        void givenAuthenticatedUserNotFoundInDatabase_whenGettingCurrentUser_thenThrowsRuntimeException() {
            // Given
            UserDetailsImpl userDetails = new UserDetailsImpl(
                    1L, "deleteduser", "deleted@example.com", "hashedpassword", true,
                    List.of(new SimpleGrantedAuthority("ROLE_VIEWER"))
            );

            when(userRepository.findByUsername("deleteduser")).thenReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> authService.getCurrentUser(userDetails))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("User not found");
        }
    }
}
