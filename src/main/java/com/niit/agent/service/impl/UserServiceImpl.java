package com.niit.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.niit.agent.common.util.JwtUtil;
import com.niit.agent.entity.User;
import com.niit.agent.mapper.UserMapper;
import com.niit.agent.service.UserService;
import com.niit.agent.vo.LoginVO;
import com.niit.agent.vo.UserProfileVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    private final UserMapper userMapper;
    private final JwtUtil jwtUtil;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private static final java.util.regex.Pattern MD5_PATTERN =
            java.util.regex.Pattern.compile("^[a-fA-F0-9]{32}$");

    @Value("${app.upload.avatar-dir:${user.dir}/uploads/avatars/}")
    private String uploadDir;

    @Override
    public Map<String, Object> login(LoginVO loginVO) {
        User user = getByUsername(loginVO.getUsername());
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }
        if (!verifyPassword(loginVO.getPassword(), user)) {
            throw new RuntimeException("密码错误");
        }
        String role = user.getRole() != null ? user.getRole() : "user";
        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), role);
        Map<String, Object> result = new HashMap<>();
        result.put("token", token);
        result.put("userId", user.getId());
        result.put("username", user.getUsername());
        return result;
    }

    @Override
    public void register(LoginVO loginVO) {
        User exist = getByUsername(loginVO.getUsername());
        if (exist != null) {
            throw new RuntimeException("用户名已存在");
        }
        User user = new User();
        user.setUsername(loginVO.getUsername());
        user.setPassword(passwordEncoder.encode(loginVO.getPassword()));
        userMapper.insert(user);
    }

    /**
     * 验证密码：先尝试 BCrypt 匹配，失败则回退到 MD5 比较（兼容旧用户），
     * MD5 匹配成功后自动将密码升级为 BCrypt。
     */
    private boolean verifyPassword(String rawPassword, User user) {
        String stored = user.getPassword();
        if (stored == null) return false;

        // 1. BCrypt 匹配（新用户或已升级用户）
        if (stored.startsWith("$2")) {
            return passwordEncoder.matches(rawPassword, stored);
        }

        // 2. MD5 回退（旧用户）
        if (MD5_PATTERN.matcher(stored).matches()) {
            String md5 = md5Hex(rawPassword);
            if (stored.equalsIgnoreCase(md5)) {
                // 自动升级为 BCrypt
                String encoded = passwordEncoder.encode(rawPassword);
                user.setPassword(encoded);
                userMapper.updateById(user);
                log.info("用户 {} 的密码已从 MD5 自动升级为 BCrypt", user.getUsername());
                return true;
            }
            return false;
        }

        // 未知格式，尝试 BCrypt
        return passwordEncoder.matches(rawPassword, stored);
    }

    private String md5Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 算法不可用", e);
        }
    }

    @Override
    public User getByUsername(String username) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername, username);
        return userMapper.selectOne(wrapper);
    }

    @Override
    public UserProfileVO getProfile(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }
        UserProfileVO vo = new UserProfileVO();
        BeanUtils.copyProperties(user, vo);
        return vo;
    }

    @Override
    public void updateProfile(Long userId, UserProfileVO profileVO) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }
        if (StringUtils.hasText(profileVO.getNickname())) user.setNickname(profileVO.getNickname());
        if (StringUtils.hasText(profileVO.getBio())) user.setBio(profileVO.getBio());
        if (StringUtils.hasText(profileVO.getTheme())) user.setTheme(profileVO.getTheme());
        userMapper.updateById(user);
    }

    @Override
    public String updateAvatar(Long userId, MultipartFile file) {
        if (file.isEmpty()) {
            throw new RuntimeException("文件为空");
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }
        try {
            File dir = new File(uploadDir);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            String originalFilename = file.getOriginalFilename();
            String ext = originalFilename != null ? originalFilename.substring(originalFilename.lastIndexOf(".")) : ".png";
            String newFilename = UUID.randomUUID().toString().replace("-", "") + ext;
            File dest = new File(uploadDir + newFilename);
            file.transferTo(dest);

            String avatarUrl = "/api/avatars/" + newFilename;
            user.setAvatarUrl(avatarUrl);
            userMapper.updateById(user);
            return avatarUrl;
        } catch (IOException e) {
            throw new RuntimeException("上传头像失败", e);
        }
    }

    @Override
    public void incrementTokenUsage(Long userId, int tokens) {
        User user = userMapper.selectById(userId);
        if (user != null) {
            user.setTotalTokens((user.getTotalTokens() != null ? user.getTotalTokens() : 0L) + tokens);
            userMapper.updateById(user);
        }
    }
}
