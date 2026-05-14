package com.ai.codeplatform.core;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@Slf4j
public class CancelGenerationManager {

    private final ConcurrentHashMap<Long, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();

    public void register(Long appId) {
        cancelFlags.put(appId, new AtomicBoolean(false));
    }

    public void cancel(Long appId) {
        AtomicBoolean flag = cancelFlags.get(appId);
        if (flag != null) {
            flag.set(true);
        }
    }

    public boolean isCancelled(Long appId) {
        AtomicBoolean flag = cancelFlags.get(appId);
        return flag != null && flag.get();
    }

    public void remove(Long appId) {
        cancelFlags.remove(appId);
    }
}
