package com.fss.common.context;

import com.fss.common.error.BizException;
import com.fss.common.error.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 当前请求的用户上下文。
 *
 * <p>由认证过滤器在放行前写入、在 finally 中清除。业务层通过它拿 userId，
 * 而不是从请求体里读——<b>请求体里的 userId 一律不可信</b>。
 *
 * <p>为什么不直接用 Spring Security 的 {@code SecurityContextHolder}：
 * 那会让 fss-biz 依赖 spring-security-core。认证过滤器会同时写入两者，
 * Security 负责 URL/方法级授权规则，业务层只从这里取身份。
 *
 * <p>线程池场景（异步任务、定时任务）不会有用户上下文，此时 {@link #userId()} 抛
 * {@code UNAUTHORIZED}，需要 userId 的逻辑必须由调用方显式传入。
 */
public final class UserContext {

    private static final ThreadLocal<Principal> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    @Getter
    @RequiredArgsConstructor
    public static class Principal {
        private final long   userId;
        private final String username;
        private final int    role;

        public boolean isAdmin() {
            return role == 1;
        }
    }

    public static void set(long userId, String username, int role) {
        HOLDER.set(new Principal(userId, username, role));
    }

    public static Principal current() {
        return HOLDER.get();
    }

    /** 已登录用户 ID。未登录直接抛 UNAUTHORIZED，不返回 null 让 NPE 在别处炸开 */
    public static long userId() {
        Principal p = HOLDER.get();
        if (p == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return p.getUserId();
    }

    public static Long userIdOrNull() {
        Principal p = HOLDER.get();
        return p == null ? null : p.getUserId();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
