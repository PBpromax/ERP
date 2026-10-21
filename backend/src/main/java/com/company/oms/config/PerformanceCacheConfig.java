package com.company.oms.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.cache.annotation.EnableCaching;

import java.time.Duration;
import java.util.List;

@Configuration
@EnableCaching
public class PerformanceCacheConfig {
    @Bean
    public CacheManager cacheManager() {
        SimpleCacheManager manager = new SimpleCacheManager();
        manager.setCaches(List.of(
                cache("dashboard", Duration.ofSeconds(3), 4),
                cache("masterCustomers", Duration.ofMinutes(5), 16),
                cache("masterProducts", Duration.ofMinutes(5), 16),
                cache("salesOrderPages", Duration.ofMinutes(5), 256),
                cache("deliveryBoardPages", Duration.ofMinutes(5), 256)
        ));
        return manager;
    }

    private CaffeineCache cache(String name, Duration ttl, long maximumSize) {
        return new CaffeineCache(name, Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(maximumSize)
                .recordStats()
                .build());
    }
}
