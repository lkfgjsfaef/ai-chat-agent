package com.niit.agent.service;

import com.niit.agent.vo.AppSkillVO;

import java.util.List;
import java.util.Optional;

public interface AppSkillService {

    List<AppSkillVO> listSkills();

    boolean isValidSkill(String skillId);

    Optional<AppSkillVO> findSkill(String skillId);

    String resolveSkillId(String overrideSkillId, String sessionSkillId, String latestUserQuestion);

    String buildSkillInstruction(String skillId, String latestUserQuestion);
}
