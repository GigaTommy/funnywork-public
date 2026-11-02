package com.funnywork;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class Scheduler {
    private final ControlService control;
    private final boolean enabled;
    private final RecoveryGate recoveryGate;
    private final Set<String> dirty=ConcurrentHashMap.newKeySet();
    private long lastSweep=0;
    public Scheduler(ControlService control,@Value("${funnywork.scheduling-enabled:true}") boolean enabled,RecoveryGate recoveryGate) { this.control=control;this.enabled=enabled;this.recoveryGate=recoveryGate; }
    @TransactionalEventListener
    public void changed(TenantWorkChanged event) { if(enabled)dirty.add(event.tenant()); }
    @Scheduled(fixedDelayString="${funnywork.tick-ms:100}")
    public void tick() {
        if(!enabled || recoveryGate.failure()!=null)return;
        // Notifications coalesce; fallback sweeps recover lost/local-only hints and expire leases.
        // Remove before processing so a concurrent change is not lost.
        for(String tenant:dirty.toArray(String[]::new))if(dirty.remove(tenant))control.tickTenant(tenant);
        if(System.nanoTime()-lastSweep>=1_000_000_000L) {
            lastSweep=System.nanoTime();control.tick();
        }
    }
}
