package com.metis.engine.api;

import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bounded, restartable polling. Transactional row claims coordinate multiple application workers. */
@Component
@ConditionalOnProperty(name="metis.rules.cpu.enabled",havingValue="true",matchIfMissing=true)
public class RulesCpuRunner {
    private final RulesRepository repository;
    private final RulesService rules;
    public RulesCpuRunner(RulesRepository repository,RulesService rules) { this.repository=repository;this.rules=rules; }
    @Scheduled(fixedDelayString="${metis.rules.cpu.tick-ms:500}",initialDelayString="${metis.rules.cpu.initial-delay-ms:1000}")
    public void tick() {
        for(var id:repository.cpuQueue())try { rules.advanceCpu(id); }
        catch(RuntimeException retryable) { LoggerFactory.getLogger(RulesCpuRunner.class).debug("Rules CPU transaction will retry for session {}",id); }
    }
}
