package com.aifinance.application.port.out;

import com.aifinance.domain.investment.Investment;

import java.util.List;
import java.util.Optional;

public interface InvestmentPort {
    Investment save(Investment investment);
    Optional<Investment> findById(Long id);
    Optional<Investment> findByIdWithLock(Long id);
    List<Investment> findMatured(long currentTick);
    long countOpen();
    void deleteAll();
}
