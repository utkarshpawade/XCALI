package com.drawapp.backend.chat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One message posted to a room. On the drawing app each message is a shape,
 * serialized by the client as {@code {"shape": {...}}}; the server stores it
 * verbatim.
 */
@Entity
@Table(name = "Chat")
public class Chat {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Integer id;

	@Column(nullable = false)
	private Integer roomId;

	@Column(nullable = false)
	private String message;

	@Column(nullable = false)
	private String userId;

	protected Chat() {
	}

	public Chat(Integer roomId, String message, String userId) {
		this.roomId = roomId;
		this.message = message;
		this.userId = userId;
	}

	public Integer getId() {
		return this.id;
	}

	public Integer getRoomId() {
		return this.roomId;
	}

	public String getMessage() {
		return this.message;
	}

	public String getUserId() {
		return this.userId;
	}

}
