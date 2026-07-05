package com.gitnova.common;

/**
 * 用户上下文 — 基于 ThreadLocal 传递当前请求用户信息
 *
 * 💡 Design Note：Controller → Service 层需要知道"当前是谁在操作"，
 * 用 ThreadLocal 传递比每个方法都加 userId 参数更干净。
 * ⚠️ 注意请求结束后必须 remove()，防止线程池复用导致数据污染。
 */
public class UserContext {

    private static final ThreadLocal<Long> USER_ID_HOLDER = new ThreadLocal<>();
    private static final ThreadLocal<String> USERNAME_HOLDER = new ThreadLocal<>();

    public static void setUserId(Long userId) {
        USER_ID_HOLDER.set(userId);
    }

    public static Long getUserId() {
        return USER_ID_HOLDER.get();
    }

    public static void setUsername(String username) {
        USERNAME_HOLDER.set(username);
    }

    public static String getUsername() {
        return USERNAME_HOLDER.get();
    }

    public static void clear() {
        USER_ID_HOLDER.remove();
        USERNAME_HOLDER.remove();
    }
}
