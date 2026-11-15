package com.aifinance.adapter.out.persistence;

import com.aifinance.application.port.out.InvestmentPort;
import com.aifinance.domain.investment.Investment;
import com.aifinance.domain.investment.InvestmentRepository;
import com.aifinance.domain.investment.InvestmentStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class InvestmentJpaAdapter implements InvestmentPort {

    private final InvestmentRepository investmentRepository;

    public InvestmentJpaAdapter(InvestmentRepository investmentRepository) {
        this.investmentRepository = investmentRepository;
    }

    @Override
    public Investment save(Investment investment) {
        return investmentRepository.save(investment);
    }

    @Override
    public Optional<Investment> findById(Long id) {
        return investmentRepository.findById(id);
    }

    @Override
    public Optional<Investment> findByIdWithLock(Long id) {
        return investmentRepository.findByIdWithLock(id);
    }

    @Override
    public List<Investment> findMatured(long currentTick) {
        return investmentRepository.findByStatusAndMaturesAtTickLessThanEqualOrderByIdAsc(InvestmentStatus.OPEN, currentTick);
    }

    @Override
    public long countOpen() {
        return investmentRepository.countByStatus(InvestmentStatus.OPEN);
    }

    @Override
    public void deleteAll() {
        investmentRepository.deleteAllInBatch();
    }
}
