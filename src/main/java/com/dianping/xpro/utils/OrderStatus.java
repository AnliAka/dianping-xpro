package com.dianping.xpro.utils;

/**
 * 订单状态常量，与 tb_voucher_order.status 对应。
 * 状态机约束：待支付只能迁往已支付或已取消之一，迁移由条件 UPDATE 保证唯一胜者。
 */
public final class OrderStatus {

    /** 未支付（待支付）。 */
    public static final int PENDING_PAYMENT = 1;
    /** 已支付。 */
    public static final int PAID = 2;
    /** 已取消（用户主动取消或超时关单，两者共用）。 */
    public static final int CANCELLED = 4;

    private OrderStatus() {
    }
}
