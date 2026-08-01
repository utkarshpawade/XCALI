package com.drawapp.backend.room;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RoomController {

	private final RoomService roomService;

	public RoomController(RoomService roomService) {
		this.roomService = roomService;
	}

	public record CreatedRoomResponse(Integer roomId, String slug) {
	}

	public record RoomListResponse(List<RoomView> rooms) {
	}

	public record RoomResponse(RoomView room) {
	}

	@PostMapping("/room")
	@ResponseStatus(HttpStatus.CREATED)
	public CreatedRoomResponse create(@Valid @RequestBody CreateRoomRequest request,
			@AuthenticationPrincipal String userId) {
		Room room = this.roomService.create(request.name(), userId);
		return new CreatedRoomResponse(room.getId(), room.getSlug());
	}

	/** Boards the caller created. */
	@GetMapping("/rooms")
	public RoomListResponse list(@AuthenticationPrincipal String userId) {
		return new RoomListResponse(this.roomService.listCreatedBy(userId).stream().map(RoomView::of).toList());
	}

	@GetMapping("/room/{slug}")
	public RoomResponse get(@PathVariable String slug) {
		return new RoomResponse(RoomView.of(this.roomService.getBySlug(slug)));
	}

}
