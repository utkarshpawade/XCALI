package com.drawapp.backend.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * An account. The sign-in identifier is stored in {@code email}; the API calls
 * it {@code username}.
 */
@Entity
@Table(name = "User")
public class User {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private String id;

	@Column(nullable = false, unique = true)
	private String email;

	/** bcrypt hash. */
	@Column(nullable = false)
	private String password;

	@Column(nullable = false)
	private String name;

	private String photo;

	protected User() {
	}

	public User(String email, String password, String name) {
		this.email = email;
		this.password = password;
		this.name = name;
	}

	public String getId() {
		return this.id;
	}

	public String getEmail() {
		return this.email;
	}

	public String getPassword() {
		return this.password;
	}

	public String getName() {
		return this.name;
	}

	public String getPhoto() {
		return this.photo;
	}

}
