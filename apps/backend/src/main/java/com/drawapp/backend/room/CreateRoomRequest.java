package com.drawapp.backend.room;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** @param name the slug people will share to join the board */
public record CreateRoomRequest(@NotNull(message = "Required") @Size(min = 3,
		message = "Room name must be at least 3 characters") @Size(max = 20,
				message = "Room name must be at most 20 characters") @Pattern(regexp = "^[a-zA-Z0-9_-]+$",
						message = "Room name may only contain letters, numbers, hyphens and underscores") String name) {
}
