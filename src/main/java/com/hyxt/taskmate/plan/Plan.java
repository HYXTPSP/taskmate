package com.hyxt.taskmate.plan;

import java.util.List;

/** 一次任务的完整计划。 */
public class Plan {
    /** AI 对计划的一句话说明 */
    public final String summary;
    public final List<TaskStep> steps;

    public Plan(String summary, List<TaskStep> steps) {
        this.summary = summary == null ? "" : summary;
        this.steps = steps;
    }
}
