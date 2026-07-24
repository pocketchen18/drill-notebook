package com.drillnotebook.app.controller;

import com.drillnotebook.app.service.AiService;
import com.drillnotebook.app.service.StudyPlanService;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/study-plans")
public class StudyPlanController {
    private final StudyPlanService plans;
    private final AiService ai;

    public StudyPlanController(StudyPlanService plans, AiService ai) {
        this.plans = plans;
        this.ai = ai;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam String from, @RequestParam String to) {
        return plans.listRange(from, to);
    }

    @GetMapping("/day")
    public Map<String, Object> day(@RequestParam String date) {
        return plans.listDay(date);
    }

    @PostMapping("/groups")
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        return plans.createGroup(body);
    }

    @PutMapping("/groups/{id}")
    public Map<String, Object> updateGroup(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return plans.updateGroup(id, body);
    }

    @DeleteMapping("/groups/{id}")
    public void deleteGroup(@PathVariable long id) {
        plans.deleteGroup(id);
    }

    @PutMapping("/items/{id}")
    public Map<String, Object> updateItem(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return plans.updateItem(id, body);
    }

    @DeleteMapping("/items/{id}")
    public void deleteItem(@PathVariable long id) {
        plans.deleteItem(id);
    }

    @PostMapping("/items/complete-by-resources")
    public Map<String, Object> completeByResources(@RequestBody Map<String, Object> body) {
        return plans.completeByResources(body);
    }

    @PostMapping("/recommend")
    public Map<String, Object> recommend(@RequestBody(required = false) Map<String, Object> body) {
        return plans.recommend(body == null ? Map.of() : body);
    }

    /** AI 安排背诵/复习计划（预览，不写库）；失败时返回规则降级方案。 */
    @PostMapping("/recommend/ai-schedule")
    public Map<String, Object> aiSchedule(@RequestBody(required = false) Map<String, Object> body) {
        return plans.aiSchedule(body == null ? Map.of() : body);
    }

    /** 一键写入多日计划方案。 */
    @PostMapping("/recommend/apply-schedule")
    public Map<String, Object> applySchedule(@RequestBody Map<String, Object> body) {
        return plans.applySchedule(body);
    }

    /** 会话结束双写：可选加入记忆曲线 + 可选写入学习日历。 */
    @PostMapping("/recommend/session-apply")
    public Map<String, Object> sessionApply(@RequestBody Map<String, Object> body) {
        return plans.sessionApply(body);
    }

    @PostMapping("/recommend/ai-note")
    public Map<String, Object> aiNote(@RequestBody Map<String, Object> body) {
        return ai.writePlanNote(body);
    }
}
