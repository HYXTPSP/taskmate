package com.hyxt.taskmate.api;

/**
 * 单个步骤的执行器。生命周期:
 * start() -> (每 tick)tick() -> 你调用 control.complete()/fail() 结束
 * 玩家终止/暂停时会调用 onCancel()。
 *
 * 框架保证所有回调都在客户端主线程执行;
 * start() 之后有约 1 秒宽限期才开始调用 tick()(给 Baritone 接管的时间);
 * 非 openEnded 的步骤超过配置的 stepTimeoutSeconds 会被自动判为失败。
 */
public abstract class StepHandler {

    protected StepControl control;

    /** 框架调用,插件不要调用 */
    public final void init(StepControl control) {
        this.control = control;
    }

    /** 步骤开始。可以在这里直接 control.complete()(瞬时动作)。抛异常等价于 fail。 */
    public abstract void start() throws Exception;

    /** 宽限期后每个客户端 tick 调用一次,自行判断完成/失败。 */
    public void tick() {}

    /** 步骤被终止/暂停时调用(complete/fail 之后不会再调)。 */
    public void onCancel() {}
}
