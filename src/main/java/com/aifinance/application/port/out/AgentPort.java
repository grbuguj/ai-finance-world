package com.aifinance.application.port.out;

import com.aifinance.domain.agent.Agent;

import java.util.List;
import java.util.Optional;

public interface AgentPort {
    Optional<Agent> findByIdWithLock (Long id);
    Agent save(Agent agent);
    List<Agent> findAll();
}
