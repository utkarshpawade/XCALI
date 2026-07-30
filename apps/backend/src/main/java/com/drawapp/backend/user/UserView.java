package com.drawapp.backend.user;

/** The public part of an account; never includes the password hash. */
public record UserView(String id, String email, String name) {

	public static UserView of(User user) {
		return new UserView(user.getId(), user.getEmail(), user.getName());
	}

}
