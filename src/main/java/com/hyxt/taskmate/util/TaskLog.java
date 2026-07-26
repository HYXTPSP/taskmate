package com.hyxt.taskmate.util;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 本地执行日志:config/taskmate/task.log。
 * 记录计划、步骤开始/完成/失败与背包变化,出问题时用于排查(比如物品去向)。
 */
public final class TaskLog {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss");

    private TaskLog() {}

    public static void log(String message) {
        try {
            Path p = FabricLoader.getInstance().getConfigDir().resolve("taskmate").resolve("task.log");
            Files.createDirectories(p.getParent());
            String line = "[" + LocalDateTime.now().format(FMT) + "] " + message + System.lineSeparator();
            Files.writeString(p, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }
}
