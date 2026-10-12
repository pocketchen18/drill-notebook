package com.drillnotebook.app.controller;

import com.drillnotebook.app.service.ChatTurnService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 轮次状态与停止（执行方案 §7.3）。
 *
 * <p>状态查询是纯读：绝不因为"查一下"而重新生成、重新提取或重新计费。
 * 停止走服务端句柄，前端 abort 只断连接，不能代替服务端取消上游。</p>
 */
@RestController
@RequestMapping("/api/ai/turns")
public class ChatTurnController {
    private final ChatTurnService turns;

    public ChatTurnController(ChatTurnService turns) {
        this.turns = turns;
    }

    @GetMapping("/{requestId}")
    public Map<String, Object> status(@PathVariable String requestId) {
        return turns.status(requestId);
    }

    @PostMapping("/{requestId}/cancel")
    public Map<String, Object> cancel(@PathVariable String requestId) {
        return turns.cancel(requestId);
    }
}
