package com.aifinance.external.settlement;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SettlementRecordRepository extends JpaRepository<SettlementRecord, Long> {

    @Query("select coalesce(sum(s.deliveryCount - 1), 0) from SettlementRecord s")
    long countDuplicateDeliveries();
}
