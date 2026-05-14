package com.niit.agent.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.niit.agent.common.result.Result;
import com.niit.agent.entity.ModelConfig;
import com.niit.agent.entity.User;
import com.niit.agent.mapper.ModelConfigMapper;
import com.niit.agent.service.AiModelRouterService;
import com.niit.agent.service.ChatMessageService;
import com.niit.agent.service.MemoryService;
import com.niit.agent.service.RagQualityService;
import com.niit.agent.service.RuntimeHealthService;
import com.niit.agent.service.RuntimeMetricsHistoryService;
import com.niit.agent.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final UserService userService;
    private final ModelConfigMapper modelConfigMapper;
    private final AiModelRouterService aiModelRouterService;
    private final ChatMessageService chatMessageService;
    private final MemoryService memoryService;
    private final RagQualityService ragQualityService;
    private final RuntimeHealthService runtimeHealthService;
    private final RuntimeMetricsHistoryService runtimeMetricsHistoryService;

    /**
     * 校验管理员权限
     */
    private void checkAdminRole(HttpServletRequest request) {
        Object roleObj = request.getAttribute("role");
        if (roleObj == null || !"admin".equals(roleObj.toString())) {
            throw new RuntimeException("无权限访问，仅限管理员");
        }
    }

    /**
     * 分页获取用户列表及 Token 消耗情况
     */
    @GetMapping("/users")
    public Result<IPage<User>> listUsers(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size,
            HttpServletRequest request) {
        checkAdminRole(request);
        
        Page<User> page = new Page<>(current, size);
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(User::getTotalTokens); // 默认按 Token 消耗降序
        wrapper.select(User.class, info -> !info.getColumn().equals("password")); // 排除密码字段
        
        IPage<User> userPage = userService.page(page, wrapper);
        return Result.ok(userPage);
    }

    /**
     * 获取全局 Token 和用户统计大盘数据
     */
    @GetMapping("/stats/dashboard")
    public Result<Map<String, Object>> getDashboardStats(HttpServletRequest request) {
        checkAdminRole(request);

        Map<String, Object> stats = new HashMap<>();
        
        // 1. 总用户数
        long totalUsers = userService.count();
        stats.put("totalUsers", totalUsers);
        
        // 2. 总 Token 消耗 (这里简单通过全表求和，实际生产中应使用 SQL SUM 或单独的统计表)
        List<User> allUsers = userService.list(new LambdaQueryWrapper<User>().select(User::getTotalTokens));
        long totalTokens = allUsers.stream()
                .mapToLong(u -> u.getTotalTokens() != null ? u.getTotalTokens() : 0L)
                .sum();
        stats.put("totalTokens", totalTokens);
        
        // 3. 模型可用状态
        List<ModelConfig> models = modelConfigMapper.selectList(null);
        long activeModels = models.stream().filter(m -> m.getStatus() == 1).count();
        stats.put("activeModels", activeModels);
        stats.put("totalModels", models.size());
        
        return Result.ok(stats);
    }

    @GetMapping("/stats/runtime")
    public Result<Map<String, Object>> getRuntimeStats(HttpServletRequest request) {
        checkAdminRole(request);

        Map<String, Object> stats = new HashMap<>();
        stats.put("modelRuntime", aiModelRouterService.getRuntimeMetrics());
        stats.put("ragRuntime", memoryService.getRagRuntimeMetrics());
        stats.put("health", runtimeHealthService.getHealthSummary());
        return Result.ok(stats);
    }

    @GetMapping("/stats/history")
    public Result<List<Map<String, Object>>> getRuntimeHistory(
            @RequestParam(defaultValue = "60") Integer limit,
            HttpServletRequest request) {
        checkAdminRole(request);
        return Result.ok(runtimeMetricsHistoryService.getRuntimeHistory(limit));
    }

    @GetMapping("/stats/health")
    public Result<Map<String, Object>> getRuntimeHealth(HttpServletRequest request) {
        checkAdminRole(request);
        return Result.ok(runtimeHealthService.getHealthSummary());
    }

    @GetMapping("/stats/feedback")
    public Result<Map<String, Object>> getFeedbackStats(
            @RequestParam(defaultValue = "10") Integer recentLimit,
            HttpServletRequest request) {
        checkAdminRole(request);
        return Result.ok(chatMessageService.getFeedbackStats(recentLimit));
    }

    @GetMapping("/stats/rag-quality")
    public Result<Map<String, Object>> getRagQuality(HttpServletRequest request) {
        checkAdminRole(request);
        return Result.ok(ragQualityService.getQualityMetrics());
    }

    /**
     * 封禁/解封用户 (示例操作)
     */
    @PutMapping("/users/{userId}/status")
    public Result<Void> updateUserStatus(@PathVariable Long userId, @RequestParam Integer status, HttpServletRequest request) {
        checkAdminRole(request);
        // 这里只是示例，实际可以在 user 表加个 status 字段
        return Result.ok();
    }
}
