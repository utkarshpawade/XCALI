package com.drawapp.backend.room;

import java.util.List;

import org.jspecify.annotations.Nullable;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.drawapp.backend.common.ApiException;
import com.drawapp.backend.common.UniqueViolation;

@Service
public class RoomService {

	private static final int MAX_LISTED_ROOMS = 50;

	private final RoomRepository rooms;

	public RoomService(RoomRepository rooms) {
		this.rooms = rooms;
	}

	public Room create(String slug, String adminId) {
		try {
			return this.rooms.saveAndFlush(new Room(slug, adminId));
		}
		catch (DataIntegrityViolationException ex) {
			if (UniqueViolation.isCause(ex)) {
				throw new ApiException(HttpStatus.CONFLICT, "A room with this name already exists");
			}
			throw ex;
		}
	}

	/** Newest first. */
	public List<Room> listCreatedBy(String adminId) {
		return this.rooms.findByAdminIdOrderByCreatedAtDesc(adminId, Limit.of(MAX_LISTED_ROOMS));
	}

	public Room getBySlug(String slug) {
		return this.rooms.findBySlug(slug).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Room not found"));
	}

	/**
	 * Parses a room id as sent over HTTP or the socket: a positive integer,
	 * optionally as a string. Returns null for anything else.
	 */
	public static @Nullable Integer parseId(@Nullable String raw) {
		if (raw == null) {
			return null;
		}
		try {
			int id = Integer.parseInt(raw.strip());
			return (id > 0) ? id : null;
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

}
