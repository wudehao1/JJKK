package com.wdh.jjkk_2.dto;

import java.util.List;

/**
 * 官方基金搜索导入接口的响应对象。
 *
 * 后端会把公开源搜索到的基金基础信息先导入本地，再返回本地标准化后的基金摘要列表。
 * sourceName/sourcePageUrl/sourceFileUrl 用来标明数据来源，便于前端或测试人员确认数据出处。
 */
public final class OfficialFundDtos {
    private OfficialFundDtos() {
    }

    public record SearchResponse(
            String sourceName,
            String sourcePageUrl,
            String sourceFileUrl,
            String keyword,
            int importedCount,
            List<FundDtos.SummaryResponse> items
    ) {
    }
}

