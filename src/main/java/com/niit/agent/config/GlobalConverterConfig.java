package com.niit.agent.config;

import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.InitBinder;

import java.beans.PropertyEditorSupport;

@ControllerAdvice
public class GlobalConverterConfig {

    @InitBinder
    public void initBinder(WebDataBinder binder) {
        binder.registerCustomEditor(Long.class, new PropertyEditorSupport() {
            @Override
            public void setAsText(String text) throws IllegalArgumentException {
                if (text == null || "null".equals(text) || text.isEmpty()) {
                    setValue(null);
                } else {
                    setValue(Long.parseLong(text));
                }
            }
        });
        binder.registerCustomEditor(Integer.class, new PropertyEditorSupport() {
            @Override
            public void setAsText(String text) throws IllegalArgumentException {
                if (text == null || "null".equals(text) || text.isEmpty()) {
                    setValue(null);
                } else {
                    setValue(Integer.parseInt(text));
                }
            }
        });
    }
}
