package com.dianping.xpro.service;

/**
 * 关单服务。支付、用户取消、超时关单三方竞争同一个"待支付→已取消"迁移，
 * 唯一胜者由条件 UPDATE 保证；此处承载胜者之后的库存归还与释放 Outbox。
 */
public interface IOrderCloseService {

    /**
     * 关闭订单：status 1→4，归还数据库库存，写入预占释放 Outbox。
     *
     * @param requireExpired true 表示超时关单语义，仅在 pay_deadline 已过时执行；
     *                       false 表示用户主动取消，只要仍是待支付即可
     * @return true 表示本次调用完成迁移（是唯一胜者）；false 表示订单不存在、已被支付
     *         或已被其他路径取消（幂等放弃）
     */
    boolean closeOrder(Long orderId, boolean requireExpired);
}
