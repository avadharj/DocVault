package com.docvault.service;

import com.docvault.dto.UserProfileResponse;
import com.docvault.entity.Role;
import com.docvault.entity.User;
import com.docvault.enums.ERole;
import com.docvault.exception.ResourceNotFoundException;
import com.docvault.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserService userService;

    private Role viewerRole;
    private Role adminRole;
    private User user1;
    private User user2;
    private LocalDateTime createdAt;

    @BeforeEach
    void setUp() {
        viewerRole = Role.builder().id(1L).name(ERole.ROLE_VIEWER).build();
        adminRole = Role.builder().id(2L).name(ERole.ROLE_ADMIN).build();
        createdAt = LocalDateTime.of(2025, 3, 1, 12, 0);

        user1 = User.builder()
                .id(1L)
                .username("viewer")
                .email("viewer@example.com")
                .password("hashedpassword")
                .roles(Set.of(viewerRole))
                .createdAt(createdAt)
                .build();

        user2 = User.builder()
                .id(2L)
                .username("admin")
                .email("admin@example.com")
                .password("hashedpassword")
                .roles(Set.of(adminRole, viewerRole))
                .createdAt(createdAt)
                .build();
    }

    @Nested
    @DisplayName("Get All Users")
    class GetAllUsers {

        @Test
        @DisplayName("Given users exist in database, when getting all users, then returns list of user profiles")
        void givenUsersExistInDatabase_whenGettingAllUsers_thenReturnsListOfUserProfiles() {
            // Given
            when(userRepository.findAll()).thenReturn(List.of(user1, user2));

            // When
            List<UserProfileResponse> response = userService.getAllUsers();

            // Then
            assertThat(response).hasSize(2);
            assertThat(response.get(0).getUsername()).isEqualTo("viewer");
            assertThat(response.get(0).getRoles()).containsExactly("ROLE_VIEWER");
            assertThat(response.get(1).getUsername()).isEqualTo("admin");
            assertThat(response.get(1).getRoles()).containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_VIEWER");
        }

        @Test
        @DisplayName("Given no users exist in database, when getting all users, then returns empty list")
        void givenNoUsersExistInDatabase_whenGettingAllUsers_thenReturnsEmptyList() {
            // Given
            when(userRepository.findAll()).thenReturn(Collections.emptyList());

            // When
            List<UserProfileResponse> response = userService.getAllUsers();

            // Then
            assertThat(response).isEmpty();
        }
    }

    @Nested
    @DisplayName("Get User By Id")
    class GetUserById {

        @Test
        @DisplayName("Given user exists, when getting user by id, then returns user profile")
        void givenUserExists_whenGettingUserById_thenReturnsUserProfile() {
            // Given
            when(userRepository.findById(1L)).thenReturn(Optional.of(user1));

            // When
            UserProfileResponse response = userService.getUserById(1L);

            // Then
            assertThat(response.getId()).isEqualTo(1L);
            assertThat(response.getUsername()).isEqualTo("viewer");
            assertThat(response.getEmail()).isEqualTo("viewer@example.com");
            assertThat(response.getRoles()).containsExactly("ROLE_VIEWER");
            assertThat(response.getCreatedAt()).isEqualTo(createdAt);
        }

        @Test
        @DisplayName("Given user does not exist, when getting user by id, then throws ResourceNotFoundException")
        void givenUserDoesNotExist_whenGettingUserById_thenThrowsResourceNotFoundException() {
            // Given
            when(userRepository.findById(999L)).thenReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> userService.getUserById(999L))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessage("User not found with id: 999");
        }
    }
}
