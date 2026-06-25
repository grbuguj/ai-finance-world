package com.aifinance.domain.ledger;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;

public interface LedgerRepository extends JpaRepository<LedgerEntry, Long> {

    // 복식부기 무결성 검증용 - 차변/대변 타입별 총합
    @Query("select coalesce(sum(l.amount), 0) from LedgerEntry l where l.type = :type")
    BigDecimal sumByType(@Param("type") EntryType type);
}
