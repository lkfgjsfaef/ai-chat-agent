package com.niit.agent.vo;

import lombok.Data;

@Data
public class UserProfileVO {
    private Long id;
    private String username;
    private String nickname;
    private String avatarUrl;
    private String bio;
    private String theme;
    private Long totalTokens;
    private Boolean hasApiKey;
}