package com.wdh.jjkk_2.service;

import com.wdh.jjkk_2.dto.FeedbackDtos;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wdh.jjkk_2.common.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 追加写入式反馈日志服务。
 *
 * 当前反馈功能只需要收集用户建议、问题描述、联系方式和设备信息，暂时不需要复杂的
 * 后台管理查询。因此这里把每条反馈序列化成一行 JSON，按日期写入配置目录下的日志
 * 文件。后续如果要做运营后台，再从日志迁移或同步到专门的数据表也比较容易。
 */
@Service
public class FeedbackService {
    private final ObjectMapper objectMapper;
    private final Path logDirectory;

    public FeedbackService(
            ObjectMapper objectMapper,
            @Value("${jjkk.feedback.log-dir:logs/feedback}") String logDir
    ) {
        this.objectMapper = objectMapper;
        this.logDirectory = Paths.get(logDir).toAbsolutePath().normalize();
    }

    /**
     * 校验并保存一条反馈。
     *
     * 保存前会补充提交时间、真实 userId、9 位展示用户编号、分类、联系方式和设备信息。
     * 目标文件名按日期生成，并校验最终路径仍在反馈目录内，避免异常文件名造成路径穿越。
     */
    public FeedbackDtos.FeedbackResponse submit(Long userId, FeedbackDtos.FeedbackRequest request) {
        if (request == null || !StringUtils.hasText(request.content())) {
            throw new BusinessException("\u53cd\u9988\u5185\u5bb9\u4e0d\u80fd\u4e3a\u7a7a");
        }
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("submittedAt", now.toString());
        payload.put("userId", userId);
        payload.put("displayUserId", displayUserId(userId));
        payload.put("category", StringUtils.hasText(request.category()) ? request.category().trim() : "\u5176\u4ed6");
        payload.put("content", request.content().trim());
        payload.put("contact", trimToNull(request.contact()));
        payload.put("deviceInfo", trimToNull(request.deviceInfo()));

        try {
            Files.createDirectories(logDirectory);
            Path target = logDirectory.resolve("feedback-" + LocalDate.now() + ".log").normalize();
            if (!target.startsWith(logDirectory)) {
            throw new BusinessException("\u53cd\u9988\u4fdd\u5b58\u8def\u5f84\u4e0d\u5408\u6cd5");
            }
            Files.writeString(
                    target,
                    objectMapper.writeValueAsString(payload) + System.lineSeparator(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
            return new FeedbackDtos.FeedbackResponse(now);
        } catch (IOException exception) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "\u53cd\u9988\u4fdd\u5b58\u5931\u8d25");
        }
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String displayUserId(Long id) {
        long safeId = id == null ? 0 : id;
        return String.valueOf(100_000_000L + safeId);
    }
}
