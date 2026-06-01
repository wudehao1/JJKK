package com.wdh.jjkk_2.service;

import com.wdh.jjkk_2.common.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;

/**
 * 用户头像上传保存服务。
 *
 * 小程序端第一次授权后可以拿到微信头像，用户后续也可以在“我的”里修改头像。
 * 文件保存到配置的本地上传目录，并返回由
 * {@link com.wdh.jjkk_2.common.UploadResourceConfig} 暴露出去的访问 URL。
 */
@Service
public class UserAvatarStorageService {
    private static final long MAX_AVATAR_BYTES = 2 * 1024 * 1024;
    private static final Map<String, String> CONTENT_TYPE_EXTENSIONS = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp",
            "image/gif", "gif"
    );

    private final Path avatarDirectory;

    public UserAvatarStorageService(@Value("${jjkk.upload.avatar-dir:uploads/avatars}") String avatarDir) {
        this.avatarDirectory = Paths.get(avatarDir).toAbsolutePath().normalize();
    }

    /**
     * 校验头像文件并保存到本地目录。
     *
     * 只允许常见图片格式，大小限制为 2MB；保存文件名包含 9 位用户编号和时间戳，
     * 可以减少重名覆盖。写入前会检查目标路径仍在头像目录下，防止非法文件名逃逸到
     * 其他目录。返回值会写入用户资料表，前端可直接用它展示头像。
     */
    public String store(Long userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("澶村儚鏂囦欢涓嶈兘涓虹┖");
        }
        if (file.getSize() > MAX_AVATAR_BYTES) {
            throw new BusinessException(HttpStatus.PAYLOAD_TOO_LARGE, "澶村儚涓嶈兘瓒呰繃2MB");
        }
        String extension = extension(file);
        try {
            Files.createDirectories(avatarDirectory);
            String filename = String.format("%09d-%d.%s", userId == null ? 0 : userId, System.currentTimeMillis(), extension);
            Path target = avatarDirectory.resolve(filename).normalize();
            if (!target.startsWith(avatarDirectory)) {
                throw new BusinessException("澶村儚鏂囦欢鍚嶄笉鍚堟硶");
            }
            Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
            return "/uploads/avatars/" + filename;
        } catch (IOException exception) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "澶村儚淇濆瓨澶辫触");
        }
    }

    private String extension(MultipartFile file) {
        String contentType = file.getContentType();
        if (StringUtils.hasText(contentType)) {
            String extension = CONTENT_TYPE_EXTENSIONS.get(contentType.toLowerCase(Locale.ROOT));
            if (extension != null) {
                return extension;
            }
        }
        String filename = file.getOriginalFilename();
        if (StringUtils.hasText(filename) && filename.contains(".")) {
            String raw = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
            if (raw.equals("jpg") || raw.equals("jpeg") || raw.equals("png") || raw.equals("webp") || raw.equals("gif")) {
                return raw.equals("jpeg") ? "jpg" : raw;
            }
        }
        throw new BusinessException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "澶村儚浠呮敮鎸?jpg銆乸ng銆亀ebp銆乬if");
    }
}

