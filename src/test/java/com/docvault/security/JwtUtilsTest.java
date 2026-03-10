package com.docvault.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.security.Key;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JwtUtilsTest {

    private static final String SECRET = "dGhpcyBpcyBhIHZlcnkgc2VjcmV0IGtleSBmb3IgSldU";
    private static final long EXPIRATION_MS = 86400000; // 24 hours

    private JwtUtils jwtUtils;
    private UserDetails userDetails;

    @BeforeEach
    void setUp() {
        jwtUtils = new JwtUtils(SECRET, EXPIRATION_MS);

        userDetails = new UserDetailsImpl(
                1L,
                "testuser",
                "test@example.com",
                "hashedpassword",
                true,
                List.of(new SimpleGrantedAuthority("ROLE_VIEWER"))
        );
    }

    @Nested
    @DisplayName("Token Generation")
    class GenerateToken {

        @Test
        @DisplayName("Given valid user details, when generating token, then returns non-null token")
        void givenValidUserDetails_whenGeneratingToken_thenReturnsNonNullToken() {
            // Given — userDetails from setUp

            // When
            String token = jwtUtils.generateToken(userDetails);

            // Then
            assertThat(token).isNotNull().isNotBlank();
        }

        @Test
        @DisplayName("Given valid user details, when generating token, then token contains three parts")
        void givenValidUserDetails_whenGeneratingToken_thenTokenContainsThreeParts() {
            // Given — userDetails from setUp

            // When
            String token = jwtUtils.generateToken(userDetails);

            // Then — JWTs have header.payload.signature
            assertThat(token.split("\\.")).hasSize(3);
        }

        @Test
        @DisplayName("Given user with multiple roles, when generating token, then token is valid and contains correct username")
        void givenUserWithMultipleRoles_whenGeneratingToken_thenTokenIsValidAndContainsCorrectUsername() {
            // Given
            UserDetails multiRoleUser = new UserDetailsImpl(
                    2L, "admin", "admin@example.com", "hashedpassword", true,
                    List.of(
                            new SimpleGrantedAuthority("ROLE_ADMIN"),
                            new SimpleGrantedAuthority("ROLE_EDITOR")
                    )
            );

            // When
            String token = jwtUtils.generateToken(multiRoleUser);

            // Then
            String username = jwtUtils.extractUsername(token);
            assertThat(username).isEqualTo("admin");
        }
    }

    @Nested
    @DisplayName("Username Extraction")
    class ExtractUsername {

        @Test
        @DisplayName("Given a valid token, when extracting username, then returns correct username")
        void givenValidToken_whenExtractingUsername_thenReturnsCorrectUsername() {
            // Given
            String token = jwtUtils.generateToken(userDetails);

            // When
            String username = jwtUtils.extractUsername(token);

            // Then
            assertThat(username).isEqualTo("testuser");
        }

        @Test
        @DisplayName("Given tokens for different users, when extracting usernames, then returns respective usernames")
        void givenTokensForDifferentUsers_whenExtractingUsernames_thenReturnsRespectiveUsernames() {
            // Given
            UserDetails anotherUser = new UserDetailsImpl(
                    2L, "anotheruser", "another@example.com", "hashedpassword", true,
                    List.of(new SimpleGrantedAuthority("ROLE_EDITOR"))
            );
            String token1 = jwtUtils.generateToken(userDetails);
            String token2 = jwtUtils.generateToken(anotherUser);

            // When
            String username1 = jwtUtils.extractUsername(token1);
            String username2 = jwtUtils.extractUsername(token2);

            // Then
            assertThat(username1).isEqualTo("testuser");
            assertThat(username2).isEqualTo("anotheruser");
        }
    }

    @Nested
    @DisplayName("Token Validation")
    class ValidateToken {

        @Test
        @DisplayName("Given a valid token and matching user, when validating, then returns true")
        void givenValidTokenAndMatchingUser_whenValidating_thenReturnsTrue() {
            // Given
            String token = jwtUtils.generateToken(userDetails);

            // When
            boolean isValid = jwtUtils.validateToken(token, userDetails);

            // Then
            assertThat(isValid).isTrue();
        }

        @Test
        @DisplayName("Given a valid token and different user, when validating, then returns false")
        void givenValidTokenAndDifferentUser_whenValidating_thenReturnsFalse() {
            // Given
            String token = jwtUtils.generateToken(userDetails);
            UserDetails wrongUser = new UserDetailsImpl(
                    2L, "wronguser", "wrong@example.com", "hashedpassword", true,
                    List.of(new SimpleGrantedAuthority("ROLE_VIEWER"))
            );

            // When
            boolean isValid = jwtUtils.validateToken(token, wrongUser);

            // Then
            assertThat(isValid).isFalse();
        }

        @Test
        @DisplayName("Given an expired token, when validating, then returns false")
        void givenExpiredToken_whenValidating_thenReturnsFalse() {
            // Given — create JwtUtils with 0ms expiration to force instant expiry
            JwtUtils expiredJwtUtils = new JwtUtils(SECRET, 0);
            String token = expiredJwtUtils.generateToken(userDetails);

            // When
            boolean isValid = expiredJwtUtils.validateToken(token, userDetails);

            // Then
            assertThat(isValid).isFalse();
        }

        @Test
        @DisplayName("Given a malformed token, when validating, then returns false")
        void givenMalformedToken_whenValidating_thenReturnsFalse() {
            // Given
            String malformedToken = "not.a.valid.jwt.token";

            // When
            boolean isValid = jwtUtils.validateToken(malformedToken, userDetails);

            // Then
            assertThat(isValid).isFalse();
        }

        @Test
        @DisplayName("Given a token signed with a different key, when validating, then returns false")
        void givenTokenSignedWithDifferentKey_whenValidating_thenReturnsFalse() {
            // Given
            String differentSecret = "c29tZXRoaW5nY29tcGxldGVseWRpZmZlcmVudGtleTE=";
            Key differentKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(differentSecret));

            String tamperedToken = Jwts.builder()
                    .setSubject("testuser")
                    .setIssuedAt(new Date())
                    .setExpiration(new Date(System.currentTimeMillis() + EXPIRATION_MS))
                    .signWith(differentKey, SignatureAlgorithm.HS256)
                    .compact();

            // When
            boolean isValid = jwtUtils.validateToken(tamperedToken, userDetails);

            // Then
            assertThat(isValid).isFalse();
        }
    }
}
