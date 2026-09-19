package com.dianping.xpro.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 订单 Outbox：与业务写入同事务落库，事务提交后由定时 relay 发布到 MQ。
 * 保证"订单已落库但提醒消息丢失"不会发生——relay 未发布前记录一直在，可反复重试。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_order_outbox")
public class OrderOutbox implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 关联订单 id。 */
    private Long orderId;

    /** 消息类型，见 MqConstants 的 OUTBOX_TYPE_* 常量。 */
    private String type;

    /** 消息体 JSON，relay 原样发布。 */
    private String payload;

    /** 0 待发布；1 已发布。发布失败保持 0，由下一轮 relay 重试（消费端幂等兜底重复）。 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime publishTime;
}
