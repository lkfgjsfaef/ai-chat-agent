package com.niit.agent.controller;

import com.niit.agent.common.result.Result;
import com.niit.agent.common.result.Result;
import com.niit.agent.service.UserService;
import com.niit.agent.vo.LoginVO;
import com.niit.agent.vo.UserProfileVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@Tag(name = "用户管理", description = "用户注册、登录、个人信息管理")
@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/profile")
    public Result<UserProfileVO> getProfile(HttpServletRequest request) {
        Long userId = Long.parseLong(request.getAttribute("userId").toString());
        return Result.ok(userService.getProfile(userId));
    }

    @PutMapping("/profile")
    public Result<Void> updateProfile(@RequestBody UserProfileVO profileVO, HttpServletRequest request) {
        Long userId = Long.parseLong(request.getAttribute("userId").toString());
        userService.updateProfile(userId, profileVO);
        return Result.ok();
    }

    @PostMapping("/avatar")
    public Result<String> uploadAvatar(@RequestParam("file") MultipartFile file, HttpServletRequest request) {
        Long userId = Long.parseLong(request.getAttribute("userId").toString());
        String avatarUrl = userService.updateAvatar(userId, file);
        return Result.ok(avatarUrl);
    }

    @PostMapping("/login")
    public Result<Map<String, Object>> login(@Valid @RequestBody LoginVO loginVO) {
        try {
            Map<String, Object> data = userService.login(loginVO);
            return Result.ok(data);
        } catch (RuntimeException e) {
            return Result.fail(e.getMessage());
        }
    }

    @PostMapping("/register")
    public Result<Void> register(@Valid @RequestBody LoginVO loginVO) {
        try {
            userService.register(loginVO);
            return Result.ok();
        } catch (RuntimeException e) {
            return Result.fail(e.getMessage());
        }
    }
}
