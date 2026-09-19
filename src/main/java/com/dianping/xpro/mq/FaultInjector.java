package com.dianping.xpro.mq;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 仅在测试环境启用的确定性故障注入点，用于复现"崩溃窗口"。
 *
 * <p>人工 kill 进程几乎不可能稳定命中一个毫秒级的窗口，所以把两个窗口做成显式开关：
 * <ul>
 *   <li>{@code exit-after-reserve}：预占脚本已写下 SUCCESS、但尚未向 Broker 返回 COMMIT。
 *       此位置终止会让半消息保持未决，重启后只能靠事务回查收敛。</li>
 *   <li>{@code exit-after-consume-commit}：建单事务已提交、但尚未确认消费。
 *       此位置终止会让同一消息被重投，用来验证消费端幂等。</li>
 *   <li>{@code drop-close-remind}：Outbox relay 丢弃关单提醒消息（标记已发布但不发送）。
 *       用来验证延迟提醒丢失后，定时扫描兜底关单与库存回收。</li>
 * </ul>
 *
 * <p>总开关 {@code app.fault.enabled} 默认为 false，且 {@code target-voucher-id}
 * 可以把故障限定在指定券上——否则历史遗留的重试消息可能在错误的时刻触发它。
 * 打开方式（不改代码、不进版本库里的配置）：
 *
 * <pre>
 * java -jar target/dianping-xpro-0.0.1-SNAPSHOT.jar --server.port=8081 \
 *   --app.fault.enabled=true --app.fault.exit-after-reserve=true --app.fault.target-voucher-id=11
 * </pre>
 */
@Slf4j
@Component
public class FaultInjector {

    /** 模拟 SIGKILL：128 + 9，与容器被强杀时的退出码一致。 */
    private static final int KILL_EXIT_CODE = 137;

    private final boolean enabled;
    private final boolean exitAfterReserve;
    private final boolean exitAfterConsumeCommit;
    private final boolean dropCloseRemind;
    private final long targetVoucherId;

    public FaultInjector(
            @Value("${app.fault.enabled:false}") boolean enabled,
            @Value("${app.fault.exit-after-reserve:false}") boolean exitAfterReserve,
            @Value("${app.fault.exit-after-consume-commit:false}") boolean exitAfterConsumeCommit,
            @Value("${app.fault.drop-close-remind:false}") boolean dropCloseRemind,
            @Value("${app.fault.target-voucher-id:0}") long targetVoucherId) {
        this.enabled = enabled;
        this.exitAfterReserve = exitAfterReserve;
        this.exitAfterConsumeCommit = exitAfterConsumeCommit;
        this.dropCloseRemind = dropCloseRemind;
        this.targetVoucherId = targetVoucherId;
        if (enabled) {
            log.warn("故障注入已启用：exitAfterReserve={}, exitAfterConsumeCommit={}, dropCloseRemind={}, targetVoucherId={}",
                    exitAfterReserve, exitAfterConsumeCommit, dropCloseRemind, targetVoucherId);
        }
    }

    /**
     * 在"预占已成功、尚未返回 COMMIT"的位置终止进程。
     */
    public void exitAfterReserve(long orderId, long userId, long voucherId) {
        if (!fire(exitAfterReserve, voucherId)) {
            return;
        }
        log.error("【故障注入】预占已写入 SUCCESS，进程在此终止，不会向 Broker 返回 COMMIT。"
                + "半消息保持未决，重启后应由事务回查收敛。orderId={}, userId={}, voucherId={}",
                orderId, userId, voucherId);
        halt();
    }

    /**
     * 在"建单事务已提交、尚未确认消费"的位置终止进程。
     */
    public void exitAfterConsumeCommit(long orderId, long voucherId) {
        if (!fire(exitAfterConsumeCommit, voucherId)) {
            return;
        }
        log.error("【故障注入】建单事务已提交，进程在此终止，不会确认消费。"
                + "同一消息将被重投，用于验证消费端幂等。orderId={}, voucherId={}",
                orderId, voucherId);
        halt();
    }

    /**
     * 是否丢弃该券的关单提醒消息（不终止进程，只静默丢消息）。
     */
    public boolean dropCloseRemind(long voucherId) {
        return fire(dropCloseRemind, voucherId);
    }

    private boolean fire(boolean switchOn, long voucherId) {
        return enabled && switchOn && (targetVoucherId <= 0 || targetVoucherId == voucherId);
    }

    /**
     * 用 halt 而不是 exit：不执行 shutdown hook、不等待线程结束，最接近进程被强杀。
     * 终止前主动刷一次标准输出，避免最后一行日志停留在缓冲区里。
     */
    private void halt() {
        System.out.flush();
        System.err.flush();
        Runtime.getRuntime().halt(KILL_EXIT_CODE);
    }
}
