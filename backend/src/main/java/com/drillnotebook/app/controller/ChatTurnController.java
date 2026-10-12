package com.drillnotebook.app.controller;

import com.drillnotebook.app.service.ChatTurnService;
import com.drillnotebook.app.service.ContextInspector;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 轮次状态、停止与上下文检查（执行方案 §7.3、contracts §3/§4.5）。
 *
 * <p>三个入口都是纯读或纯取消：状态查询与检查器都不重新生成、不重新提取、不重新计费，
 * 也不因为"想看一眼"就重建计划；停止走服务端句柄，前端 abort 只断连接，不能代替服务端取消上游。</p>
 */
@RestController
@RequestMapping("/api/ai/turns")
public class ChatTurnController {
    private final ChatTurnService turns;
    private final ContextInspector inspector;

    public ChatTurnController(ChatTurnService turns, ContextInspector inspector) {
        this.turns = turns;
        this.inspector = inspector;
    }

    @GetMapping("/{requestId}")
    public Map<String, Object> status(@PathVariable String requestId) {
        return turns.status(requestId);
    }

    @PostMapping("/{requestId}/cancel")
    public Map<String, Object> cancel(@PathVariable String requestId) {
        return turns.cancel(requestId);
    }

    /** 本轮实际发给模型的有界审计；没有快照时明确 unavailable。 */
    @GetMapping("/{requestId}/context")
    public Map<String, Object> context(@PathVariable String requestId) throws SQLException {
        return inspector.audit(requestId);
    }

    /** 最多 5 个"本轮真发过且现在仍合法"的来源片段，各 ≤160 码点；不触发任何模型调用。 */
    @PostMapping("/{requestId}/context/preview")
    public List<Map<String, Object>> preview(@PathVariable String requestId,
                                             @RequestBody(required = false) Map<String, Object> body)
            throws SQLException {
        Object raw = body == null ? null : body.get("sourceKeys");
        List<String> keys = raw instanceof List<?> list
                ? list.stream().map(String::valueOf).toList() : List.of();
        return inspector.preview(requestId, keys);
    }
}
