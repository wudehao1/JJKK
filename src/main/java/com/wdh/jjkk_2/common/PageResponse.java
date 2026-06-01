package com.wdh.jjkk_2.common;

import java.util.List;

/**
 * 通用分页响应对象。
 *
 * items 放当前页数据，page/size 保留前端请求后的实际分页参数，total 表示符合条件的
 * 总记录数。列表接口统一返回这个结构，前端就可以复用同一套分页、懒加载和空状态逻辑。
 */
public record PageResponse<T>(
        List<T> items,
        int page,
        int size,
        long total
) {
}

