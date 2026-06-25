package com.aifinance.adapter.in.web;


import com.aifinance.application.port.out.AgentPort;
import com.aifinance.domain.agent.Agent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class AgentController {

    private final AgentPort agentPort;

    public AgentController(AgentPort agentPort) {
        this.agentPort = agentPort;
    }

    @GetMapping("/agents")
    public List<Agent> getAgents() {
        return agentPort.findAll();
    }

}
