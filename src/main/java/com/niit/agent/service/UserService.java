package com.niit.agent.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.niit.agent.entity.User;
import com.niit.agent.vo.LoginVO;
import com.niit.agent.vo.UserProfileVO;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

public interface UserService extends IService<User> {

    Map<String, Object> login(LoginVO loginVO);

    void register(LoginVO loginVO);

    User getByUsername(String username);

    UserProfileVO getProfile(Long userId);

    void updateProfile(Long userId, UserProfileVO profileVO);

    String updateAvatar(Long userId, org.springframework.web.multipart.MultipartFile file);

    void incrementTokenUsage(Long userId, int tokens);
}
