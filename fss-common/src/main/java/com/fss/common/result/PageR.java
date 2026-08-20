package com.fss.common.result;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/** 分页结果体，作为 {@link R} 的 data 使用。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageR<T> implements Serializable {

    private long    total;
    private long    page;
    private long    size;
    private List<T> list;

    public static <T> PageR<T> of(long total, long page, long size, List<T> list) {
        return new PageR<>(total, page, size, list);
    }

    public static <T> PageR<T> empty(long page, long size) {
        return new PageR<>(0, page, size, List.of());
    }
}
