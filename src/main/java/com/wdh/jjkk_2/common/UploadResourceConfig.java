package com.wdh.jjkk_2.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 静态上传资源映射配置。
 *
 * 用户头像等上传文件会保存到本机目录，Spring Boot 默认不会自动把这个目录
 * 暴露为可访问 URL。这里把 /uploads/avatars/** 映射到配置的头像目录，
 * 让小程序保存的 avatar_url 可以直接被前端展示。
 */
@Configuration
public class UploadResourceConfig implements WebMvcConfigurer {
    private final Path avatarDirectory;

    public UploadResourceConfig(@Value("${jjkk.upload.avatar-dir:uploads/avatars}") String avatarDir) {
        this.avatarDirectory = Paths.get(avatarDir).toAbsolutePath().normalize();
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        try {
            Files.createDirectories(avatarDirectory);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot create avatar upload directory", exception);
        }
        registry.addResourceHandler("/uploads/avatars/**")
                .addResourceLocations(avatarDirectory.toUri().toString());
    }
}

