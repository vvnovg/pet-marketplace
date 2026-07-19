package com.petmarketplace.application.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.petmarketplace.IntegrationTestBase;
import com.petmarketplace.application.auth.dto.LoginRequest;
import com.petmarketplace.application.auth.dto.RefreshRequest;
import com.petmarketplace.application.auth.dto.RegisterRequest;
import com.petmarketplace.application.auth.dto.TokenResponse;
import com.petmarketplace.application.user.dto.UserProfileResponse;
import com.petmarketplace.domain.user.entity.Role;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class AuthControllerTest extends IntegrationTestBase {

    private static final String PASSWORD = "Password1!";

    @Test
    void shouldRegisterNewUser() {
        String email = "new_user_" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        RegisterRequest request = new RegisterRequest(email, PASSWORD, null, "New", "User");

        ResponseEntity<Void> response = restClient.post()
                .uri("/auth/register")
                .body(request)
                .retrieve()
                .toBodilessEntity();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(userRepository.findByEmail(email)).isPresent();
    }

    @Test
    void shouldLoginVerifiedUser() {
        TestUser user = createUniqueUser(Role.BUYER);
        LoginRequest request = new LoginRequest(user.email(), user.password());

        ResponseEntity<TokenResponse> response = restClient.post()
                .uri("/auth/login")
                .body(request)
                .retrieve()
                .toEntity(TokenResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().accessToken()).isNotBlank();
        assertThat(response.getBody().refreshToken()).isNotBlank();
        assertThat(response.getBody().tokenType()).isEqualToIgnoringCase("Bearer");
        assertThat(response.getBody().expiresIn()).isPositive();
    }

    @Test
    void shouldRefreshAccessToken() {
        TestUser user = createUniqueUser(Role.BUYER);
        LoginRequest loginRequest = new LoginRequest(user.email(), user.password());

        ResponseEntity<TokenResponse> loginResponse = restClient.post()
                .uri("/auth/login")
                .body(loginRequest)
                .retrieve()
                .toEntity(TokenResponse.class);
        assertThat(loginResponse.getBody()).isNotNull();
        String refreshToken = loginResponse.getBody().refreshToken();

        RefreshRequest refreshRequest = new RefreshRequest(refreshToken);
        ResponseEntity<TokenResponse> refreshResponse = restClient.post()
                .uri("/auth/refresh")
                .body(refreshRequest)
                .retrieve()
                .toEntity(TokenResponse.class);

        assertThat(refreshResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(refreshResponse.getBody()).isNotNull();
        assertThat(refreshResponse.getBody().accessToken()).isNotBlank();
        assertThat(refreshResponse.getBody().refreshToken()).isNotBlank();
        assertThat(refreshResponse.getBody().accessToken())
                .isNotEqualTo(loginResponse.getBody().accessToken());
    }

    @Test
    void shouldGetCurrentProfile() {
        TestUser user = createUniqueUser(Role.BUYER);
        HttpHeaders headers = authHeader(user);

        ResponseEntity<UserProfileResponse> response = restClient.get()
                .uri("/users/me")
                .headers(httpHeaders -> httpHeaders.addAll(headers))
                .retrieve()
                .toEntity(UserProfileResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().email()).isEqualTo(user.email());
    }
}
