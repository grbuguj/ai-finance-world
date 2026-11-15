package com.aifinance.domain.investment;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface InvestmentRepository extends JpaRepository<Investment, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Investment i where i.id = :id")
    Optional<Investment> findByIdWithLock(@Param("id") Long id);

    List<Investment> findByStatusAndMaturesAtTickLessThanEqualOrderByIdAsc(InvestmentStatus status, long tick);

    long countByStatus(InvestmentStatus status);
}
