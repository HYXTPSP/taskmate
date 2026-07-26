package com.hyxt.taskmate.exec;

import com.hyxt.taskmate.TaskmateClient;
import com.hyxt.taskmate.ai.AiSession;
import com.hyxt.taskmate.ai.TokenStats;
import com.hyxt.taskmate.api.ActionDefinition;
import com.hyxt.taskmate.api.StepControl;
import com.hyxt.taskmate.api.StepHandler;
import com.hyxt.taskmate.config.ModConfig;
import com.hyxt.taskmate.plan.Plan;
import com.hyxt.taskmate.plan.TaskStep;
import com.hyxt.taskmate.util.ChatUi;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 任务执行器:tick 驱动的状态机,步骤执行分发给 ActionRegistry 里注册的 StepHandler。
 * 计划 -> (确认) -> 逐步执行 -> 完成/失败(可自动上报 AI 重规划)
 * 执行中检测到玩家手动操作会自动暂停。
 */
public class TaskExecutor {

    public static final TaskExecutor INSTANCE = new TaskExecutor();

    public enum State { IDLE, AWAITING_CONFIRM, RUNNING, PAUSED }

    private State state = State.IDLE;
    private Plan pendingPlan;
    private Plan activePlan;
    private int stepIndex;
    private boolean stepStarted;
    private StepHandler handler;
    private StepControlImpl control;
    private boolean stepOpenEnded;
    private int stepTicks;
    private int graceTicks;
    private int replanCount;
    private boolean awaitingReplanPlan;
    private boolean autoMode;
    private Map<String, Integer> stepStartInventory = Map.of();
    private final Map<String, Integer> planGains = new TreeMap<>();

    private TaskExecutor() {}

    // ------------------------------------------------ 对外接口

    public synchronized void onPlanReceived(Plan plan) {
        ModConfig cfg = TaskmateClient.CONFIG;
        if (state == State.RUNNING || state == State.PAUSED) {
            cancelCurrentStep();
        }
        pendingPlan = plan;
        boolean replan = awaitingReplanPlan;
        awaitingReplanPlan = false;
        boolean needConfirm = !autoMode && (replan ? cfg.confirmReplan : cfg.requireConfirm);
        ChatUi.showPlan(plan, needConfirm);
        if (needConfirm) {
            state = State.AWAITING_CONFIRM;
        } else {
            if (autoMode) ChatUi.info("自动模式:直接执行。");
            confirmPending();
        }
    }

    /** 执行当前计划并开启自动模式:本任务后续计划(含重规划)不再询问,任务结束或 #停止 后自动关闭 */
    public synchronized void confirmAuto() {
        if (pendingPlan == null) {
            ChatUi.info("当前没有等待确认的计划。");
            return;
        }
        autoMode = true;
        ChatUi.info("自动模式已开启:本任务后续计划将直接执行,直到任务完成或 #停止。");
        confirmPending();
    }

    public synchronized void confirmPending() {
        if (pendingPlan == null) {
            ChatUi.info("当前没有等待确认的计划。");
            return;
        }
        activePlan = pendingPlan;
        pendingPlan = null;
        stepIndex = 0;
        stepStarted = false;
        planGains.clear();
        state = State.RUNNING;
        ChatUi.runningBanner(activePlan.summary.isBlank() ? "共 " + activePlan.steps.size() + " 步" : activePlan.summary);
    }

    public synchronized void cancelPending() {
        if (pendingPlan == null) {
            ChatUi.info("当前没有等待确认的计划。");
            return;
        }
        pendingPlan = null;
        autoMode = false;
        if (state == State.AWAITING_CONFIRM) state = State.IDLE;
        ChatUi.info("已取消该计划。");
    }

    /** 玩家手动终止(#停止 / 按键 / 按钮) */
    public synchronized void stopAll(String reason) {
        boolean hadWork = state != State.IDLE || pendingPlan != null;
        pendingPlan = null;
        cancelCurrentStep();
        activePlan = null;
        awaitingReplanPlan = false;
        replanCount = 0;
        autoMode = false;
        state = State.IDLE;
        if (hadWork) {
            ChatUi.info("任务已终止(" + reason + ")。");
            AiSession.INSTANCE.noteEvent("玩家终止了任务(" + reason + ")");
        }
    }

    /** 手动/自动暂停(计划保留,可继续) */
    public synchronized void pause(String reason) {
        if (state != State.RUNNING) return;
        cancelCurrentStep();
        state = State.PAUSED;
        ChatUi.pausedBanner(reason);
    }

    /** 从暂停恢复:当前步骤重新开始 */
    public synchronized void resume() {
        if (state != State.PAUSED) {
            ChatUi.info("当前没有已暂停的任务。");
            return;
        }
        stepStarted = false;
        state = State.RUNNING;
        ChatUi.info("继续执行任务。");
    }

    /** 玩家在任务执行中发来纠正指令:放弃当前计划,等 AI 的新计划 */
    public synchronized void pauseForCorrection() {
        if (state == State.RUNNING || state == State.PAUSED) {
            cancelCurrentStep();
            activePlan = null;
            state = State.IDLE;
            awaitingReplanPlan = true;
            ChatUi.info("任务已暂停,等待 AI 根据新指令重新规划…");
        }
    }

    public synchronized boolean isRunningOrPaused() {
        return state == State.RUNNING || state == State.PAUSED;
    }

    public synchronized void hardReset() {
        pendingPlan = null;
        cancelCurrentStep();
        activePlan = null;
        awaitingReplanPlan = false;
        replanCount = 0;
        autoMode = false;
        state = State.IDLE;
    }

    public synchronized boolean isAutoMode() {
        return autoMode;
    }

    public synchronized State state() {
        return state;
    }

    /** HUD 与状态快照共用 */
    public synchronized String statusLine() {
        return switch (state) {
            case IDLE -> "空闲";
            case AWAITING_CONFIRM -> "有一个计划等待玩家确认";
            case PAUSED -> "任务已暂停(第 " + (stepIndex + 1) + " 步)";
            case RUNNING -> activePlan == null ? "执行中" :
                    "正在执行第 " + (stepIndex + 1) + "/" + activePlan.steps.size() + " 步: "
                            + activePlan.steps.get(stepIndex).describe();
        };
    }

    public synchronized String hudStepLine() {
        if (activePlan == null || stepIndex >= activePlan.steps.size()) return "";
        return "步骤 " + (stepIndex + 1) + "/" + activePlan.steps.size() + ": "
                + activePlan.steps.get(stepIndex).describe();
    }

    // ------------------------------------------------ tick 驱动

    public synchronized void tick(MinecraftClient client) {
        if (state != State.RUNNING) return;
        if (client.player == null || client.world == null) {
            stopAll("玩家离开世界");
            return;
        }
        List<TaskStep> steps = activePlan.steps;
        if (stepIndex >= steps.size()) {
            finishPlan();
            return;
        }
        TaskStep step = steps.get(stepIndex);

        if (!stepStarted) {
            startStep(client, step);
            return;
        }

        // 手动操作检测:玩家碰了移动键 → 自动暂停,不抢操作
        ModConfig cfg = TaskmateClient.CONFIG;
        if (cfg.pauseOnManualInput && currentStepNeedsBaritone() && anyMovementKeyDown(client)) {
            pause("检测到手动操作");
            return;
        }

        stepTicks++;
        if (control != null) control.ticks = stepTicks;
        if (graceTicks > 0) {
            graceTicks--;
            return;
        }

        if (!stepOpenEnded && stepTicks > cfg.stepTimeoutSeconds * 20L) {
            control.fail("步骤超时(超过 " + cfg.stepTimeoutSeconds + " 秒)");
            return;
        }

        try {
            handler.tick();
        } catch (Throwable t) {
            control.fail("执行出错: " + t.getMessage());
        }
    }

    // ------------------------------------------------ 步骤生命周期

    private void startStep(MinecraftClient client, TaskStep step) {
        ActionDefinition def = ActionRegistry.get(step.action);
        stepStarted = true;
        stepTicks = 0;
        graceTicks = 20;
        stepOpenEnded = false;
        stepStartInventory = inventoryCounts(client);
        ChatUi.stepBanner(stepIndex + 1, activePlan.steps.size(), step.describe());

        control = new StepControlImpl(client, step);

        if (def == null) {
            control.fail("未注册的动作: " + step.action);
            return;
        }
        if (def.needsBaritone() && !BaritoneCheck.available()) {
            replanCount = TaskmateClient.CONFIG.maxAutoReplans; // 没装 Baritone,重规划也没意义
            control.fail("Baritone 不可用,无法执行该动作。请在 mods 中安装 baritone-api-fabric(注意不是 standalone 版)");
            return;
        }
        if (def.needsBaritone()) {
            BaritoneBridge.applySettings();
        }

        handler = def.handlerFactory().get();
        handler.init(control);
        try {
            handler.start();
        } catch (Throwable t) {
            control.fail("执行出错: " + t.getMessage());
        }
    }

    private void cancelCurrentStep() {
        if (handler != null) {
            try {
                handler.onCancel();
            } catch (Throwable ignored) {
            }
        }
        releaseControls();
        stepStarted = false;
    }

    /** 停掉 Baritone 并强制松开所有被程序按住的按键(修复左键长按卡住) */
    private void releaseControls() {
        if (BaritoneCheck.available()) {
            BaritoneBridge.cancelAll();
            BaritoneBridge.releaseKeys();
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.options != null) {
            client.options.attackKey.setPressed(false);
            client.options.useKey.setPressed(false);
            client.options.forwardKey.setPressed(false);
            client.options.backKey.setPressed(false);
            client.options.leftKey.setPressed(false);
            client.options.rightKey.setPressed(false);
            client.options.jumpKey.setPressed(false);
            client.options.sneakKey.setPressed(false);
            client.options.sprintKey.setPressed(false);
        }
    }

    private void recordStepGains(MinecraftClient client) {
        for (Map.Entry<String, Integer> e : inventoryDiff(client).entrySet()) {
            planGains.merge(e.getKey(), e.getValue(), Integer::sum);
        }
    }

    private void finishPlan() {
        Plan done = activePlan;
        activePlan = null;
        handler = null;
        state = State.IDLE;
        replanCount = 0;
        autoMode = false;
        releaseControls();
        String gains = describeGains();
        ChatUi.success("任务完成!"
                + (gains.isEmpty() ? "" : " 获得: " + gains)
                + " [本任务消耗 " + TokenStats.taskTotal() + " tokens]");
        ModConfig cfg = TaskmateClient.CONFIG;
        String event = "计划已全部执行完成。" + (gains.isEmpty() ? "" : "实际获得物品: " + gains + "。");
        if (cfg.reportTaskResultToAi && !TokenStats.taskCapExceeded()) {
            AiSession.INSTANCE.systemEvent(event + "请用一句话向玩家简短总结(type=chat)。");
        } else {
            AiSession.INSTANCE.noteEvent(event);
        }
    }

    private void onStepFailed(TaskStep step, String reason) {
        ModConfig cfg = TaskmateClient.CONFIG;
        cancelCurrentStep();
        activePlan = null;
        handler = null;
        state = State.IDLE;
        ChatUi.error("步骤失败: " + step.describe() + " —— " + reason);
        if (TokenStats.taskCapExceeded()) {
            autoMode = false;
            ChatUi.error("本任务 token 消耗已达上限(" + cfg.maxTokensPerTask + "),不再自动重规划。");
            AiSession.INSTANCE.noteEvent("任务失败: " + reason + "(token 上限已到,未自动重规划)");
            return;
        }
        if (cfg.autoReplanOnFailure && replanCount < cfg.maxAutoReplans && !AiSession.INSTANCE.isBusy()) {
            replanCount++;
            awaitingReplanPlan = true;
            ChatUi.info("正在请求 AI 重新规划…(第 " + replanCount + "/" + cfg.maxAutoReplans + " 次)");
            String gains = describeGains();
            AiSession.INSTANCE.systemEvent("第 " + (stepIndex + 1) + " 步「" + step.describe() + "」执行失败,原因: " + reason
                    + (gains.isEmpty() ? "" : "。目前已获得: " + gains)
                    + "。请根据当前状态重新规划(type=plan),或说明无法完成(type=chat)。");
        } else {
            autoMode = false;
            AiSession.INSTANCE.noteEvent("任务失败: " + reason);
        }
    }

    private String describeGains() {
        if (planGains.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Map.Entry<String, Integer> e : planGains.entrySet()) {
            if (n++ > 0) sb.append(", ");
            sb.append(e.getKey()).append("×").append(e.getValue());
        }
        return sb.toString();
    }

    // ------------------------------------------------ 手动操作检测(读硬件按键,避开 Baritone 的输入注入)

    private boolean currentStepNeedsBaritone() {
        if (activePlan == null || stepIndex >= activePlan.steps.size()) return false;
        ActionDefinition def = ActionRegistry.get(activePlan.steps.get(stepIndex).action);
        return def != null && def.needsBaritone();
    }

    private static boolean anyMovementKeyDown(MinecraftClient client) {
        if (client.currentScreen != null) return false; // 打开界面时按键属于界面
        long window = client.getWindow().getHandle();
        KeyBinding[] keys = {
                client.options.forwardKey, client.options.backKey,
                client.options.leftKey, client.options.rightKey,
                client.options.jumpKey, client.options.sneakKey
        };
        for (KeyBinding kb : keys) {
            InputUtil.Key key = KeyBindingHelper.getBoundKeyOf(kb);
            if (key != null && key.getCategory() == InputUtil.Type.KEYSYM
                    && key.getCode() != InputUtil.UNKNOWN_KEY.getCode()
                    && InputUtil.isKeyPressed(window, key.getCode())) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------ 背包统计

    private static Map<String, Integer> inventoryCounts(MinecraftClient client) {
        Map<String, Integer> counts = new HashMap<>();
        if (client.player == null) return counts;
        for (int i = 0; i < client.player.getInventory().size(); i++) {
            ItemStack stack = client.player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            counts.merge(Registries.ITEM.getId(stack.getItem()).getPath(), stack.getCount(), Integer::sum);
        }
        return counts;
    }

    /** 相对本步骤开始时的净增量(只含正增量) */
    private Map<String, Integer> inventoryDiff(MinecraftClient client) {
        Map<String, Integer> now = inventoryCounts(client);
        Map<String, Integer> diff = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : now.entrySet()) {
            int gained = e.getValue() - stepStartInventory.getOrDefault(e.getKey(), 0);
            if (gained > 0) diff.put(e.getKey(), gained);
        }
        return diff;
    }

    // ------------------------------------------------ StepControl 实现

    private class StepControlImpl implements StepControl {
        private final MinecraftClient client;
        private final TaskStep step;
        private boolean done = false;
        int ticks = 0;

        StepControlImpl(MinecraftClient client, TaskStep step) {
            this.client = client;
            this.step = step;
        }

        @Override
        public MinecraftClient client() {
            return client;
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
            synchronized (TaskExecutor.this) {
                return inventoryDiff(client);
            }
        }

        @Override
        public void markOpenEnded(String playerHint) {
            synchronized (TaskExecutor.this) {
                stepOpenEnded = true;
                if (playerHint != null && !playerHint.isBlank()) ChatUi.info(playerHint);
            }
        }

        @Override
        public void complete() {
            synchronized (TaskExecutor.this) {
                if (done || this != control) return;
                done = true;
                recordStepGains(client);
                stepIndex++;
                stepStarted = false;
                handler = null;
                if (activePlan != null && stepIndex >= activePlan.steps.size()) {
                    finishPlan();
                }
            }
        }

        @Override
        public void fail(String reason) {
            synchronized (TaskExecutor.this) {
                if (done || this != control) return;
                done = true;
                recordStepGains(client);
                onStepFailed(step, reason);
            }
        }
    }
}
