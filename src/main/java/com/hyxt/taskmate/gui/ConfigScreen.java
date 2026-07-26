package com.hyxt.taskmate.gui;

import com.hyxt.taskmate.TaskmateClient;
import com.hyxt.taskmate.config.ModConfig;
import com.hyxt.taskmate.util.ChatUi;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;

/**
 * API 配置界面:Base URL / API Key / 模型名。
 * 其余高级选项在 config/taskmate.json 中编辑。
 */
public class ConfigScreen extends Screen {

    private final Screen parent;
    private TextFieldWidget urlField;
    private TextFieldWidget keyField;
    private TextFieldWidget modelField;
    private boolean showKey = false;

    public ConfigScreen(Screen parent) {
        super(Text.literal("AI Taskmate 设置"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        ModConfig cfg = TaskmateClient.CONFIG;
        int w = Math.min(320, this.width - 40);
        int x = (this.width - w) / 2;
        int y = 60;

        urlField = new TextFieldWidget(this.textRenderer, x, y, w, 20, Text.literal("Base URL"));
        urlField.setMaxLength(512);
        urlField.setText(cfg.baseUrl == null ? "" : cfg.baseUrl);
        this.addDrawableChild(urlField);

        keyField = new TextFieldWidget(this.textRenderer, x, y + 45, w - 60, 20, Text.literal("API Key"));
        keyField.setMaxLength(512);
        keyField.setText(cfg.apiKey == null ? "" : cfg.apiKey);
        applyKeyMask();
        this.addDrawableChild(keyField);

        this.addDrawableChild(ButtonWidget.builder(Text.literal("显示"), b -> {
            showKey = !showKey;
            b.setMessage(Text.literal(showKey ? "隐藏" : "显示"));
            applyKeyMask();
        }).dimensions(x + w - 55, y + 45, 55, 20).build());

        modelField = new TextFieldWidget(this.textRenderer, x, y + 90, w, 20, Text.literal("Model"));
        modelField.setMaxLength(128);
        modelField.setText(cfg.model == null ? "" : cfg.model);
        this.addDrawableChild(modelField);

        this.addDrawableChild(ButtonWidget.builder(Text.literal("保存"), b -> saveAndClose())
                .dimensions(this.width / 2 - 105, y + 130, 100, 20).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("取消"), b -> this.close())
                .dimensions(this.width / 2 + 5, y + 130, 100, 20).build());

        this.setInitialFocus(urlField);
    }

    private void applyKeyMask() {
        if (showKey) {
            keyField.setRenderTextProvider((str, idx) -> OrderedText.styledForwardsVisitedString(str, Style.EMPTY));
        } else {
            keyField.setRenderTextProvider((str, idx) -> OrderedText.styledForwardsVisitedString("*".repeat(str.length()), Style.EMPTY));
        }
    }

    private void saveAndClose() {
        ModConfig cfg = TaskmateClient.CONFIG;
        cfg.baseUrl = urlField.getText().trim();
        cfg.apiKey = keyField.getText().trim();
        cfg.model = modelField.getText().trim();
        cfg.save();
        ChatUi.info("配置已保存。当前模型: " + cfg.model + ",Key: " + cfg.maskedKey());
        this.close();
    }

    @Override
    public void close() {
        if (this.client != null) this.client.setScreen(parent);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 24, 0xFFFFFFFF);
        int w = Math.min(320, this.width - 40);
        int x = (this.width - w) / 2;
        context.drawTextWithShadow(this.textRenderer, "接口地址 (OpenAI 兼容, 如 https://api.deepseek.com/v1)", x, 48, 0xFFA0A0A0);
        context.drawTextWithShadow(this.textRenderer, "API Key", x, 93, 0xFFA0A0A0);
        context.drawTextWithShadow(this.textRenderer, "模型名称 (如 gpt-4o-mini / deepseek-chat)", x, 138, 0xFFA0A0A0);
    }
}
