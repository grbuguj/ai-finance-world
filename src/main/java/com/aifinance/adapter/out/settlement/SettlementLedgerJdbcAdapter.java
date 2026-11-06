package com.aifinance.adapter.out.settlement;

import com.aifinance.application.port.out.SettlementLedgerPort;
import com.aifinance.external.settlement.SettlementInbox;
import com.aifinance.external.settlement.SettlementRecordRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 대사 쿼리 구현 — 애플리케이션 메모리로 두 장부를 다 올리지 않고, DB 에서 집합 연산(LEFT JOIN anti-join)으로 차이만 뽑는다.
 * 거래가 수백만 건이어도 결과(차이)만 네트워크를 탄다.
 *
 * 사용 인덱스: transfers(created_at), settlement_records(occurred_at), outbox_events(aggregate_id)
 */
@Component
public class SettlementLedgerJdbcAdapter implements SettlementLedgerPort {

    // 은행에는 있고 정산 장부에는 없는 거래. Outbox 상태로 '전달 중'과 '유실'을 구분한다.
    private static final String MISSING_BASE = """
            from transfers t
            left join settlement_records s on s.transfer_id = t.id
            left join outbox_events o on o.aggregate_id = t.id and o.aggregate_type = 'TRANSFER'
            where t.created_at >= ? and t.created_at < ?
              and s.transfer_id is null
            """;

    private static final String UNEXPECTED_BASE = """
            from settlement_records s
            left join transfers t on t.id = s.transfer_id
            where s.occurred_at >= ? and s.occurred_at < ?
              and t.id is null
            """;

    private static final String MISMATCH_BASE = """
            from transfers t
            join settlement_records s on s.transfer_id = t.id
            where t.created_at >= ? and t.created_at < ?
              and t.amount <> s.amount
            """;

    private final JdbcTemplate jdbc;
    private final SettlementRecordRepository repository;
    private final SettlementInbox inbox;

    public SettlementLedgerJdbcAdapter(JdbcTemplate jdbc, SettlementRecordRepository repository,
                                       SettlementInbox inbox) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.inbox = inbox;
    }

    @Override
    public Totals bankTotals(LocalDateTime from, LocalDateTime to) {
        return jdbc.queryForObject(
                "select count(*), coalesce(sum(amount), 0) from transfers where created_at >= ? and created_at < ?",
                (rs, i) -> new Totals(rs.getLong(1), rs.getBigDecimal(2)), ts(from), ts(to));
    }

    @Override
    public Totals settlementTotals(LocalDateTime from, LocalDateTime to) {
        return jdbc.queryForObject(
                "select count(*), coalesce(sum(amount), 0) from settlement_records where occurred_at >= ? and occurred_at < ?",
                (rs, i) -> new Totals(rs.getLong(1), rs.getBigDecimal(2)), ts(from), ts(to));
    }

    @Override
    public long countBreaks(BreakKind kind, LocalDateTime from, LocalDateTime to) {
        String sql = "select count(*) " + base(kind) + extraFilter(kind);
        Long n = jdbc.queryForObject(sql, Long.class, ts(from), ts(to));
        return n == null ? 0 : n;
    }

    @Override
    public List<Break> findBreaks(BreakKind kind, LocalDateTime from, LocalDateTime to, int limit) {
        String select = switch (kind) {
            case MISSING_IN_SETTLEMENT, IN_FLIGHT -> "select t.id, t.tx_type, t.amount, null ";
            case UNEXPECTED_IN_SETTLEMENT -> "select s.transfer_id, s.tx_type, null, s.amount ";
            case AMOUNT_MISMATCH -> "select t.id, t.tx_type, t.amount, s.amount ";
        };
        String sql = select + base(kind) + extraFilter(kind) + " order by 1 limit " + Math.max(1, limit);
        return jdbc.query(sql, (rs, i) -> new Break(rs.getLong(1), kind, rs.getString(2),
                rs.getBigDecimal(3), rs.getBigDecimal(4)), ts(from), ts(to));
    }

    @Override
    public long countDuplicateDeliveries() {
        return repository.countDuplicateDeliveries();
    }

    @Override
    public void deleteAll() {
        repository.deleteAllInBatch();
        inbox.resetCounters();
    }

    private static String base(BreakKind kind) {
        return switch (kind) {
            case MISSING_IN_SETTLEMENT, IN_FLIGHT -> MISSING_BASE;
            case UNEXPECTED_IN_SETTLEMENT -> UNEXPECTED_BASE;
            case AMOUNT_MISMATCH -> MISMATCH_BASE;
        };
    }

    private static String extraFilter(BreakKind kind) {
        return switch (kind) {
            case IN_FLIGHT -> " and o.status = 'PENDING'";
            case MISSING_IN_SETTLEMENT -> " and (o.status is null or o.status <> 'PENDING')";
            default -> "";
        };
    }

    private static Timestamp ts(LocalDateTime t) {
        return Timestamp.valueOf(t);
    }
}
