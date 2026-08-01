package com.drawapp.backend.chat;

import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

@Service
public class ChatService {

	private static final int MAX_HISTORY = 1000;

	private final ChatRepository chats;

	public ChatService(ChatRepository chats) {
		this.chats = chats;
	}

	/** Oldest first, so the canvas replays shapes in the order they were drawn. */
	public List<Chat> history(int roomId) {
		return this.chats.findByRoomIdOrderByIdAsc(roomId, Limit.of(MAX_HISTORY));
	}

	public Chat post(int roomId, String userId, String message) {
		return this.chats.save(new Chat(roomId, message, userId));
	}

}
