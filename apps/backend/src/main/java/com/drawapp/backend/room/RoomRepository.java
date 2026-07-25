package com.drawapp.backend.room;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoomRepository extends JpaRepository<Room, Integer> {

	Optional<Room> findBySlug(String slug);

	List<Room> findByAdminIdOrderByCreatedAtDesc(String adminId, Limit limit);

}
