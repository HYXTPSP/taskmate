package com.hyxt.taskmate.plan;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 计划中的单个步骤。action 为注册表中的动作名,args 保留 AI 给出的原始 JSON 参数。 */
public class TaskStep {

    public final String action;
    public final JsonObject args;
    public final String desc;

    public TaskStep(String action, JsonObject args, String desc) {
        this.action = action.toLowerCase(Locale.ROOT);
        this.args = args == null ? new JsonObject() : args;
        this.desc = desc;
    }

    public int getInt(String key, int def) {
        return args.has(key) && args.get(key).isJsonPrimitive() ? args.get(key).getAsInt() : def;
    }

    public boolean has(String key) {
        return args.has(key) && !args.get(key).isJsonNull();
    }

    public String getString(String key, String def) {
        return args.has(key) && args.get(key).isJsonPrimitive() ? args.get(key).getAsString() : def;
    }

    public List<String> getStringList(String key) {
        List<String> out = new ArrayList<>();
        if (args.has(key)) {
            if (args.get(key).isJsonArray()) {
                args.getAsJsonArray(key).forEach(e -> out.add(e.getAsString()));
            } else if (args.get(key).isJsonPrimitive()) {
                out.add(args.get(key).getAsString());
            }
        }
        return out;
    }

    public String describe() {
        if (desc != null && !desc.isBlank()) return desc;
        return action + " " + args;
    }
}
