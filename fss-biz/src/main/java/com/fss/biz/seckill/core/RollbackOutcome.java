package com.fss.biz.seckill.core;

/**
 * 库存回补（脚本 B / 脚本 C）的结果。
 *
 * <p><b>为什么不是 boolean。</b> 原先 {@code rollback} / {@code release} 返回 boolean，
 * 注释写着 "{@code false} 表示幂等命中（已回补过或状态不允许）"，但 Redis 调用失败的
 * catch 分支也返回 {@code false} —— 调用方拿到的 {@code false} 有两个完全相反的来源：
 *
 * <ul>
 *   <li>{@link #ALREADY_DONE}：脚本状态机判定之前已经回补过。重复投递的正常结果，
 *       什么都不用做。</li>
 *   <li>{@link #FAILED}：Redis 调用失败，回补<b>到底有没有发生是未知的</b>，
 *       库存可能正在泄漏。必须最高优先级告警 + 等对账修正。</li>
 * </ul>
 *
 * <p>分不清这两者，就会把"库存泄漏"处理成"一次正常的重复投递"。
 * 这不是假设：{@code MqResendJob} 的放弃分支确实在 {@code done == false} 时
 * 照样走 P2 并打印"已告警"，而真正为库存泄漏准备的 P1 告警写在一个永远不会进入的
 * catch 块里（{@code SeckillExecutor} 已经把异常吞掉了）。
 */
public enum RollbackOutcome {

    /** 本次真的回补了库存 */
    DONE,

    /** 幂等命中：之前已经回补过，或脚本状态机不允许再改。重复投递的正常结果 */
    ALREADY_DONE,

    /** Redis 调用失败或返回了预期外的码，回补结果未知，库存可能正在泄漏 */
    FAILED;

    /**
     * 是否"回补没成功且原因不是幂等"——即需要立刻告警的那一类。
     *
     * <p>提供这个方法是为了让调用点的分支写成 {@code if (outcome.isFailed())}，
     * 而不是各自去枚举三个值，漏一个不会有编译错误。
     */
    public boolean isFailed() {
        return this == FAILED;
    }
}
