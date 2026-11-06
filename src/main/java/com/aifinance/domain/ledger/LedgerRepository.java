package com.aifinance.domain.ledger;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface LedgerRepository extends JpaRepository<LedgerEntry, Long> {

    // 복식부기 무결성 검증용 - 차변/대변 타입별 총합
    @Query("select coalesce(sum(l.amount), 0) from LedgerEntry l where l.type = :type")
    BigDecimal sumByType(@Param("type") EntryType type);

    // 계좌별·타입별 합계 [accountId, type, sum]
    @Query("select l.account.id, l.type, coalesce(sum(l.amount), 0) from LedgerEntry l group by l.account.id, l.type")
    List<Object[]> sumGroupByAccountAndType();

    /**
     * 무결성 검증 값을 "한 번의 SQL" 로 읽는다 → DB가 보장하는 하나의 일관된 스냅샷.
     * (쿼리를 여러 번 나눠 실행하면 그 사이 커밋된 거래 때문에, 데이터가 맞아도 불일치처럼 보일 수 있다)
     * [∑DEBIT, ∑CREDIT, 분개≠2건 거래 수, 전체 잔액 합, 잔액≠원장 계좌 수]
     */
    @Query(nativeQuery = true, value = """
            select
              (select coalesce(sum(l.amount), 0) from ledger_entries l where l.type = 'DEBIT'),
              (select coalesce(sum(l.amount), 0) from ledger_entries l where l.type = 'CREDIT'),
              (select count(*) from transfers t
                 where (select count(*) from ledger_entries l where l.transfer_id = t.id) <> 2),
              (select coalesce(sum(a.balance), 0) from accounts a),
              (select count(*) from accounts a
                 where a.balance <> coalesce((select sum(case when l.type = 'CREDIT' then l.amount else -l.amount end)
                                              from ledger_entries l where l.account_id = a.id), 0))
            """)
    List<Object[]> integritySnapshot();

    // 거래별 분개 건수가 2건이 아닌 거래 수 (항상 0이어야 함)
    @Query("select count(t) from Transfer t where (select count(l) from LedgerEntry l where l.transfer = t) <> 2")
    long countTransfersWithoutTwoEntries();
}
