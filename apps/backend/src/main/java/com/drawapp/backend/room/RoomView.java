package com.drawapp.backend.room;

import java.time.Instant;

public record RoomView(Integer id, String slug, Instant createdAt) {

	public static RoomView of(Room room) {
		return new RoomView(room.getId(), room.getSlug(), room.getCreatedAt());
	}

}
