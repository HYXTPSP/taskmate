package com.hyxt.taskmate.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 模组配置,JSON 持久化到 config/taskmate.json。
 * 界面里只暴露最常用的三项(baseUrl / apiKey / model),
 * 其余高级选项直接编辑 JSON 文件即可。
 */
public class ModConfig {

    // ---- AI 接口(OpenAI 兼容) ----
    /** OpenAI 兼容接口地址,可以带或不带 /chat/completions 后缀,例如:
     *  https://api.openai.com/v1
     *  https://api.deepseek.com/v1
     *  http://127.0.0.1:11434/v1  (Ollama) */
    public String baseUrl = "https://api.openai.com/v1";
    public String apiKey = "";
    public String model = "gpt-4o-mini";
    public double temperature = 0.4;
    public int maxTokens = 4096;
    /** 请求超时(秒) */
    public int requestTimeoutSeconds = 120;
    /** 使用 OpenAI JSON 模式(response_format=json_object),服务商不支持时自动降级 */
    public boolean useJsonMode = true;

    // ---- 交互 ----
    /** 触发前缀:以此开头的聊天消息被拦截并发给 AI(可改为 "@ai " 等,避免与服务器聊天插件冲突) */
    public String triggerPrefix = "#";
    /** 任务执行中检测到玩家手动移动时自动暂停 */
    public boolean pauseOnManualInput = true;
    /** 显示 HUD 任务状态条 */
    public boolean showHud = true;

    // ---- 对话上下文 ----
    /** 发送给 AI 的最大历史消息条数(超出后丢弃最旧的;旧消息的状态快照会自动剥掉以省 token) */
    public int maxHistoryMessages = 16;
    /** 上下文与地点记忆按 服务器/存档 持久化到磁盘 */
    public boolean persistContext = true;
    /** 追加到系统提示词末尾的自定义内容 */
    public String extraSystemPrompt = "";

    // ---- 任务执行 ----
    /** 每个计划执行前是否需要玩家点击「执行」确认 */
    public boolean requireConfirm = true;
    /** 步骤失败后是否自动上报 AI 重新规划 */
    public boolean autoReplanOnFailure = true;
    /** 自动重规划的最大次数(防止 token 烧穿) */
    public int maxAutoReplans = 3;
    /** 重新规划出的新计划是否也需要确认 */
    public boolean confirmReplan = true;
    /** 单个步骤的超时时间(秒) */
    public int stepTimeoutSeconds = 300;
    /** 任务全部完成后是否上报 AI(让 AI 总结一句)。默认关闭省 token,本地会显示收获清单 */
    public boolean reportTaskResultToAi = false;
    /** 单个任务的 token 消耗上限,超过后不再自动重规划/总结(0 = 不限) */
    public long maxTokensPerTask = 0;

    // ---- 生存保障层 ----
    /** 任务执行中饥饿低于该值时自动进食 */
    public boolean autoEat = true;
    public int autoEatAt = 14;
    /** 任务执行中遭袭自动反击,解除后自动继续任务 */
    public boolean autoDefend = true;
    /** kill 动作在生命值低于该值时中止 */
    public int combatAbortHealth = 6;
    /** 任务中光照过低时自动插火把(需背包有火把) */
    public boolean autoTorch = true;
    /** 工具即将损坏时自动换备用/预警 */
    public boolean toolGuard = true;

    // ---- 安全边界(透传给 Baritone) ----
    /** 允许 AI 破坏方块 */
    public boolean allowBreak = true;
    /** 允许 AI 放置方块(消耗背包物品) */
    public boolean allowPlace = true;

    // ------------------------------------------------------------

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("taskmate.json");
    }

    public static ModConfig load() {
        try {
            Path p = path();
            if (Files.exists(p)) {
                String json = Files.readString(p, StandardCharsets.UTF_8);
                ModConfig cfg = GSON.fromJson(json, ModConfig.class);
                if (cfg != null) {
                    cfg.save(); // 回写,补全新增字段
                    return cfg;
                }
            }
        } catch (Exception e) {
            System.err.println("[Taskmate] 读取配置失败,使用默认配置: " + e);
        }
        ModConfig cfg = new ModConfig();
        cfg.save();
        return cfg;
    }

    public void save() {
        try {
            Files.createDirectories(path().getParent());
            Files.writeString(path(), GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Taskmate] 保存配置失败: " + e);
        }
    }

    /** 界面展示用:脱敏后的 Key,如 sk-ab****cd */
    public String maskedKey() {
        if (apiKey == null || apiKey.isBlank()) return "(未设置)";
        if (apiKey.length() <= 8) return "****";
        return apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4);
    }
}
