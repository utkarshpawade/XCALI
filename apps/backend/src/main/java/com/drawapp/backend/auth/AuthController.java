package com.drawapp.backend.auth;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.drawapp.backend.auth.AuthService.Session;
import com.drawapp.backend.user.UserView;

@RestController
public class AuthController {

	private final AuthService authService;

	public AuthController(AuthService authService) {
		this.authService = authService;
	}

	public record SignupResponse(String userId, UserView user, String token) {
	}

	public record SigninResponse(String token, UserView user) {
	}

	public record CurrentUserResponse(UserView user) {
	}

	@PostMapping("/signup")
	@ResponseStatus(HttpStatus.CREATED)
	public SignupResponse signup(@Valid @RequestBody SignupRequest request) {
		Session session = this.authService.signup(request);
		return new SignupResponse(session.user().id(), session.user(), session.token());
	}

	@PostMapping("/signin")
	public SigninResponse signin(@Valid @RequestBody SigninRequest request) {
		Session session = this.authService.signin(request);
		return new SigninResponse(session.token(), session.user());
	}

	@GetMapping("/me")
	public CurrentUserResponse me(@AuthenticationPrincipal String userId) {
		return new CurrentUserResponse(this.authService.currentUser(userId));
	}

}
