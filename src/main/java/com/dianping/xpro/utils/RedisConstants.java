package com.dianping.xpro.utils;

public class RedisConstants {
    /** 验证码键前缀，完整键为 login:code:{phone}。 */
    public static final String LOGIN_CODE_KEY = "login:code:";
    /** 验证码有效期，单位秒。发送时写入，登录校验时读取。 */
    public static final Long LOGIN_CODE_TTL = 30L;

    /** 登录用户信息的键前缀，完整键为 login:user:{token}。 */
    public static final String LOGIN_USER_KEY = "login:user:";
    /** 登录状态有效期，单位分钟；登录时写入、每次请求滑动续期。 */
    public static final Long LOGIN_USER_TTL = 30L;

    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";

    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;

    /** 秒杀库存键前缀，完整键为 seckill:stock:{voucherId}。 */
    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";
}
