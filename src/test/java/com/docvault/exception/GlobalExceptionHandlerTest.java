package com.docvault.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        request = new MockHttpServletRequest();
        request.setRequestURI("/api/test");
    }

    @Nested
    @DisplayName("UserAlreadyExistsException")
    class HandleUserAlreadyExists {

        @Test
        @DisplayName("Given duplicate username, when handled, then returns 409 with correct message")
        void givenDuplicateUsername_whenHandled_thenReturns409WithCorrectMessage() {
            // Given
            var ex = new UserAlreadyExistsException("Username is already taken");

            // When
            ResponseEntity<ErrorResponse> response = handler.handleUserAlreadyExists(ex, request);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getStatus()).isEqualTo(409);
            assertThat(response.getBody().getError()).isEqualTo("Conflict");
            assertThat(response.getBody().getMessage()).isEqualTo("Username is already taken");
            assertThat(response.getBody().getPath()).isEqualTo("/api/test");
            assertThat(response.getBody().getTimestamp()).isNotNull();
            assertThat(response.getBody().getFieldErrors()).isNull();
        }
    }

    @Nested
    @DisplayName("BadCredentialsException")
    class HandleBadCredentials {

        @Test
        @DisplayName("Given invalid credentials, when handled, then returns 401 with generic message")
        void givenInvalidCredentials_whenHandled_thenReturns401WithGenericMessage() {
            // Given
            var ex = new BadCredentialsException("Bad credentials");

            // When
            ResponseEntity<ErrorResponse> response = handler.handleBadCredentials(ex, request);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getStatus()).isEqualTo(401);
            assertThat(response.getBody().getError()).isEqualTo("Unauthorized");
            assertThat(response.getBody().getMessage()).isEqualTo("Invalid username or password");
            assertThat(response.getBody().getPath()).isEqualTo("/api/test");
        }
    }

    @Nested
    @DisplayName("ResourceNotFoundException")
    class HandleResourceNotFound {

        @Test
        @DisplayName("Given resource not found, when handled, then returns 404 with message")
        void givenResourceNotFound_whenHandled_thenReturns404WithMessage() {
            // Given
            var ex = new ResourceNotFoundException("User not found with id: 999");

            // When
            ResponseEntity<ErrorResponse> response = handler.handleResourceNotFound(ex, request);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getStatus()).isEqualTo(404);
            assertThat(response.getBody().getError()).isEqualTo("Not Found");
            assertThat(response.getBody().getMessage()).isEqualTo("User not found with id: 999");
            assertThat(response.getBody().getPath()).isEqualTo("/api/test");
        }
    }

    @Nested
    @DisplayName("AccessDeniedException")
    class HandleAccessDenied {

        @Test
        @DisplayName("Given insufficient permissions, when handled, then returns 403 with permission message")
        void givenInsufficientPermissions_whenHandled_thenReturns403WithPermissionMessage() {
            // Given
            var ex = new AccessDeniedException("Access is denied");

            // When
            ResponseEntity<ErrorResponse> response = handler.handleAccessDenied(ex, request);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getStatus()).isEqualTo(403);
            assertThat(response.getBody().getError()).isEqualTo("Forbidden");
            assertThat(response.getBody().getMessage())
                    .isEqualTo("You do not have permission to access this resource");
        }
    }

    @Nested
    @DisplayName("MethodArgumentNotValidException")
    class HandleValidationErrors {

        @Test
        @DisplayName("Given invalid request fields, when handled, then returns 400 with field-level errors")
        void givenInvalidRequestFields_whenHandled_thenReturns400WithFieldLevelErrors() {
            // Given
            var bindingResult = new BeanPropertyBindingResult(new Object(), "signupRequest");
            bindingResult.addError(new FieldError(
                    "signupRequest", "username", "Username is required"));
            bindingResult.addError(new FieldError(
                    "signupRequest", "email", "Email must be valid"));

            var ex = new MethodArgumentNotValidException(null, bindingResult);

            // When
            ResponseEntity<ErrorResponse> response = handler.handleValidationErrors(ex, request);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getStatus()).isEqualTo(400);
            assertThat(response.getBody().getError()).isEqualTo("Bad Request");
            assertThat(response.getBody().getMessage()).isEqualTo("Validation failed");
            assertThat(response.getBody().getFieldErrors())
                    .containsEntry("username", "Username is required")
                    .containsEntry("email", "Email must be valid");
        }

        @Test
        @DisplayName("Given single invalid field, when handled, then returns 400 with one field error")
        void givenSingleInvalidField_whenHandled_thenReturns400WithOneFieldError() {
            // Given
            var bindingResult = new BeanPropertyBindingResult(new Object(), "loginRequest");
            bindingResult.addError(new FieldError(
                    "loginRequest", "password", "Password is required"));

            var ex = new MethodArgumentNotValidException(null, bindingResult);

            // When
            ResponseEntity<ErrorResponse> response = handler.handleValidationErrors(ex, request);

            // Then
            assertThat(response.getBody().getFieldErrors()).hasSize(1)
                    .containsEntry("password", "Password is required");
        }
    }

    @Nested
    @DisplayName("Generic Exception")
    class HandleGenericException {

        @Test
        @DisplayName("Given unexpected error, when handled, then returns 500 with generic message")
        void givenUnexpectedError_whenHandled_thenReturns500WithGenericMessage() {
            // Given
            var ex = new RuntimeException("Something broke internally");

            // When
            ResponseEntity<ErrorResponse> response = handler.handleGenericException(ex, request);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getStatus()).isEqualTo(500);
            assertThat(response.getBody().getError()).isEqualTo("Internal Server Error");
            assertThat(response.getBody().getMessage()).isEqualTo("An unexpected error occurred");
        }

        @Test
        @DisplayName("Given NullPointerException, when handled, then returns 500 without leaking details")
        void givenNullPointerException_whenHandled_thenReturns500WithoutLeakingDetails() {
            // Given
            var ex = new NullPointerException("user.getName()");

            // When
            ResponseEntity<ErrorResponse> response = handler.handleGenericException(ex, request);

            // Then
            assertThat(response.getBody().getMessage())
                    .isEqualTo("An unexpected error occurred")
                    .doesNotContain("NullPointerException")
                    .doesNotContain("user.getName()");
        }
    }
}
