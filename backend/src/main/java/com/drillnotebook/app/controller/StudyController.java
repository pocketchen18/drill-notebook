package com.drillnotebook.app.controller;

import com.drillnotebook.app.service.CompletionSyncService;
import com.drillnotebook.app.service.TodayQueueService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/study")
public class StudyController {

    private final TodayQueueService todayQueue;
    private final CompletionSyncService completionSync;

    public StudyController(TodayQueueService todayQueue, CompletionSyncService completionSync) {
        this.todayQueue = todayQueue;
        this.completionSync = completionSync;
    }

    @GetMapping("/today")
    public Map<String, Object> today(
            @RequestParam(required = false) String date,
            @RequestParam(required = false) Long configId) {
        return todayQueue.getToday(date, configId);
    }

    @PostMapping("/complete")
    public Map<String, Object> complete(@RequestBody Map<String, Object> body) {
        return completionSync.onItemCompleted(body);
    }
}
