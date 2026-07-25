package com.drawapp.backend.chat;

import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatRepository extends JpaRepository<Chat, Integer> {

	/** Served by the ("roomId", "id") index. */
	List<Chat> findByRoomIdOrderByIdAsc(Integer roomId, Limit limit);

}
