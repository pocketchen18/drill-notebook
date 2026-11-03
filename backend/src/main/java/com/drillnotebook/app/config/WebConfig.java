package com.drillnotebook.app.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("http://127.0.0.1:[*]", "http://localhost:[*]", "file://")
                // PATCH 是 contracts §3 里记忆条目编辑的既定动词，渲染进程带 JSON 头必然预检，
                // 缺这个动词就等于前端调不到契约自己定的端点；来源名单不因写接口放宽（见 WebConfigCorsTest）
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false)
                .maxAge(3600);
    }
}
