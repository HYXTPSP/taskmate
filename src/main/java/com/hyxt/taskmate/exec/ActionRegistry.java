package com.hyxt.taskmate.exec;

import com.hyxt.taskmate.api.ActionDefinition;
import com.hyxt.taskmate.api.TaskmateApi;
import com.hyxt.taskmate.api.TaskmateEntrypoint;
import net.fabricmc.loader.api.FabricLoader;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 动作注册表:核心动作与插件动作统一管理,系统提示词由这里动态生成。 */
public final class ActionRegistry {

    private static final Map<String, ActionDefinition> BY_NAME = new LinkedHashMap<>();
    private static final Set<ActionDefinition> DEFINITIONS = new LinkedHashSet<>();

    private ActionRegistry() {}

    public static void init() {
        CoreActions.registerAll();
        CraftingActions.register();
        ContainerActions.register();
        CombatActions.register();
        WorldActions.register();
        ObtainAction.register();
        // 加载其他模组通过 "taskmate" entrypoint 注册的动作
        FabricLoader.getInstance()
                .getEntrypointContainers("taskmate", TaskmateEntrypoint.class)
                .forEach(container -> {
                    try {
                        container.getEntrypoint().register(REGISTRAR);
                        System.out.println("[Taskmate] 已加载插件动作: " + container.getProvider().getMetadata().getId());
                    } catch (Throwable t) {
                        System.err.println("[Taskmate] 插件 " + container.getProvider().getMetadata().getId()
                                + " 注册失败: " + t);
                    }
                });
    }

    private static final TaskmateApi REGISTRAR = ActionRegistry::register;

    public static synchronized void register(ActionDefinition def) {
        DEFINITIONS.remove(BY_NAME.get(def.name()));
        DEFINITIONS.add(def);
        BY_NAME.put(def.name(), def);
        for (String alias : def.aliases()) {
            BY_NAME.put(alias.toLowerCase(Locale.ROOT), def);
        }
    }

    public static synchronized ActionDefinition get(String name) {
        return BY_NAME.get(name.toLowerCase(Locale.ROOT));
    }

    /** 生成系统提示词中的动作文档 */
    public static synchronized String promptDocs() {
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (ActionDefinition def : DEFINITIONS) {
            sb.append(i++).append(". ").append(def.promptDoc()).append('\n');
        }
        return sb.toString();
    }

    /** 合法动作名列表(解析报错时反馈给 AI) */
    public static synchronized String validNames() {
        return String.join(", ", BY_NAME.keySet());
    }
}
