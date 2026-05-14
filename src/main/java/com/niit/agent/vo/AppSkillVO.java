package com.niit.agent.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AppSkillVO {

    private String id;
    private String name;
    private String description;
    private String scope;
    private String placeholder;
}
