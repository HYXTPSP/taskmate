package com.hyxt.taskmate.api;

import com.google.gson.JsonObject;
import com.hyxt.taskmate.exec.ActionRegistry;
import com.hyxt.taskmate.exec.BaritoneCheck;
import com.hyxt.taskmate.plan.TaskStep;
import net.minecraft.client.MinecraftClient;

import java.util.Map;

/**
 * 子步骤运行时:让一个 StepHandler 在自己内部运行另一个动作(任务树的地基)。
 * 用法:child = SubStep.create("mine", args, control); 每 tick 调 child.tick(),
 * 根据返回的 Status 处理 DONE/FAILED。
 */
public final class SubStep {

    public enum Status { RUNNING, DONE, FAILED }

    private final StepHandler handler;
    private final TaskStep step;
    private Status status = Status.RUNNING;
    private String failReason;
    private int ticks;
    private int grace = 10;

    private SubStep(StepHandler handler, TaskStep step) {
        this.handler = handler;
        this.step = step;
    }

    /** 创建并启动子步骤;动作不存在或 Baritone 缺失时返回一个已失败的实例 */
    public static SubStep create(String action, JsonObject args, StepControl parentControl) {
        ActionDefinition def = ActionRegistry.get(action);
        TaskStep st = new TaskStep(action, args, null);
        if (def == null) {
            return failed(st, "未注册的动作: " + action);
        }
        if (def.needsBaritone() && !BaritoneCheck.available()) {
            return failed(st, "Baritone 不可用");
        }
        StepHandler h = def.handlerFactory().get();
        SubStep sub = new SubStep(h, st);
        h.init(sub.new Bridge(parentControl));
        try {
            h.start();
        } catch (Throwable t) {
            sub.status = Status.FAILED;
            sub.failReason = "子步骤启动出错: " + t.getMessage();
        }
        return sub;
    }

    private static SubStep failed(TaskStep st, String reason) {
        SubStep s = new SubStep(null, st);
        s.status = Status.FAILED;
        s.failReason = reason;
        return s;
    }

    public Status tick(MinecraftClient client) {
        if (status != Status.RUNNING) return status;
        ticks++;
        if (grace > 0) {
            grace--;
            return status;
        }
        try {
            handler.tick();
        } catch (Throwable t) {
            status = Status.FAILED;
            failReason = "子步骤出错: " + t.getMessage();
        }
        return status;
    }

    public void cancel() {
        if (handler != null && status == Status.RUNNING) {
            try {
                handler.onCancel();
            } catch (Throwable ignored) {
            }
        }
    }

    public String failReason() {
        return failReason == null ? "未知原因" : failReason;
    }

    public String action() {
        return step.action;
    }

    /** 桥接的 StepControl:完成/失败只改自己的状态,收获与持续标记透传给父步骤 */
    private class Bridge implements StepControl {
        private final StepControl parent;

        Bridge(StepControl parent) {
            this.parent = parent;
        }

        @Override
        public MinecraftClient client() {
            return parent.client();
        }

        @Override
        public TaskStep step() {
            return step;
        }

        @Override
        public int ticks() {
            return ticks;
        }

        @Override
        public Map<String, Integer> gainsSoFar() {
            return parent.gainsSoFar();
        }

        @Override
        public void markOpenEnded(String playerHint) {
            parent.markOpenEnded(playerHint);
        }

        @Override
        public void complete() {
            if (status == Status.RUNNING) status = Status.DONE;
        }

        @Override
        public void fail(String reason) {
            if (status == Status.RUNNING) {
                status = Status.FAILED;
                failReason = reason;
            }
        }
    }
}
