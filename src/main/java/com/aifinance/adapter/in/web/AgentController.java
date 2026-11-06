package com.aifinance.adapter.in.web;

import com.aifinance.application.usecase.AgentUseCase;
import com.aifinance.domain.agent.Agent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class AgentController {

    private final AgentUseCase agentUseCase;

    public AgentController(AgentUseCase agentUseCase) {
        this.agentUseCase = agentUseCase;
    }

    @GetMapping("/agents")
    public List<Agent> getAgents() {
        return agentUseCase.findAll();
    }

}
