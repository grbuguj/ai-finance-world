package com.aifinance.domain.transfer;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface TransferRepository extends JpaRepository<Transfer, Long> {

    // 대시보드 피드용 - 최신 20건
    List<Transfer> findTop20ByOrderByIdDesc();

    // 거래 이력 키셋 페이지네이션 - PK 인덱스만 타므로 페이지 깊이와 무관하게 일정
    List<Transfer> findAllByOrderByIdDesc(Pageable pageable);

    List<Transfer> findByIdLessThanOrderByIdDesc(Long beforeId, Pageable pageable);

    // 사유 미생성건 - 최신순 3건
    List<Transfer> findTop3ByDescriptionIsNullOrderByIdDesc();

    // 환불 대상 - 해당 계좌의 아직 환불되지 않은 최근 소비 5건
    List<Transfer> findTop5BySenderIdAndTxTypeAndRefundedFalseOrderByIdDesc(Long senderId, TransactionType txType);

    // 환불 시 원거래 행 잠금 (동시 중복 환불 방지)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Transfer t where t.id = :id")
    Optional<Transfer> findByIdWithLock(@Param("id") Long id);

    // 통계 - 총 거래액 합계 (초기 자본금 개설 거래 제외)
    @Query("select coalesce(sum(t.amount), 0) from Transfer t where t.txType <> com.aifinance.domain.transfer.TransactionType.OPENING")
    BigDecimal sumAmount();

    // 통계 - 거래 건수 (초기 자본금 개설 거래 제외)
    @Query("select count(t) from Transfer t where t.txType <> com.aifinance.domain.transfer.TransactionType.OPENING")
    long countExcludingOpening();

    // 통계 - 유형별 건수
    @Query("select t.txType, count(t) from Transfer t group by t.txType")
    List<Object[]> countByTxType();
}
