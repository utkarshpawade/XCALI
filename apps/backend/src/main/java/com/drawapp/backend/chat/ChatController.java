package com.drawapp.backend.chat;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.drawapp.backend.common.ApiException;
import com.drawapp.backend.room.RoomService;

@RestController
public class ChatController {

	private final ChatService chatService;

	public ChatController(ChatService chatService) {
		this.chatService = chatService;
	}

	public record ChatView(Integer id, Integer roomId, String message, String userId) {

		static ChatView of(Chat chat) {
			return new ChatView(chat.getId(), chat.getRoomId(), chat.getMessage(), chat.getUserId());
		}

	}

	public record ChatHistoryResponse(List<ChatView> messages) {
	}

	@GetMapping("/chats/{roomId}")
	public ChatHistoryResponse history(@PathVariable String roomId) {
		Integer id = RoomService.parseId(roomId);
		if (id == null) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid room id");
		}
		return new ChatHistoryResponse(this.chatService.history(id).stream().map(ChatView::of).toList());
	}

}
