package com.nextstep.api.dashboard;

import java.util.List;
import java.util.Map;

import com.nextstep.api.interviews.InterviewResponse;
import com.nextstep.api.tasks.TaskResponse;

public record DashboardResponse(
        Map<String, Long> statusCounts,
        Section<TaskResponse> overdueTasks,
        Section<TaskResponse> upcomingTasks,
        Section<InterviewResponse> upcomingInterviews) {

    public record Section<T>(List<T> items, long total) {
    }
}

