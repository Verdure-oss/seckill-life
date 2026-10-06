package com.hmdp.utils;

public class RedisConstants {

    // Bloom filter names
    public static final String BLOOM_FILTER_SHOP = "bloom:shop";
    public static final String BLOOM_FILTER_VOUCHER = "bloom:voucher";

    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 36000L;

    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final Long CACHE_SHOP_MIN_TTL = 27L;  // 30 * 0.9
    public static final Long CACHE_SHOP_MAX_TTL = 33L;  // 30 * 1.1
    public static final String CACHE_SHOP_KEY = "cache:shop:";
    public static final String CACHE_SHOP_TYPE_KEY = "cache:shop-type:";
    public static final Long CACHE_SHOP_TYPE_TTL = 30L; // 分钟
    public static final String CACHE_VOUCHER_KEY = "cache:voucher:";

    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    /** 秒杀活动时间窗口元数据（Hash: begin/end，epoch 毫秒）*/
    public static final String SECKILL_META_KEY = "seckill:meta:";
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";
}
