package com.docvault.security;

import com.docvault.entity.Role;
import com.docvault.entity.User;
import com.docvault.enums.ERole;
import com.docvault.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserDetailsServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserDetailsServiceImpl userDetailsService;

    private User user;

    @BeforeEach
    void setUp() {
        Role viewerRole = Role.builder().id(1L).name(ERole.ROLE_VIEWER).build();

        user = User.builder()
                .id(1L)
                .username("testuser")
                .email("test@example.com")
                .password("hashedpassword")
                .enabled(true)
                .roles(Set.of(viewerRole))
                .build();
    }

    @Nested
    @DisplayName("Load User By Username")
    class LoadUserByUsername {

        @Test
        @DisplayName("Given user exists in database, when loading by username, then returns UserDetails with correct fields")
        void givenUserExistsInDatabase_whenLoadingByUsername_thenReturnsUserDetailsWithCorrectFields() {
            // Given
            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(user));

            // When
            UserDetails result = userDetailsService.loadUserByUsername("testuser");

            // Then
            assertThat(result.getUsername()).isEqualTo("testuser");
            assertThat(result.getPassword()).isEqualTo("hashedpassword");
            assertThat(result.isEnabled()).isTrue();
            assertThat(result.getAuthorities()).hasSize(1);
            assertThat(result.getAuthorities().iterator().next().getAuthority())
                    .isEqualTo("ROLE_VIEWER");
        }

        @Test
        @DisplayName("Given user exists with multiple roles, when loading by username, then returns all authorities")
        void givenUserExistsWithMultipleRoles_whenLoadingByUsername_thenReturnsAllAuthorities() {
            // Given
            Role adminRole = Role.builder().id(2L).name(ERole.ROLE_ADMIN).build();
            Role viewerRole = Role.builder().id(1L).name(ERole.ROLE_VIEWER).build();
            user.setRoles(Set.of(adminRole, viewerRole));
            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(user));

            // When
            UserDetails result = userDetailsService.loadUserByUsername("testuser");

            // Then
            assertThat(result.getAuthorities()).hasSize(2);
            assertThat(result.getAuthorities())
                    .extracting("authority")
                    .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_VIEWER");
        }

        @Test
        @DisplayName("Given user does not exist, when loading by username, then throws UsernameNotFoundException")
        void givenUserDoesNotExist_whenLoadingByUsername_thenThrowsUsernameNotFoundException() {
            // Given
            when(userRepository.findByUsername("nonexistent")).thenReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> userDetailsService.loadUserByUsername("nonexistent"))
                    .isInstanceOf(UsernameNotFoundException.class)
                    .hasMessage("User not found with username: nonexistent");
        }
    }
}
