package com.dianping.xpro.mq;

/**
 * RocketMQ 4.x 语义下的 18 个固定延迟级别（1s ~ 2h）。
 * 选择不超过剩余等待时间的最大级别，让消息在截止时间前后分段唤醒；
 * 消费端每次以绝对 pay_deadline 校验，未到期继续按剩余时间重投。
 */
public final class DelayLevels {

    private static final long[] LEVEL_SECONDS = {
            1, 5, 10, 30, 60, 120, 180, 240, 300, 360, 420, 480, 540, 600, 1200, 1800, 3600, 7200
    };

    /**
     * 返回不超过剩余毫秒数的最大延迟级别（1~18）。
     * 不足 1 秒仍返回级别 1；超过 2 小时返回级别 18，之后继续分段重投。
     */
    public static int forRemainingMillis(long remainingMillis) {
        long seconds = Math.max(1, Math.floorDiv(remainingMillis, 1000));
        for (int i = LEVEL_SECONDS.length - 1; i >= 0; i--) {
            if (LEVEL_SECONDS[i] <= seconds) {
                return i + 1;
            }
        }
        return 1;
    }

    private DelayLevels() {
    }
}
