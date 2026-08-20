package com.fss.infra.cache;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 逻辑过期缓存包装。
 *
 * <p><b>为什么 {@code data} 是 String 而不是泛型 T</b>：
 * 泛型写法（{@code CacheWrapper<T>}）在反序列化时必须构造
 * {@code TypeReference<CacheWrapper<ActivityDetailVO>>}，每个业务类型一个匿名类，
 * 而且泛型擦除下一旦类型传错，错误会推迟到访问字段时才以 ClassCastException 冒出来。
 * 存成内层 JSON 原文之后，外层结构固定、反序列化只需要一个 Class，
 * 代价是多一层字符串编码——对一个 2 小时才重建一次的缓存完全不值得计较。
 *
 * <p>{@code data == null} 表示<b>空值缓存</b>：数据库里确实没这条数据。
 * 这一层是防穿透用的——不缓存"不存在"这个结论，攻击者用不存在的 ID
 * 就能让每个请求都打到数据库。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CacheWrapper {

    /** 内层业务对象的 JSON；null 表示空值缓存 */
    private String data;

    /**
     * 逻辑过期时刻（epoch millis）。
     *
     * <p>物理 TTL 比它长一段缓冲，保证逻辑过期后旧值仍然读得到，
     * 这样重建期间可以先返回旧值而不是让请求排队等回源。
     */
    private long expireAt;

    public boolean nullValue() {
        return data == null;
    }

    public boolean logicallyExpired() {
        return System.currentTimeMillis() > expireAt;
    }
}
