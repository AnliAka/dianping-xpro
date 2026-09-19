-- 订单生命周期功能的一次性存量库修正。
--
-- 1. 截止时间保留毫秒，与 CloseRemindMessage 中的 epoch 毫秒保持一致。
-- 2. 已取消/超时订单保留历史记录但不再占购买资格；其他状态仍保持一人一单。
--
-- 本文件针对已经执行过初版订单生命周期 DDL 的数据库，只运行一次。
ALTER TABLE `tb_voucher_order`
  MODIFY COLUMN `pay_deadline` datetime(3) NULL DEFAULT NULL
    COMMENT '支付截止时间；NULL 为历史订单，不参与超时关单',
  DROP INDEX `uk_user_voucher`,
  ADD COLUMN `active_voucher_id` bigint(20) UNSIGNED
    GENERATED ALWAYS AS (IF(`status` = 4, NULL, `voucher_id`)) STORED
    COMMENT '未取消订单的券id，用于条件唯一约束',
  ADD UNIQUE INDEX `uk_user_active_voucher` (`user_id`, `active_voucher_id`),
  ADD INDEX `idx_user_voucher` (`user_id`, `voucher_id`);
