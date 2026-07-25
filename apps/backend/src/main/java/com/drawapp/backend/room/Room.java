package com.drawapp.backend.room;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/** A board. Its slug is the name people share to join it. */
@Entity
@Table(name = "Room")
public class Room {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Integer id;

	@Column(nullable = false, unique = true)
	private String slug;

	/**
	 * UTC wall-clock time in a {@code timestamp without time zone} column, the
	 * convention Prisma used. Kept as LocalDateTime so neither the driver nor
	 * the JVM's time zone shifts it.
	 */
	@Column(nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@Column(nullable = false)
	private String adminId;

	protected Room() {
	}

	public Room(String slug, String adminId) {
		this.slug = slug;
		this.adminId = adminId;
	}

	@PrePersist
	void onCreate() {
		if (this.createdAt == null) {
			this.createdAt = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
		}
	}

	public Integer getId() {
		return this.id;
	}

	public String getSlug() {
		return this.slug;
	}

	public Instant getCreatedAt() {
		return this.createdAt.toInstant(ZoneOffset.UTC);
	}

	public String getAdminId() {
		return this.adminId;
	}

}
