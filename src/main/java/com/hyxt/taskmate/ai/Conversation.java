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

    /** 返回裁剪后的历史副本(最多 maxMessages 条,从最旧开始丢弃) */
    public synchronized List<Msg> messages(int maxMessages) {
        int from = Math.max(0, history.size() - Math.max(2, maxMessages));
        return new ArrayList<>(history.subList(from, history.size()));
    }
}
