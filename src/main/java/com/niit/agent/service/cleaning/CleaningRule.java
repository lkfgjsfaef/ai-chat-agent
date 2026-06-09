package com.niit.agent.service.cleaning;

public interface CleaningRule {
    String getId();
    String apply(String text);
}
