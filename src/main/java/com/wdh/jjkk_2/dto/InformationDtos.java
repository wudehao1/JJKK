package com.wdh.jjkk_2.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 信息板块的数据传输对象。
 *
 * 列表对象只携带卡片展示需要的摘要、时间、重要标记和媒体概览；详情对象再返回完整正文。
 * ExternalNewsItem 是 service 抓取上游快讯后的内部清洗结果，不直接作为公开接口返回。
 */
public final class InformationDtos {
    private InformationDtos() {
    }

    public record ListResponse(
            List<ItemResponse> items,
            int page,
            int size,
            long total,
            boolean hasMore
    ) {
    }

    public record ItemResponse(
            Long id,
            String title,
            String summary,
            String sourceName,
            String sourceUrl,
            String importance,
            LocalDateTime publishTime,
            List<String> imageUrls,
            List<String> chartUrls,
            List<String> symbols
    ) {
    }

    public record DetailResponse(
            Long id,
            String title,
            String summary,
            String content,
            String sourceName,
            String sourceUrl,
            String importance,
            LocalDateTime publishTime,
            List<String> imageUrls,
            List<String> chartUrls,
            List<String> symbols
    ) {
    }

    public record ExternalNewsItem(
            String sourceItemId,
            String title,
            String summary,
            String content,
            String sourceUrl,
            LocalDateTime publishTime,
            List<String> imageUrls,
            List<String> chartUrls,
            List<String> symbols,
            boolean important
    ) {
    }
}

