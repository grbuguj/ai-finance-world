package com.aifinance.application.port.out;

import com.aifinance.domain.transfer.TransactionType;
import com.aifinance.domain.transfer.Transfer;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface TransferPort {
    Transfer save(Transfer transfer);
    Optional<Transfer> findById(Long id);
    Optional<Transfer> findByIdWithLock(Long id);
    /** 최신순 키셋 페이지. beforeId 가 null 이면 첫 페이지. */
    List<Transfer> findPage(Long beforeId, int limit);
    List<Transfer> findRecent();
    List<Transfer> findRefundableSpends(Long accountId);
    long count();
    BigDecimal sumAmount();
    Map<TransactionType, Long> countByType();
    /** 이 소비 건에 대한 환불(REFUND)이 이미 있는가 */
    boolean existsRefundOf(Long spendTransferId);
    /** 시뮬레이션 초기화 전용 — 원장 불변성 예외. ArchitectureTest 가 이 이름 외의 삭제·수정 메서드를 금지한다. */
    void deleteAllForSimulationReset();
}
