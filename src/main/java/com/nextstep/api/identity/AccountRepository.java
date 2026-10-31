package com.nextstep.api.identity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    Optional<Account> findByEmailAndKind(String email, AccountKind kind);

    boolean existsByEmailAndKind(String email, AccountKind kind);

    @Query("""
            select a.id from Account a
            where a.kind = com.nextstep.api.identity.AccountKind.DEMO and a.demoExpiresAt <= :now
            order by a.demoExpiresAt
            """)
    List<UUID> findExpiredDemoIds(@Param("now") Instant now, Pageable pageable);

    @Query("""
            select a.id from Account a
            where a.kind = com.nextstep.api.identity.AccountKind.NORMAL and a.emailVerifiedAt is null
              and a.createdAt <= :cutoff
            order by a.createdAt
            """)
    List<UUID> findUnverifiedCreatedBefore(@Param("cutoff") Instant cutoff, Pageable pageable);
}

