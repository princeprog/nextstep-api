package com.nextstep.api.interviews;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface InterviewRepository extends JpaRepository<Interview, UUID> {

    Optional<Interview> findByIdAndOwnerId(UUID id, UUID ownerId);

    long countByOwnerId(UUID ownerId);

    List<Interview> findAllByOwnerIdOrderByCreatedAtAsc(UUID ownerId);
}

