package com.aifinance.adapter.in.web;

import com.aifinance.adapter.in.web.dto.FeedItem;
import com.aifinance.application.port.out.AccountPort;
import com.aifinance.application.port.out.BalanceCachePort;
import com.aifinance.application.port.out.TransferPort;
import com.aifinance.application.service.IdempotentExecutor;
import com.aifinance.application.usecase.InvestUseCase;
import com.aifinance.application.usecase.RefundUseCase;
import com.aifinance.application.usecase.SpendUseCase;
import com.aifinance.application.usecase.TransferUseCase;
import com.aifinance.domain.account.Account;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 거래 API. 시뮬레이션 엔진과 똑같은 UseCase 를 사람이 직접 호출해 볼 수 있다 (발표 시연 / 부하 테스트용).
 */
@RestController
public class TransferController {

    private final TransferPort transferPort;
    private final AccountPort accountPort;
    private final BalanceCachePort balanceCachePort;
    private final TransferUseCase transferUseCase;
    private final SpendUseCase spendUseCase;
    private final RefundUseCase refundUseCase;
    private final InvestUseCase investUseCase;

    public TransferController(TransferPort transferPort, AccountPort accountPort, BalanceCachePort balanceCachePort,
                              TransferUseCase transferUseCase, SpendUseCase spendUseCase,
                              RefundUseCase refundUseCase, InvestUseCase investUseCase) {
        this.transferPort = transferPort;
        this.accountPort = accountPort;
        this.balanceCachePort = balanceCachePort;
        this.transferUseCase = transferUseCase;
        this.spendUseCase = spendUseCase;
        this.refundUseCase = refundUseCase;
        this.investUseCase = investUseCase;
    }

    static final int MAX_PAGE_SIZE = 200;

    /**
     * 거래 이력 (최신순, 키셋 페이지네이션). 예전에는 원장 전체를 한 번에 돌려줘서
     * 거래가 쌓일수록 응답 크기와 메모리가 끝없이 커졌다. 다음 페이지는 응답의 마지막 id 를 beforeId 로 넘긴다.
     */
    @GetMapping("/transfers")
    public List<FeedItem> getTransfers(@RequestParam(defaultValue = "50") int limit,
                                       @RequestParam(required = false) Long beforeId) {
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("limit 은 1~" + MAX_PAGE_SIZE + " 사이여야 합니다");
        }
        return transferPort.findPage(beforeId, limit).stream().map(FeedItem::from).toList();
    }

    @GetMapping("/accounts")
    public List<Account> getAccounts() {
        return accountPort.findAll();
    }

    /** 잔액 조회: Redis 캐시 우선, 없으면 DB */
    @GetMapping("/accounts/{id}/balance")
    public Map<String, Object> balance(@PathVariable Long id) {
        Optional<BigDecimal> cached = balanceCachePort.get(id);
        BigDecimal balance = cached.orElseGet(() -> accountPort.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("계좌 없음: " + id))
                .getBalance());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accountId", id);
        result.put("balance", balance);
        result.put("source", cached.isPresent() ? "cache" : "db");
        return result;
    }

    /**
     * 송금. Idempotency-Key 헤더를 보내면 같은 키의 재요청은 새로 송금하지 않고 첫 결과를 돌려준다
     * (응답 헤더 Idempotent-Replayed: true).
     */
    @PostMapping("/transfers")
    public ResponseEntity<FeedItem> transfer(@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                             @RequestParam Long from, @RequestParam Long to, @RequestParam BigDecimal amount,
                                             @RequestParam(required = false) String description) {
        return toResponse(transferUseCase.transfer(idempotencyKey, from, to, amount, description));
    }

    @PostMapping("/spends")
    public ResponseEntity<FeedItem> spend(@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                          @RequestParam Long from, @RequestParam Long merchant, @RequestParam BigDecimal amount,
                                          @RequestParam(required = false) String description) {
        return toResponse(spendUseCase.spend(idempotencyKey, from, merchant, amount, description));
    }

    private static ResponseEntity<FeedItem> toResponse(IdempotentExecutor.Result result) {
        return ResponseEntity.ok()
                .header("Idempotent-Replayed", String.valueOf(result.replayed()))
                .body(FeedItem.from(result.transfer()));
    }

    @PostMapping("/refunds/{spendTransferId}")
    public FeedItem refund(@PathVariable Long spendTransferId) {
        return FeedItem.from(refundUseCase.refund(spendTransferId));
    }

    @PostMapping("/investments")
    public FeedItem invest(@RequestParam Long from, @RequestParam BigDecimal amount,
                           @RequestParam(defaultValue = "10") long holdTicks) {
        return FeedItem.from(investUseCase.invest(from, amount, 0, holdTicks).transfer());
    }

    @PostMapping("/investments/{id}/settle")
    public Map<String, Object> settle(@PathVariable Long id, @RequestParam(required = false) BigDecimal rate) {
        BigDecimal r = rate != null ? rate : investUseCase.sampleReturnRate();
        var transfer = investUseCase.settle(id, r);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("investmentId", id);
        result.put("returnRate", r.setScale(4, RoundingMode.HALF_UP));
        result.put("settlement", transfer == null ? null : FeedItem.from(transfer));
        return result;
    }
}
