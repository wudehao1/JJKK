package com.wdh.jjkk_2.mapper;

import com.wdh.jjkk_2.dto.FundDtos;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 基金查询结果到 DTO 的映射工具。
 *
 * Service 负责业务编排和 SQL 选择，Mapper 只负责把 ResultSet 中的列转换成前端
 * 需要的响应对象。把映射逻辑集中放在这里，后续调整 SQL 别名、增加字段或修正
 * 日期类型时，不需要在 FundService 里到处寻找 RowMapper。
 */
public final class FundRowMapper {
    private FundRowMapper() {
    }

    /**
     * 映射基金搜索列表行。
     *
     * 列表页只需要摘要信息、最新净值和估算快照，因此这里返回 SummaryResponse，
     * 避免把详情页才需要的基金成立日、托管人等字段传给前端。
     */
    public static RowMapper<FundDtos.SummaryResponse> summary() {
        return (rs, rowNum) -> new FundDtos.SummaryResponse(
                rs.getString("fund_code"),
                rs.getString("fund_name"),
                rs.getString("fund_abbr"),
                rs.getString("share_class_code"),
                rs.getString("fund_type"),
                rs.getString("operation_mode"),
                rs.getString("risk_level"),
                rs.getBoolean("is_index_fund"),
                rs.getString("tracking_index_code"),
                rs.getString("company_name"),
                localDate(rs, "nav_date"),
                rs.getBigDecimal("unit_nav"),
                rs.getBigDecimal("daily_return_pct"),
                localDateTime(rs, "quote_time"),
                rs.getBigDecimal("estimate_nav"),
                rs.getBigDecimal("estimate_return_pct"),
                rs.getString("data_status"),
                rs.getString("status")
        );
    }

    /**
     * 映射基金详情行。
     *
     * 详情页需要更完整的基金画像，包括份额类别、风险等级、跟踪指数、申赎状态、
     * 官方净值和当前估算状态，所以这里读取的字段比列表映射更多。
     */
    public static RowMapper<FundDtos.DetailResponse> detail() {
        return (rs, rowNum) -> new FundDtos.DetailResponse(
                rs.getLong("product_id"),
                rs.getLong("share_class_id"),
                rs.getString("fund_code"),
                rs.getString("fund_name"),
                rs.getString("fund_abbr"),
                rs.getString("share_class_code"),
                rs.getString("fund_type"),
                rs.getString("operation_mode"),
                rs.getString("risk_level"),
                rs.getString("currency"),
                localDate(rs, "inception_date"),
                rs.getString("benchmark"),
                rs.getString("tracking_index_code"),
                rs.getBoolean("is_index_fund"),
                rs.getBoolean("is_qdii"),
                rs.getBoolean("is_fof"),
                rs.getBoolean("is_reit"),
                rs.getString("company_name"),
                rs.getString("custodian_name"),
                rs.getString("purchase_status"),
                rs.getString("redeem_status"),
                localDate(rs, "nav_date"),
                rs.getBigDecimal("unit_nav"),
                rs.getBigDecimal("daily_return_pct"),
                localDateTime(rs, "quote_time"),
                rs.getBigDecimal("estimate_nav"),
                rs.getBigDecimal("estimate_return_pct"),
                rs.getBigDecimal("confidence_score"),
                rs.getString("data_status"),
                rs.getString("status")
        );
    }

    private static LocalDate localDate(ResultSet rs, String column) throws SQLException {
        Date date = rs.getDate(column);
        return date == null ? null : date.toLocalDate();
    }

    private static LocalDateTime localDateTime(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
