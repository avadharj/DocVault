package com.docvault.integration;

import tools.jackson.databind.json.JsonMapper;
import com.docvault.dto.LoginRequest;
import com.docvault.dto.SignupRequest;
import com.docvault.entity.Role;
import com.docvault.entity.User;
import com.docvault.enums.ERole;
import com.docvault.repository.RoleRepository;
import com.docvault.repository.UserRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();

        for (ERole eRole : ERole.values()) {
            if (!roleRepository.existsByName(eRole)) {
                roleRepository.save(Role.builder().name(eRole).build());
            }
        }
    }

    // ========================
    // SIGNUP TESTS
    // ========================

    @Nested
    @DisplayName("POST /api/auth/signup")
    class SignupEndpoint {

        @Test
        @DisplayName("Given valid signup request, when signing up, then returns 200 with success message")
        void givenValidSignupRequest_whenSigningUp_thenReturns200WithSuccessMessage() throws Exception {
            // Given
            SignupRequest request = SignupRequest.builder()
                    .username("testuser")
                    .email("test@example.com")
                    .password("password123")
                    .build();

            // When / Then
            mockMvc.perform(post("/api/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(jsonMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("User registered successfully"));

            assertThat(userRepository.existsByUsername("testuser")).isTrue();
        }

        @Test
        @DisplayName("Given duplicate email, when signing up, then returns 409 conflict")
        void givenDuplicateEmail_whenSigningUp_thenReturns409Conflict() throws Exception {
            // Given
            createUser("existinguser", "duplicate@example.com", "ROLE_VIEWER");

            SignupRequest request = SignupRequest.builder()
                    .username("newuser")
                    .email("duplicate@example.com")
                    .password("password123")
                    .build();

            // When / Then
            mockMvc.perform(post("/api/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(jsonMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.message").value("Email is already in use"));
        }

        @Test
        @DisplayName("Given duplicate username, when signing up, then returns 409 conflict")
        void givenDuplicateUsername_whenSigningUp_thenReturns409Conflict() throws Exception {
            // Given
            createUser("testuser", "existing@example.com", "ROLE_VIEWER");

            SignupRequest request = SignupRequest.builder()
                    .username("testuser")
                    .email("new@example.com")
                    .password("password123")
                    .build();

            // When / Then
            mockMvc.perform(post("/api/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(jsonMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.message").value("Username is already taken"));
        }

        @Test
        @DisplayName("Given missing required fields, when signing up, then returns 400 with field errors")
        void givenMissingRequiredFields_whenSigningUp_thenReturns400WithFieldErrors() throws Exception {
            // Given
            SignupRequest request = SignupRequest.builder().build();

            // When / Then
            mockMvc.perform(post("/api/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(jsonMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.fieldErrors.username").exists())
                    .andExpect(jsonPath("$.fieldErrors.email").exists())
                    .andExpect(jsonPath("$.fieldErrors.password").exists());
        }

        @Test
        @DisplayName("Given password too short, when signing up, then returns 400 with password error")
        void givenPasswordTooShort_whenSigningUp_thenReturns400WithPasswordError() throws Exception {
            // Given
            SignupRequest request = SignupRequest.builder()
                    .username("testuser")
                    .email("test@example.com")
                    .password("short")
                    .build();

            // When / Then
            mockMvc.perform(post("/api/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(jsonMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors.password").exists());
        }

        @Test
        @DisplayName("Given invalid email format, when signing up, then returns 400 with email error")
        void givenInvalidEmailFormat_whenSigningUp_thenReturns400WithEmailError() throws Exception {
            // Given
            SignupRequest request = SignupRequest.builder()
                    .username("testuser")
                    .email("not-an-email")
                    .password("password123")
                    .build();

            // When / Then
            mockMvc.perform(post("/api/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(jsonMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors.email").exists());
        }
    }

    // ========================
    // LOGIN TESTS
    // ========================

    @Nested
    @DisplayName("POST /api/auth/login")
    class LoginEndpoint {

        @Test
        @DisplayName("Given valid credentials, when logging in, then returns 200 with JWT and user info")
        void givenValidCredentials_whenLoggingIn_thenReturns200WithJwtAndUserInfo() throws Exception {
            // Given
            createUser("testuser", "test@example.com", "ROLE_VIEWER");

            LoginRequest request = LoginRequest.builder()
                    .username("testuser")
                    .password("password123")
                    .build();

            // When / Then
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(jsonMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.token").isNotEmpty())
                    .andExpect(jsonPath("$.type").value("Bearer"))
                    .andExpect(jsonPath("$.username").value("testuser"))
                    .andExpect(jsonPath("$.email").value("test@example.com"))
                    .andExpect(jsonPath("$.roles[0]").value("ROLE_VIEWER"));
        }

        @Test
        @DisplayName("Given wrong password, when logging in, then returns 401")
        void givenWrongPassword_whenLoggingIn_thenReturns401() throws Exception {
            // Given
            createUser("testuser", "test@example.com", "ROLE_VIEWER");

            LoginRequest request = LoginRequest.builder()
                    .username("testuser")
                    .password("wrongpassword")
                    .build();

            // When / Then
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(jsonMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.status").value(401));
        }

        @Test
        @DisplayName("Given nonexistent user, when logging in, then returns 401")
        void givenNonexistentUser_whenLoggingIn_thenReturns401() throws Exception {
            // Given — no user created

            LoginRequest request = LoginRequest.builder()
                    .username("ghost")
                    .password("password123")
                    .build();

            // When / Then
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(jsonMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ========================
    // GET /api/auth/me TESTS
    // ========================

    @Nested
    @DisplayName("GET /api/auth/me")
    class MeEndpoint {

        @Test
        @DisplayName("Given valid JWT, when getting current user, then returns 200 with profile")
        void givenValidJwt_whenGettingCurrentUser_thenReturns200WithProfile() throws Exception {
            // Given
            String token = createUserAndGetToken("testuser", "test@example.com", "ROLE_VIEWER");

            // When / Then
            mockMvc.perform(get("/api/auth/me")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.username").value("testuser"))
                    .andExpect(jsonPath("$.email").value("test@example.com"))
                    .andExpect(jsonPath("$.roles").isArray())
                    .andExpect(jsonPath("$.createdAt").exists());
        }

        @Test
        @DisplayName("Given no JWT, when getting current user, then returns 401")
        void givenNoJwt_whenGettingCurrentUser_thenReturns401() throws Exception {
            // Given — no Authorization header

            // When / Then
            mockMvc.perform(get("/api/auth/me"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Given invalid JWT, when getting current user, then returns 401")
        void givenInvalidJwt_whenGettingCurrentUser_thenReturns401() throws Exception {
            // Given
            String fakeToken = "fake.jwt.token";

            // When / Then
            mockMvc.perform(get("/api/auth/me")
                            .header("Authorization", "Bearer " + fakeToken))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ========================
    // RBAC TESTS
    // ========================

    @Nested
    @DisplayName("GET /api/admin/users (RBAC)")
    class AdminEndpoint {

        @Test
        @DisplayName("Given ADMIN token, when accessing admin endpoint, then returns 200")
        void givenAdminToken_whenAccessingAdminEndpoint_thenReturns200() throws Exception {
            // Given
            String adminToken = createUserAndGetToken("admin", "admin@example.com", "ROLE_ADMIN");

            // When / Then
            mockMvc.perform(get("/api/admin/users")
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray());
        }

        @Test
        @DisplayName("Given VIEWER token, when accessing admin endpoint, then returns 403")
        void givenViewerToken_whenAccessingAdminEndpoint_thenReturns403() throws Exception {
            // Given
            String viewerToken = createUserAndGetToken("viewer", "viewer@example.com", "ROLE_VIEWER");

            // When / Then
            mockMvc.perform(get("/api/admin/users")
                            .header("Authorization", "Bearer " + viewerToken))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Given EDITOR token, when accessing admin endpoint, then returns 403")
        void givenEditorToken_whenAccessingAdminEndpoint_thenReturns403() throws Exception {
            // Given
            String editorToken = createUserAndGetToken("editor", "editor@example.com", "ROLE_EDITOR");

            // When / Then
            mockMvc.perform(get("/api/admin/users")
                            .header("Authorization", "Bearer " + editorToken))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Given no token, when accessing admin endpoint, then returns 401")
        void givenNoToken_whenAccessingAdminEndpoint_thenReturns401() throws Exception {
            // Given — no Authorization header

            // When / Then
            mockMvc.perform(get("/api/admin/users"))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ========================
    // HELPER METHODS
    // ========================

    private void createUser(String username, String email, String roleName) {
        ERole eRole = ERole.valueOf(roleName);
        Role role = roleRepository.findByName(eRole)
                .orElseThrow(() -> new RuntimeException("Role not found: " + roleName));

        User user = User.builder()
                .username(username)
                .email(email)
                .password(passwordEncoder.encode("password123"))
                .roles(Set.of(role))
                .build();

        userRepository.save(user);
    }

    private String createUserAndGetToken(String username, String email, String roleName) throws Exception {
        createUser(username, email, roleName);

        LoginRequest loginRequest = LoginRequest.builder()
                .username(username)
                .password("password123")
                .build();

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn();

        return jsonMapper.readTree(result.getResponse().getContentAsString())
                .get("token").asString();
    }
}
