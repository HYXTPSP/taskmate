package com.hyxt.taskmate.plan;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hyxt.taskmate.exec.ActionRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * 解析 AI 的回复。约定格式:
 * {
 *   "type": "plan" | "chat",
 *   "message": "给玩家看的话",
 *   "steps": [ {"action": "mine", "blocks": ["oak_log"], "count": 20, "desc": "..."} ]
 * }
 */
public final class PlanParser {

    public enum ReplyType { PLAN, CHAT }

    public static class Result {
        public final ReplyType type;
        public final String message;
        public final Plan plan;

        Result(ReplyType type, String message, Plan plan) {
            this.type = type;
            this.message = message;
            this.plan = plan;
        }
    }

    private PlanParser() {}

    public static Result parse(String raw) {
        String json = extractJson(raw);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();

        String typeStr = root.has("type") ? root.get("type").getAsString() : "chat";
        String message = root.has("message") && !root.get("message").isJsonNull()
                ? root.get("message").getAsString() : "";

        if (!"plan".equalsIgnoreCase(typeStr)) {
            return new Result(ReplyType.CHAT, message, null);
        }

        List<TaskStep> steps = new ArrayList<>();
        if (root.has("steps") && root.get("steps").isJsonArray()) {
            JsonArray arr = root.getAsJsonArray("steps");
            for (JsonElement el : arr) {
                JsonObject o = el.getAsJsonObject();
                if (!o.has("action")) throw new IllegalArgumentException("步骤缺少 action 字段");
                String action = o.get("action").getAsString();
                if (ActionRegistry.get(action) == null) {
                    throw new IllegalArgumentException(
                            "未知动作 \"" + action + "\",可用动作: " + ActionRegistry.validNames());
                }
                String desc = o.has("desc") && !o.get("desc").isJsonNull() ? o.get("desc").getAsString() : null;
                steps.add(new TaskStep(action, o, desc));
            }
        }
        if (steps.isEmpty()) {
            // type=plan 但没有步骤,按对话处理
            return new Result(ReplyType.CHAT, message, null);
        }
        return new Result(ReplyType.PLAN, message, new Plan(message, steps));
    }

    /** 容错:剥掉 markdown 代码块围栏、截取首尾大括号之间的内容 */
    private static String extractJson(String raw) {
        String s = raw.trim();
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            if (firstNewline > 0) s = s.substring(firstNewline + 1);
            int fence = s.lastIndexOf("```");
            if (fence >= 0) s = s.substring(0, fence);
            s = s.trim();
        }
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end <= start) throw new IllegalArgumentException("回复中找不到 JSON 对象");
        return s.substring(start, end + 1);
    }
}
