package com.shardkv.service;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

@Component
public class StripedKeyLock {

    private static final int STRIPE_COUNT = 256;

    private final Lock[] stripes = new Lock[STRIPE_COUNT];

    public StripedKeyLock() {
        for (int stripe = 0; stripe < stripes.length; stripe++) {
            stripes[stripe] = new ReentrantLock();
        }
    }

    public <T> T withLock(String key, Supplier<T> operation) {
        Lock lock = stripes[stripeFor(key)];
        lock.lock();
        try {
            return operation.get();
        } finally {
            lock.unlock();
        }
    }

    private int stripeFor(String key) {
        int hash = key.hashCode();
        hash ^= hash >>> 16;
        return hash & (stripes.length - 1);
    }
}
