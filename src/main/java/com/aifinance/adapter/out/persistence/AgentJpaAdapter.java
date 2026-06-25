package com.aifinance.adapter.out.persistence;

import com.aifinance.application.port.out.AgentPort;
import com.aifinance.domain.agent.Agent;
import com.aifinance.domain.agent.AgentRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class AgentJpaAdapter implements AgentPort {

    private final AgentRepository agentRepository;

    public AgentJpaAdapter(AgentRepository agentRepository) {
        this.agentRepository = agentRepository;
    }

    @Override
    public Optional<Agent> findByIdWithLock (Long id) {
        return agentRepository.findByIdWithLock(id);
    }

    @Override
    public Agent save(Agent agent) {
        return agentRepository.save(agent);
    }

    @Override
    public List<Agent> findAll() {
        return agentRepository.findAll();
    }

}
