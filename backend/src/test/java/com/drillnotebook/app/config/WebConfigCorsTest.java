package com.drillnotebook.app.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

/**
 * 本地 API 的跨源边界（contracts §3「新增写接口不得放宽 CORS；拒绝未允许 Origin 的网页调用」）。
 *
 * <p>这一条把"方法名单要为契约补齐"与"来源名单一个都不许加宽"分开钉住：记忆编辑按契约就是 PATCH，
 * 渲染进程带 JSON 头必然触发预检，名单缺 PATCH 等于前端调不到自己定的端点；但补动词不能顺手把
 * 本机的 127.0.0.1 边界扩成任意网站可写。</p>
 */
class WebConfigCorsTest {
    /** CorsRegistry 的读取口是 protected，测试里用子类把它露出来，不改生产可见性。 */
    private static final class Probe extends CorsRegistry {
        Map<String, CorsConfiguration> configs() {
            return getCorsConfigurations();
        }
    }

    private static CorsConfiguration apiConfig() {
        Probe registry = new Probe();
        new WebConfig().addCorsMappings(registry);
        CorsConfiguration config = registry.configs().get("/api/**");
        assertNotNull(config, "本地 API 的 CORS 规则要挂在 /api/** 上");
        return config;
    }

    @Test
    void patchIsAllowedSoTheDocumentedEditRouteIsReachable() {
        List<String> methods = apiConfig().getAllowedMethods();
        assertTrue(methods.contains("PATCH"), "记忆条目编辑按契约走 PATCH，缺这个动词前端就调不到（P9a）");
        assertTrue(methods.containsAll(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS")),
                "原有方法一个都不能因为补 PATCH 而被挤掉：" + methods);
    }

    @Test
    void foreignOriginsStayRejectedEvenAfterTheMethodListGrew() {
        CorsConfiguration config = apiConfig();
        for (String origin : List.of("https://evil.example.com", "http://127.0.0.1.evil.example.com",
                "https://localhost", "null")) {
            assertNull(config.checkOrigin(origin), "补 PATCH 不许顺带放开来源：" + origin);
        }
        assertNotNull(config.checkOrigin("http://127.0.0.1:5173"), "开发期渲染进程仍要可达");
        assertNotNull(config.checkOrigin("http://localhost:18081"));
        assertFalse(config.getAllowCredentials(), "本地端口不带凭据，否则任意站点可借浏览器发起写操作");
    }
}
