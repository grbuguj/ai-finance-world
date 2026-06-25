package com.aifinance.domain.transfer;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.List;

public interface TransferRepository extends JpaRepository<Transfer, Long> {

    // 대시보드 피드용 - 최신 20건
    List<Transfer> findTop20ByOrderByIdDesc();

    // LLM 사유 미생성건 - 최신순 3건씩 처리 (오래된 백로그는 무시)
    List<Transfer> findTop3ByDescriptionIsNullOrderByIdDesc();

    // 통계 - 총 송금액 합계
    @Query("select coalesce(sum(t.amount), 0) from Transfer t")
    BigDecimal sumAmount();
}
