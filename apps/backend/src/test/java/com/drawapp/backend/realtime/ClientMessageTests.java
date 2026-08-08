package com.drawapp.backend.realtime;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import com.drawapp.backend.realtime.ClientMessage.JoinRoom;
import com.drawapp.backend.realtime.ClientMessage.LeaveRoom;
import com.drawapp.backend.realtime.ClientMessage.Post;

import static org.assertj.core.api.Assertions.assertThat;

class ClientMessageTests {

	private final JsonMapper json = JsonMapper.builder().build();

	@Test
	void parsesEachMessageType() {
		assertThat(parse("{\"type\":\"join_room\",\"roomId\":\"7\"}")).isEqualTo(new JoinRoom("7"));
		assertThat(parse("{\"type\":\"leave_room\",\"roomId\":\"7\"}")).isEqualTo(new LeaveRoom("7"));
		assertThat(parse("{\"type\":\"chat\",\"roomId\":\"7\",\"message\":\"hi\"}")).isEqualTo(new Post("7", "hi"));
	}

	@Test
	void numericRoomIdsReadTheWayJavaScriptPrintsThem() {
		assertThat(parse("{\"type\":\"join_room\",\"roomId\":7}")).isEqualTo(new JoinRoom("7"));
		assertThat(parse("{\"type\":\"join_room\",\"roomId\":7.0}")).isEqualTo(new JoinRoom("7"));
		assertThat(parse("{\"type\":\"join_room\",\"roomId\":7.5}")).isEqualTo(new JoinRoom("7.5"));
	}

	@Test
	void rejectsWhatTheOldSchemaRejected() {
		assertThat(parse("[]")).isNull();
		assertThat(parse("\"join_room\"")).isNull();
		assertThat(parse("{\"type\":\"join_room\"}")).isNull();
		assertThat(parse("{\"type\":\"join_room\",\"roomId\":true}")).isNull();
		assertThat(parse("{\"type\":\"draw\",\"roomId\":\"7\"}")).isNull();
		assertThat(parse("{\"type\":\"chat\",\"roomId\":\"7\"}")).isNull();
		assertThat(parse("{\"type\":\"chat\",\"roomId\":\"7\",\"message\":{}}")).isNull();
		assertThat(parse("{\"type\":\"chat\",\"roomId\":\"7\",\"message\":\"" + "x".repeat(200_001) + "\"}")).isNull();
	}

	private ClientMessage parse(String text) {
		return ClientMessage.from(this.json.readTree(text));
	}

}
