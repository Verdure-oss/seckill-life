package com.hmdp.utils;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bloom Filter Utility
 * <p>
 * Provides fast negative checks to prevent cache penetration.
 * Supports multiple named filters stored in a concurrent map.
 * </p>
 */
@Component
public class BloomFilterUtils {

    private static final ConcurrentHashMap<String, BloomFilter<String>> filters = new ConcurrentHashMap<>();

    /**
     * Expected number of insertions.
     * A higher value increases memory usage but reduces false positive rate.
     */
    private static final int EXPECTED_INSERTIONS = 1000000;

    /**
     * Desired false positive probability.
     * Lower values increase memory usage.
     */
    private static final double FPP = 0.001;

    /**
     * Initialize some commonly used bloom filters.
     */
    @PostConstruct
    private void init() {
        // Shop id filter
        filters.put(RedisConstants.BLOOM_FILTER_SHOP, createFilter());
        // Voucher id filter
        filters.put(RedisConstants.BLOOM_FILTER_VOUCHER, createFilter());
    }

    /**
     * Create a new Guava BloomFilter instance.
     */
    private static BloomFilter<String> createFilter() {
        return BloomFilter.create(
                Funnels.stringFunnel(StandardCharsets.UTF_8),
                EXPECTED_INSERTIONS,
                FPP
        );
    }

    /**
     * Get the bloom filter by name. If not present, create one on demand.
     */
    public static BloomFilter<String> getFilter(String name) {
        return filters.computeIfAbsent(name, k -> createFilter());
    }

    /**
     * Add a key to the named filter.
     */
    public static void add(String name, String key) {
        getFilter(name).put(key);
    }

    /**
     * Check if a key might be contained in the filter.
     * @return true if it may exist; false definitely does not exist.
     */
    public static boolean mightContain(String name, String key) {
        return getFilter(name).mightContain(key);
    }
}