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

    /** 单实例商铺本地缓存固定容量，作为双层缓存容量实验的统一口径。 */
    public static final Long LOCAL_SHOP_CACHE_MAXIMUM_SIZE = 10_000L;
    /** 单实例商铺本地缓存固定写入过期时间，单位分钟。 */
    public static final Long LOCAL_SHOP_CACHE_TTL = 5L;

    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;

    /** 秒杀库存键前缀，完整键为 seckill:stock:{voucherId}。 */
    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    /** 秒杀下单资格键前缀，完整键为 seckill:order:{voucherId}，类型为 HASH：userId -> orderId。 */
    public static final String SECKILL_ORDER_KEY = "seckill:order:";
    /** 秒杀预占事务结果键前缀，完整键为 seckill:tx:{orderId}。 */
    public static final String SECKILL_TX_KEY = "seckill:tx:";
    /**
     * 预占事务结果保留期，单位小时。
     * 必须大于「事务回查窗口」与「半消息发送重试窗口」的较大值：键过期后回查会读到无记录并
     * 返回 UNKNOWN，已预占成功的请求将随回查耗尽被丢弃，且不留痕迹。
     */
    public static final Long SECKILL_TX_TTL = 24L;
    /** 预占成功标记，回查据此返回 COMMIT。 */
    public static final String SECKILL_TX_SUCCESS = "SUCCESS";
    /** 明确业务拒绝标记，回查据此返回 ROLLBACK；执行异常不得写入该值。 */
    public static final String SECKILL_TX_REJECTED = "REJECTED";
    /** 秒杀用户频控键前缀，完整键为 seckill:rate:{voucherId}:{userId}，类型为 ZSET：请求标识 -> 请求时刻(ms)。 */
    public static final String SECKILL_RATE_KEY = "seckill:rate:";
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";
}
