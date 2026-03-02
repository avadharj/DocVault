package com.docvault.filter;

import com.docvault.security.JwtUtils;
import com.docvault.security.UserDetailsImpl;
import com.docvault.security.UserDetailsServiceImpl;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    @Mock
    private JwtUtils jwtUtils;

    @Mock
    private UserDetailsServiceImpl userDetailsService;

    @Mock
    private FilterChain filterChain;

    @InjectMocks
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private UserDetailsImpl userDetails;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();

        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();

        userDetails = new UserDetailsImpl(
                1L, "testuser", "test@example.com", "hashedpassword", true,
                List.of(new SimpleGrantedAuthority("ROLE_VIEWER"))
        );
    }

    @Nested
    @DisplayName("Token Extraction")
    class TokenExtraction {

        @Test
        @DisplayName("Given no Authorization header, when filtering, then continues chain without authentication")
        void givenNoAuthorizationHeader_whenFiltering_thenContinuesChainWithoutAuthentication()
                throws ServletException, IOException {
            // Given — no header set

            // When
            jwtAuthenticationFilter.doFilterInternal(request, response, filterChain);

            // Then
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
            verify(filterChain).doFilter(request, response);
            verifyNoInteractions(jwtUtils, userDetailsService);
        }

        @Test
        @DisplayName("Given Authorization header without Bearer prefix, when filtering, then continues chain without authentication")
        void givenAuthorizationHeaderWithoutBearerPrefix_whenFiltering_thenContinuesChainWithoutAuthentication()
                throws ServletException, IOException {
            // Given
            request.addHeader("Authorization", "Basic somecredentials");

            // When
            jwtAuthenticationFilter.doFilterInternal(request, response, filterChain);

            // Then
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
            verify(filterChain).doFilter(request, response);
            verifyNoInteractions(jwtUtils, userDetailsService);
        }

        @Test
        @DisplayName("Given empty Bearer token, when filtering, then continues chain without authentication")
        void givenEmptyBearerToken_whenFiltering_thenContinuesChainWithoutAuthentication()
                throws ServletException, IOException {
            // Given
            request.addHeader("Authorization", "Bearer ");

            // When
            jwtAuthenticationFilter.doFilterInternal(request, response, filterChain);

            // Then
            verify(filterChain).doFilter(request, response);
        }
    }

    @Nested
    @DisplayName("Token Validation")
    class TokenValidation {

        @Test
        @DisplayName("Given valid Bearer token, when filtering, then sets authentication in SecurityContext")
        void givenValidBearerToken_whenFiltering_thenSetsAuthenticationInSecurityContext()
                throws ServletException, IOException {
            // Given
            request.addHeader("Authorization", "Bearer valid.jwt.token");
            when(jwtUtils.extractUsername("valid.jwt.token")).thenReturn("testuser");
            when(userDetailsService.loadUserByUsername("testuser")).thenReturn(userDetails);
            when(jwtUtils.validateToken("valid.jwt.token", userDetails)).thenReturn(true);

            // When
            jwtAuthenticationFilter.doFilterInternal(request, response, filterChain);

            // Then
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            assertThat(authentication).isNotNull();
            assertThat(authentication.getPrincipal()).isEqualTo(userDetails);
            assertThat(authentication.getAuthorities()).hasSize(1);
            assertThat(authentication.getAuthorities().iterator().next().getAuthority())
                    .isEqualTo("ROLE_VIEWER");
            verify(filterChain).doFilter(request, response);
        }

        @Test
        @DisplayName("Given invalid Bearer token, when filtering, then continues chain without authentication")
        void givenInvalidBearerToken_whenFiltering_thenContinuesChainWithoutAuthentication()
                throws ServletException, IOException {
            // Given
            request.addHeader("Authorization", "Bearer invalid.jwt.token");
            when(jwtUtils.extractUsername("invalid.jwt.token")).thenReturn("testuser");
            when(userDetailsService.loadUserByUsername("testuser")).thenReturn(userDetails);
            when(jwtUtils.validateToken("invalid.jwt.token", userDetails)).thenReturn(false);

            // When
            jwtAuthenticationFilter.doFilterInternal(request, response, filterChain);

            // Then
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
            verify(filterChain).doFilter(request, response);
        }

        @Test
        @DisplayName("Given token with null username, when filtering, then continues chain without authentication")
        void givenTokenWithNullUsername_whenFiltering_thenContinuesChainWithoutAuthentication()
                throws ServletException, IOException {
            // Given
            request.addHeader("Authorization", "Bearer bad.jwt.token");
            when(jwtUtils.extractUsername("bad.jwt.token")).thenReturn(null);

            // When
            jwtAuthenticationFilter.doFilterInternal(request, response, filterChain);

            // Then
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
            verify(filterChain).doFilter(request, response);
            verifyNoInteractions(userDetailsService);
        }

        @Test
        @DisplayName("Given valid token but SecurityContext already populated, when filtering, then does not overwrite existing authentication")
        void givenValidTokenButSecurityContextAlreadyPopulated_whenFiltering_thenDoesNotOverwriteExistingAuthentication()
                throws ServletException, IOException {
            // Given — pre-populate SecurityContext
            var existingAuth = new org.springframework.security.authentication
                    .UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
            SecurityContextHolder.getContext().setAuthentication(existingAuth);

            request.addHeader("Authorization", "Bearer valid.jwt.token");
            when(jwtUtils.extractUsername("valid.jwt.token")).thenReturn("testuser");

            // When
            jwtAuthenticationFilter.doFilterInternal(request, response, filterChain);

            // Then — should still be the original authentication
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(existingAuth);
            verify(filterChain).doFilter(request, response);
            verifyNoInteractions(userDetailsService);
        }

        @Test
        @DisplayName("Given token extraction throws exception, when filtering, then continues chain without authentication")
        void givenTokenExtractionThrowsException_whenFiltering_thenContinuesChainWithoutAuthentication()
                throws ServletException, IOException {
            // Given
            request.addHeader("Authorization", "Bearer broken.jwt.token");
            when(jwtUtils.extractUsername("broken.jwt.token"))
                    .thenThrow(new RuntimeException("Token parsing failed"));

            // When
            jwtAuthenticationFilter.doFilterInternal(request, response, filterChain);

            // Then
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
            verify(filterChain).doFilter(request, response);
        }
    }
}
