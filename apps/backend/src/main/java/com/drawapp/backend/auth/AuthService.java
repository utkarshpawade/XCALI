package com.drawapp.backend.auth;

import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.drawapp.backend.common.ApiException;
import com.drawapp.backend.common.UniqueViolation;
import com.drawapp.backend.user.User;
import com.drawapp.backend.user.UserRepository;
import com.drawapp.backend.user.UserView;

@Service
public class AuthService {

	private final UserRepository users;

	private final PasswordEncoder passwordEncoder;

	private final JwtService jwtService;

	/**
	 * A hash of a value nobody knows. Compared against when the email is
	 * unknown, so signin takes the same time either way.
	 */
	private final String dummyPasswordHash;

	public AuthService(UserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService) {
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.jwtService = jwtService;
		this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
	}

	public record Session(UserView user, String token) {
	}

	public Session signup(SignupRequest request) {
		String passwordHash = this.passwordEncoder.encode(request.password());
		User user;
		try {
			user = this.users.saveAndFlush(new User(request.username(), passwordHash, request.name()));
		}
		catch (DataIntegrityViolationException ex) {
			if (UniqueViolation.isCause(ex)) {
				throw new ApiException(HttpStatus.CONFLICT, "An account with this email already exists");
			}
			throw ex;
		}
		return new Session(UserView.of(user), this.jwtService.issue(user.getId()));
	}

	public Session signin(SigninRequest request) {
		Optional<User> user = this.users.findByEmail(request.username());
		// Always run a comparison so the response time does not reveal whether
		// the account exists.
		boolean passwordMatches = this.passwordEncoder.matches(request.password(),
				user.map(User::getPassword).orElse(this.dummyPasswordHash));
		if (user.isEmpty() || !passwordMatches) {
			throw new ApiException(HttpStatus.FORBIDDEN, "Incorrect email or password");
		}
		return new Session(UserView.of(user.get()), this.jwtService.issue(user.get().getId()));
	}

	public UserView currentUser(String userId) {
		return this.users.findById(userId)
			.map(UserView::of)
			.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));
	}

}
