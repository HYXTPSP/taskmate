package com.hyxt.taskmate.api;

/**
 * 插件入口。其他模组在自己的 fabric.mod.json 中声明:
 * <pre>
 * "entrypoints": {
 *   "taskmate": [ "com.example.MyTaskmatePlugin" ]
 * }
 * </pre>
 * 实现类在游戏启动时会被调用,可向 Taskmate 注册自定义 AI 动作。
 * 注册的动作会自动出现在发给 AI 的系统提示词里,AI 即可在计划中使用它们。
 */
public interface TaskmateEntrypoint {

    void register(TaskmateApi api);
}
