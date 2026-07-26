package com.hyxt.taskmate.ai;

import java.util.ArrayList;
import java.util.List;

/** 对话历史管理(可由 ContextStore 持久化,#重置 清空)。 */
public class Conversation {

    public record Msg(String role, String content) {}

    private final List<Msg> history = new ArrayList<>();

    public synchronized void addUser(String content) {
        history.add(new Msg("user", content));
    }

    public synchronized void addAssistant(String content) {
        history.add(new Msg("assistant", content));
    }

    /** 请求失败时,把刚加入的 user 消息撤回,避免污染历史 */
    public synchronized void dropLastIfUser() {
        if (!history.isEmpty() && "user".equals(history.get(history.size() - 1).role())) {
            history.remove(history.size() - 1);
        }
    }

    public synchronized void clear() {
        history.clear();
    }

    public synchronized List<Msg> copyAll() {
        return new ArrayList<>(history);
    }

    public synchronized void restore(List<Msg> msgs) {
        history.clear();
        history.addAll(msgs);
    }

    /**
     * 返回发送用的历史副本:最多 maxMessages 条,并把除最后一条之外的
     * 用户消息里的 [当前状态] 快照剥掉(旧快照早已过时,白白消耗 token)。
     */
    public synchronized List<Msg> messages(int maxMessages) {
        int from = Math.max(0, history.size() - Math.max(2, maxMessages));
        List<Msg> window = history.subList(from, history.size());
        List<Msg> out = new ArrayList<>(window.size());
        for (int i = 0; i < window.size(); i++) {
            Msg m = window.get(i);
            boolean last = i == window.size() - 1;
            if (!last && "user".equals(m.role())) {
                m = new Msg("user", stripSnapshot(m.content()));
            }
            out.add(m);
        }
        return out;
    }

    /** 去掉 [当前状态] 快照段,保留指令/事件文字 */
    private static String stripSnapshot(String content) {
        int s = content.indexOf("[当前状态]");
        if (s < 0) return content;
        String head = content.substring(0, s).trim();
        int cmd = content.indexOf("玩家指令", s);
        String tail = cmd >= 0 ? content.substring(cmd).trim() : "";
        StringBuilder sb = new StringBuilder();
        if (!head.isEmpty()) sb.append(head).append('\n');
        if (!tail.isEmpty()) sb.append(tail).append('\n');
        sb.append("(历史状态快照已省略)");
        return sb.toString();
    }
}
