package com.hyxt.taskmate.api;

import java.util.List;
import java.util.function.Supplier;

/**
 * 一个 AI 动作的定义。
 *
 * @param name           动作名(小写,AI 在 steps 里通过 "action" 字段引用)
 * @param aliases        别名(AI 偶尔会写同义词,做个兜底)
 * @param promptDoc      写进系统提示词的一行文档,格式建议:
 *                       {"action":"xxx","参数":"...","desc":"..."} —— 动作说明
 * @param needsBaritone  是否需要 Baritone(未安装时直接判失败)
 * @param handlerFactory 每次执行该动作时创建一个新的 StepHandler
 */
public record ActionDefinition(
        String name,
        List<String> aliases,
        String promptDoc,
        boolean needsBaritone,
        Supplier<StepHandler> handlerFactory
) {
    public ActionDefinition {
        name = name.toLowerCase(java.util.Locale.ROOT);
        aliases = aliases == null ? List.of() : aliases;
    }
}
