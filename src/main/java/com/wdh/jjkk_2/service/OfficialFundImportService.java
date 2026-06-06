package com.wdh.jjkk_2.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wdh.jjkk_2.dto.FundDtos;
import com.wdh.jjkk_2.dto.OfficialFundDtos;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 官方基金搜索和本地基础信息导入服务。
 *
 * 这个服务替代早期“手动录入基金”的做法：用户在自选页搜索基金时，后端从公开基金
 * 搜索接口拿到真实代码和名称，再把 fund_product、fund_share_class 两层基础数据
 * 写入本地。之后自选、详情、估算、历史净值都基于这些公开数据工作，降低录入错误。
 */
@Service
public class OfficialFundImportService {
    private static final String EASTMONEY_FUND_SEARCH_URL =
            "https://fundsuggest.eastmoney.com/FundSearch/api/FundSearchAPI.ashx?m=9&key=";

    private final FundService fundService;
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public OfficialFundImportService(
            FundService fundService,
            NamedParameterJdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            RestClient.Builder restClientBuilder
    ) {
        this.fundService = fundService;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.restClient = restClientBuilder
                .defaultHeader("User-Agent", "Mozilla/5.0")
                .build();
    }

    /**
     * 查询东方财富基金联想接口，并导入返回的合法 6 位基金代码。
     *
     * 导入过程是幂等的：本地没有就新增，本地已有就更新名称、状态和来源时间。
     * 最后再查询本地标准化索引返回给前端，保证搜索结果和后续加入自选使用同一套数据。
     */
    public OfficialFundDtos.SearchResponse searchAndImport(String keyword, int limit) {
        String normalizedKeyword = normalizeKeyword(keyword);
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        int importedCount = importFromEastmoney(normalizedKeyword, safeLimit);
        List<FundDtos.SummaryResponse> items = fundService.search(normalizedKeyword, 1, safeLimit).items();
        return new OfficialFundDtos.SearchResponse(
                importedCount > 0 ? "东方财富基金搜索" : "本地基金库",
                "https://fund.eastmoney.com/",
                null,
                normalizedKeyword,
                importedCount,
                items
        );
    }

    private int importFromEastmoney(String keyword, int limit) {
        try {
            String body = restClient.get()
                    .uri(EASTMONEY_FUND_SEARCH_URL + urlEncode(keyword))
                    .retrieve()
                    .body(String.class);
            JsonNode datas = objectMapper.readTree(body == null ? "{}" : body).path("Datas");
            if (!datas.isArray()) {
                return 0;
            }
            int imported = 0;
            Set<String> importedCodes = new HashSet<>();
            for (JsonNode item : datas) {
                FundCandidate candidate = fundCandidate(item, EASTMONEY_FUND_SEARCH_URL + urlEncode(keyword));
                if (candidate == null || !importedCodes.add(candidate.fundCode())) {
                    continue;
                }
                upsertFund(candidate);
                imported += 1;
                if (imported >= limit) {
                    break;
                }
            }
            return imported;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private FundCandidate fundCandidate(JsonNode item, String sourceUrl) {
        String fundCode = firstText(item, "CODE", "FundCode", "fundCode");
        JsonNode baseInfo = item.path("FundBaseInfo");
        String officialCode = firstText(baseInfo, "FCODE", "_id");
        if (!isFundCode(fundCode)
                || !fundCode.equals(officialCode)
                || !isPublicFundItem(item, baseInfo)) {
            return null;
        }
        String fundName = firstText(baseInfo, "SHORTNAME", "FundName", "fundName", "NAME");
        if (!StringUtils.hasText(fundName)) {
            fundName = firstText(item, "NAME", "FundName", "fundName");
        }
        if (!StringUtils.hasText(fundName)) {
            return null;
        }
        String typeText = firstText(baseInfo, "FTYPE", "FUNDTYPE", "RSFUNDTYPE");
        String pinyinAbbr = firstText(item, "JP", "PY", "pinyin");
        return new FundCandidate(
                fundCode,
                fundName,
                pinyinAbbr,
                inferFundType(typeText, fundName),
                sourceUrl
        );
    }

    private boolean isPublicFundItem(JsonNode item, JsonNode baseInfo) {
        String categoryDesc = firstText(item, "CATEGORYDESC", "CategoryDesc", "categoryDesc");
        int category = item.path("CATEGORY").asInt(-1);
        if (StringUtils.hasText(categoryDesc) && !"基金".equals(categoryDesc.trim())) {
            return false;
        }
        if (category >= 0 && category != 700) {
            return false;
        }
        return baseInfo != null && !baseInfo.isMissingNode() && !baseInfo.isNull();
    }

    private void upsertFund(FundCandidate candidate) {
        jdbcTemplate.update("""
                INSERT INTO fund_product (
                  main_fund_code, fund_name, fund_short_name, pinyin_abbr, fund_type, operation_mode,
                  currency, status, source_url, source_updated_at
                ) VALUES (
                  :fundCode, :fundName, :fundName, :pinyinAbbr, :fundType, 'OPEN',
                  'CNY', 'ACTIVE', :sourceUrl, :sourceUpdatedAt
                )
                ON DUPLICATE KEY UPDATE
                  fund_name = VALUES(fund_name),
                  fund_short_name = VALUES(fund_short_name),
                  pinyin_abbr = COALESCE(VALUES(pinyin_abbr), pinyin_abbr),
                  fund_type = VALUES(fund_type),
                  status = 'ACTIVE',
                  source_url = VALUES(source_url),
                  source_updated_at = VALUES(source_updated_at),
                  updated_at = CURRENT_TIMESTAMP(3)
                """, params(candidate));

        Long productId = jdbcTemplate.queryForObject("""
                SELECT id FROM fund_product WHERE main_fund_code = :fundCode LIMIT 1
                """, new MapSqlParameterSource("fundCode", candidate.fundCode()), Long.class);
        if (productId == null) {
            return;
        }
        jdbcTemplate.update("""
                INSERT INTO fund_share_class (
                  product_id, fund_code, fund_name, fund_abbr, pinyin_abbr, status, source_url, source_updated_at
                ) VALUES (
                  :productId, :fundCode, :fundName, :fundName, :pinyinAbbr, 'ACTIVE', :sourceUrl, :sourceUpdatedAt
                )
                ON DUPLICATE KEY UPDATE
                  product_id = VALUES(product_id),
                  fund_name = VALUES(fund_name),
                  fund_abbr = VALUES(fund_abbr),
                  pinyin_abbr = COALESCE(VALUES(pinyin_abbr), pinyin_abbr),
                  status = 'ACTIVE',
                  source_url = VALUES(source_url),
                  source_updated_at = VALUES(source_updated_at),
                  updated_at = CURRENT_TIMESTAMP(3)
                """, params(candidate).addValue("productId", productId));
    }

    private MapSqlParameterSource params(FundCandidate candidate) {
        return new MapSqlParameterSource()
                .addValue("fundCode", candidate.fundCode())
                .addValue("fundName", candidate.fundName())
                .addValue("pinyinAbbr", trimToNull(candidate.pinyinAbbr()))
                .addValue("fundType", candidate.fundType())
                .addValue("sourceUrl", candidate.sourceUrl())
                .addValue("sourceUpdatedAt", LocalDateTime.now());
    }

    private String inferFundType(String typeText, String fundName) {
        String type = typeText == null ? "" : typeText.toUpperCase();
        String name = fundName == null ? "" : fundName.toUpperCase();
        String combined = type + " " + name;
        if (combined.contains("QDII")) {
            return "QDII";
        }
        if (combined.contains("FOF")) {
            return "FOF";
        }
        if (combined.contains("REIT")) {
            return "REIT";
        }
        if (combined.contains("货币")) {
            return "MONEY_MARKET";
        }
        if (type.contains("指数") || name.contains("ETF") || name.contains("指数")) {
            return "INDEX";
        }
        if (type.contains("债券") || type.contains("短债") || type.contains("纯债")) {
            return "BOND";
        }
        if (type.contains("混合") || name.contains("混合")) {
            return "MIXED";
        }
        if (type.contains("股票") || name.contains("股票")) {
            return "STOCK";
        }
        return "OTHER";
    }

    private String firstText(JsonNode item, String... fields) {
        for (String field : fields) {
            String value = item.path(field).asText(null);
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private boolean isFundCode(String value) {
        return value != null && value.matches("\\d{6}");
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String normalizeKeyword(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return "";
        }
        return keyword.trim();
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private record FundCandidate(
            String fundCode,
            String fundName,
            String pinyinAbbr,
            String fundType,
            String sourceUrl
    ) {
    }
}
