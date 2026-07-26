package com.hyxt.taskmate.api;

/** 提供给插件的注册接口。 */
public interface TaskmateApi {

    /**
     * 注册一个 AI 可用的动作。
     * 若动作名与已有动作冲突,后注册的会覆盖先注册的(核心动作最先注册)。
     */
    void registerAction(ActionDefinition definition);
}
