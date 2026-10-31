package com.nextstep.api.applications;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ApplicationRepository extends JpaRepository<Application, UUID> {

    Optional<Application> findByIdAndOwnerId(UUID id, UUID ownerId);

    long countByOwnerId(UUID ownerId);

    List<Application> findAllByOwnerIdOrderByCreatedAtAsc(UUID ownerId);

    @Query("""
            select count(a) from Application a
            where a.ownerId = :owner
              and lower(a.company) = lower(:company)
              and lower(a.jobTitle) = lower(:jobTitle)
              and (:excludeId is null or a.id <> :excludeId)
            """)
    long countDuplicates(@Param("owner") UUID owner, @Param("company") String company,
                         @Param("jobTitle") String jobTitle, @Param("excludeId") UUID excludeId);
}

