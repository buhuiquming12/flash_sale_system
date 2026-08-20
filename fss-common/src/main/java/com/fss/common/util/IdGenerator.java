package com.fss.common.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 业务号生成器。
 *
 * <pre>
 * 订单号  O + yyyyMMddHHmmss + 3位机器号 + 6位序列
 * 请求号  R + yyyyMMddHHmmss + 3位机器号 + 6位序列
 * 支付号  P + yyyyMMddHHmmss + 3位机器号 + 6位序列
 * 链路号  32 位十六进制
 * 消息号  M + yyyyMMddHHmmss + 3位机器号 + 6位序列
 * </pre>
 *
 * <p><b>为什么不用纯自增 ID 做业务号</b>：会暴露订单量，且分库后冲突。
 * <b>为什么不用 UUID</b>：无序，作为二级索引会导致 B+ 树频繁页分裂。
 *
 * <p><b>为什么尾段用递增序列而不是随机数</b>：设计文档原本写的是"6 位随机"，
 * 但在 10000 QPS 下，同一秒同一机器内 10000 个 6 位随机数发生碰撞的概率约 5%
 * （生日问题），碰撞会表现为请求号重复被拒或唯一键冲突。改用进程内单调序列后，
 * 同机同秒内不可能重复，只有单机单秒超过 100 万请求才会绕回。
 */
public final class IdGenerator {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /** 3 位机器号，来自环境变量 FSS_MACHINE_ID，缺省由主机名散列得到 */
    private static final String MACHINE_ID = resolveMachineId();

    private static final AtomicLong SEQ = new AtomicLong(
            ThreadLocalRandom.current().nextInt(1_000_000));

    private IdGenerator() {
    }

    public static String orderNo() {
        return build('O');
    }

    public static String requestNo() {
        return build('R');
    }

    public static String payNo() {
        return build('P');
    }

    public static String msgId() {
        return build('M');
    }

    /** 模拟渠道的外部交易号 */
    public static String outTradeNo() {
        return "MOCK" + build('T');
    }

    public static String traceId() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return hex(r.nextLong()) + hex(r.nextLong());
    }

    /** 秒杀令牌用的随机串 */
    public static String nonce() {
        return hex(ThreadLocalRandom.current().nextLong());
    }

    public static String machineId() {
        return MACHINE_ID;
    }

    private static String build(char prefix) {
        long seq = Math.floorMod(SEQ.getAndIncrement(), 1_000_000L);
        return new StringBuilder(24)
                .append(prefix)
                .append(LocalDateTime.now().format(TS))
                .append(MACHINE_ID)
                .append(String.format("%06d", seq))
                .toString();
    }

    private static String hex(long v) {
        char[] out = new char[16];
        for (int i = 15; i >= 0; i--) {
            out[i] = HEX[(int) (v & 0xF)];
            v >>>= 4;
        }
        return new String(out);
    }

    private static String resolveMachineId() {
        String env = System.getenv("FSS_MACHINE_ID");
        if (env == null || env.isBlank()) {
            env = System.getProperty("fss.machine-id");
        }
        if (env != null && !env.isBlank()) {
            try {
                return String.format("%03d", Math.floorMod(Integer.parseInt(env.trim()), 1000));
            } catch (NumberFormatException ignored) {
                // 非数字则退化为散列
            }
        }
        String host = System.getenv("HOSTNAME");
        if (host == null || host.isBlank()) {
            host = System.getenv("COMPUTERNAME");
        }
        int h = host == null ? ThreadLocalRandom.current().nextInt() : host.hashCode();
        return String.format("%03d", Math.floorMod(h, 1000));
    }
}
