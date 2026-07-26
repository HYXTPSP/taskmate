package com.hyxt.taskmate.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hyxt.taskmate.TaskmateClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 按「服务器地址 / 单人存档名」持久化对话上下文与地点记忆。
 * 文件位置: config/taskmate/context/<key>.json
 */
public final class ContextStore {

    public record MemoryEntry(String name, int x, int y, int z, String dimension, String note) {}

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private static String currentKey = null;
    private static final List<MemoryEntry> memories = new ArrayList<>();

    private ContextStore() {}

    // ------------------------------------------------ 世界进出

    public static synchronized void onJoin(MinecraftClient client) {
        String key = computeKey(client);
        if (key == null || key.equals(currentKey)) return;
        saveNow();
        currentKey = key;
        memories.clear();
        AiSession.INSTANCE.conversation().clear();
        if (TaskmateClient.CONFIG.persistContext) {
            load(key);
        }
    }

    public static synchronized void onDisconnect() {
        saveNow();
        currentKey = null;
        memories.clear();
        AiSession.INSTANCE.conversation().clear();
    }

    private static String computeKey(MinecraftClient client) {
        try {
            if (client.getCurrentServerEntry() != null) {
                return sanitize("server_" + client.getCurrentServerEntry().address);
            }
            if (client.isInSingleplayer() && client.getServer() != null) {
                return sanitize("sp_" + client.getServer().getSaveProperties().getLevelName());
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5._-]", "_");
    }

    // ------------------------------------------------ 记忆

    public static synchronized void remember(String name, int x, int y, int z, String dimension, String note) {
        memories.removeIf(m -> m.name().equalsIgnoreCase(name));
        memories.add(new MemoryEntry(name, x, y, z, dimension, note == null ? "" : note));
        saveNow();
    }

    public static synchronized MemoryEntry findMemory(String name) {
        for (MemoryEntry m : memories) {
            if (m.name().equalsIgnoreCase(name)) return m;
        }
        return null;
    }

    public static synchronized boolean forget(String name) {
        boolean removed = memories.removeIf(m -> m.name().equalsIgnoreCase(name));
        if (removed) saveNow();
        return removed;
    }

    /** 给状态快照用 */
    public static synchronized String describeMemories() {
        if (memories.isEmpty()) return "无";
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (MemoryEntry m : memories) {
            if (n++ > 0) sb.append("; ");
            sb.append(m.name()).append("(").append(m.x()).append(",").append(m.y()).append(",").append(m.z())
                    .append(", ").append(m.dimension()).append(")");
            if (!m.note().isBlank()) sb.append(" 备注:").append(m.note());
        }
        return sb.toString();
    }

    // ------------------------------------------------ 存取

    private static Path fileFor(String key) {
        return FabricLoader.getInstance().getConfigDir().resolve("taskmate").resolve("context").resolve(key + ".json");
    }

    public static synchronized void saveNow() {
        if (currentKey == null || !TaskmateClient.CONFIG.persistContext) return;
        try {
            JsonObject root = new JsonObject();
            // 只持久化地点记忆;对话上下文按任务隔离,不落盘
            JsonArray mems = new JsonArray();
            for (MemoryEntry m : memories) {
                mems.add(GSON.toJsonTree(m));
            }
            root.add("memories", mems);
            Path p = fileFor(currentKey);
            Files.createDirectories(p.getParent());
            Files.writeString(p, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Taskmate] 保存上下文失败: " + e);
        }
    }

    private static void load(String key) {
        try {
            Path p = fileFor(key);
            if (!Files.exists(p)) return;
            JsonObject root = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.has("memories")) {
                root.getAsJsonArray("memories").forEach(el ->
                        memories.add(GSON.fromJson(el, MemoryEntry.class)));
            }
        } catch (Exception e) {
            System.err.println("[Taskmate] 读取上下文失败: " + e);
        }
    }
}
