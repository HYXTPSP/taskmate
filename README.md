# AI Taskmate

下达任务型 AI 助手模组(Fabric 1.21.8,纯客户端)。

聊天栏输入 `#` 开头的内容会被拦截并发给 AI(OpenAI 兼容接口),AI 拟定计划后在聊天栏展示,点击 **【✔ 执行】** 才会开始执行(底层由 Baritone 驱动)。不执行任务时就是普通的 Minecraft。

## 功能

- `#帮我砍20个橡木` —— 下达任务,AI 结合你的坐标、背包、周围环境、已记住的地点拟定分步计划
- 计划展示后三个按钮:**【✔ 执行】**(只执行这一个计划)、**【⚡ 自动】**(开启自动模式:本任务后续计划含失败重规划都直接执行,任务完成或 `#停止` 后自动关闭)、**【✘ 取消】**;对应 `#执行` / `#自动` / `#取消`
- 执行中随时可终止:`#停止`、点击 **【⏹ 终止】**、或快捷键(默认 `J`,可在按键设置中改)
- **手动操作自动暂停**:任务执行中你一碰移动键,AI 立刻松手暂停,聊天栏出现【▶ 继续】按钮(`#继续` 同效)
- 执行中可直接纠正:`#别挖石头了,去砍桦木` —— 当前任务暂停,AI 重新规划
- 步骤失败自动上报 AI 重新规划(默认最多 3 次,可配置);失败/完成时会带上"实际获得了哪些物品"的背包校验结果
- **HUD 状态条**:左上角显示任务状态、当前步骤、token 消耗(空闲时不显示)
- **token 统计与上限**:每次任务结束显示消耗;`maxTokensPerTask` 可设上限,超过后停止自动重规划
- **上下文与地点记忆持久化**:按服务器地址/存档名分别存盘(`config/taskmate/context/`),跨会话有效;对 AI 说"记住这里是家",之后"回家"就能直达(`remember`/`forget` 动作)
- `#重置` 清空当前世界的上下文;`#设置` 打开 API 配置界面(Key 界面默认脱敏)
- 触发前缀 `#` 可在配置中改(如 `@ai `),避免与服务器聊天插件冲突;普通聊天完全不受影响

## 构建

要求:JDK 21,网络可访问 maven.fabricmc.net 与 GitHub。

```bash
./gradlew build          # Windows 用 gradlew.bat build
```

首次构建会自动从 GitHub 下载 `baritone-api-fabric-1.15.0.jar` 到 `libs/`(编译期依赖)。
如果下载失败(网络原因),请手动从 https://github.com/cabaletta/baritone/releases/tag/v1.15.0
下载 `baritone-api-fabric-1.15.0.jar` 放入项目 `libs/` 目录后重新构建。

产物在 `build/libs/taskmate-<版本>.jar`。

## 安装(游戏侧)

`mods` 文件夹中需要:

1. Fabric API(1.21.8 对应版本)
2. **`baritone-api-fabric-1.15.0.jar`**(Baritone Releases 页面下载;不装也能聊天,但无法执行移动/挖掘类任务)
3. 本模组 jar

> ⚠️ 必须用 **api** 发行版,**不要用 standalone 版**:standalone 把 `baritone.api` 类也混淆了,
> 本模组无法对接(装错会在聊天栏提示)。api 版同样是完整可用的 Baritone。

Fabric Loader 需 0.16 及以上。

## 配置说明(config/taskmate.json)

| 字段 | 默认 | 说明 |
|---|---|---|
| baseUrl | https://api.openai.com/v1 | OpenAI 兼容接口地址(DeepSeek/通义/Ollama/OpenRouter 均可) |
| apiKey | (空) | API Key |
| model | gpt-4o-mini | 模型名 |
| temperature / maxTokens | 0.4 / 2048 | 采样参数 |
| requestTimeoutSeconds | 120 | 请求超时 |
| useJsonMode | true | 使用 response_format=json_object(不支持的服务商自动降级) |
| triggerPrefix | # | 触发前缀 |
| pauseOnManualInput | true | 手动移动时自动暂停任务 |
| showHud | true | 显示 HUD 状态条 |
| maxHistoryMessages | 16 | 上下文最大消息数(旧消息的状态快照发送时自动剥掉) |
| persistContext | true | 上下文/记忆按存档持久化 |
| extraSystemPrompt | (空) | 追加到系统提示词的自定义内容 |
| requireConfirm | true | 计划是否需要点击执行确认 |
| autoReplanOnFailure | true | 步骤失败自动请 AI 重规划 |
| maxAutoReplans | 3 | 自动重规划次数上限 |
| confirmReplan | true | 重规划的新计划是否也需确认 |
| stepTimeoutSeconds | 300 | 单步骤超时(持续性步骤除外) |
| reportTaskResultToAi | false | 任务完成后是否让 AI 总结一句(开启多花一次请求) |
| maxTokensPerTask | 0 | 单任务 token 上限,0 不限 |
| autoEat / autoEatAt | true / 14 | 任务中自动进食及饥饿阈值 |
| autoDefend | true | 任务中遇袭自动反击并自动恢复任务 |
| combatAbortHealth | 6 | 战斗中生命值低于该值时中止 |
| allowBreak / allowPlace | true | 允许 AI 破坏/放置方块(透传 Baritone) |

## AI 可用动作(当前版本)

- 基础:goto(坐标)、goto_block(找最近方块)、mine(采集,可限数量,带收获校验)、collect(捡起附近全部掉落物)、follow(跟随)、wait、say、stop、remember/forget(地点记忆)
- **合成与熔炼**:craft 带**递归材料解析器**(Altoclef TaskCatalogue 思路):缺木板自动用原木合、缺木棍自动用木板合、缺工作台自动合成并放置,只有缺基础原料才失败并精确告知"缺少 xxx×n"——这些全在本地完成,不消耗 token;smelt 熔炉全流程,中断/结束时自动把炉内物品取回,防止物品滞留丢失
- **容器**:withdraw/deposit(按记忆地点或就近找箱子/木桶,shift 搬运)
- **战斗**:kill(索敌→接近→冷却满挥刀,支持 "hostile" 通配,低血量自动中止)
- **世界操作**:place(放工作台/熔炉/箱子等)、equip(自动穿甲/切换手持)

配合系统提示词里的"物品获取链"策略,AI 能把"帮我搞一把铁镐"自动分解成
砍树→木板→工作台→木镐→挖石→石镐→挖铁→熔炉→熔铁→铁镐 的完整计划,失败自动补前置步骤重规划。

**生存保障层**(任务执行期间自动生效,可在配置关闭):饿了自动吃(黑名单食物除外)、
被打自动暂停任务反击、**低血量自动放弃反击并撤退**、威胁解除自动继续、
死亡自动终止任务并把死亡点记为记忆地点(复活后说"去死亡点拿装备",AI 会 goto+collect 两步捡回)。

执行层会把计划、每步的开始/完成/失败和背包变化写入 `config/taskmate/task.log`,排查问题时把它发出来即可。

注:craft 依赖客户端配方书,配方需已解锁(vanilla 拿到相关材料就会解锁,正常生存流程无感)。

## 插件系统:给 AI 添加自定义动作

其他模组可以向 Taskmate 注册新动作,注册后 AI 的系统提示词会自动包含它,AI 即可在计划中使用。

1. 依赖本模组(compileOnly 即可),在你模组的 `fabric.mod.json` 里声明入口:

```json
"entrypoints": { "taskmate": [ "com.example.MyPlugin" ] }
```

2. 实现入口并注册动作:

```java
public class MyPlugin implements TaskmateEntrypoint {
    @Override
    public void register(TaskmateApi api) {
        api.registerAction(new ActionDefinition(
            "dance", List.of(),                       // 动作名与别名
            "{\"action\":\"dance\",\"seconds\":3,\"desc\":\"...\"} —— 原地转圈跳舞",
            false,                                     // 是否需要 Baritone
            DanceHandler::new));                       // 每次执行创建新 handler
    }
}

public class DanceHandler extends StepHandler {
    @Override public void start() { /* 开始动作;瞬时动作可直接 control.complete() */ }
    @Override public void tick() {
        // 每 tick 调用;control.step() 取参数,control.ticks() 取已执行 tick 数
        if (control.ticks() >= control.step().getInt("seconds", 3) * 20) control.complete();
    }
    @Override public void onCancel() { /* 玩家终止/暂停时清理 */ }
}
```

`StepControl` 还提供 `gainsSoFar()`(本步骤背包净增量)、`markOpenEnded()`(标记为持续性步骤)、`fail(原因)` 等。
核心动作(goto/mine/…)与插件动作走的是同一套注册机制,可参考源码 `exec/CoreActions.java`。

## CI 自动构建

仓库已附 GitHub Actions 配置(`.github/workflows/build.yml`):推送到 GitHub 后自动编译,
在仓库 Actions 页面的运行记录里下载 `taskmate-jars` 产物,无需本地装 JDK。

## 已知限制

- 多人服务器上使用属于自动化操作,可能违反服务器规则/反作弊,请自行斟酌
- 不限数量的采集和跟随是持续性步骤,需手动 `#停止`
- API Key 明文存储在本地配置文件中,请勿分享该文件;对话上下文明文存在 `config/taskmate/context/`

## 升级到 1.21.11 的路线

代码中所有 Baritone 调用都收拢在 `exec/BaritoneBridge.java`,等 Baritone 发布 1.21.9+/1.21.11 支持后,
更新 `gradle.properties` 中的版本号并适配少量 mappings 变化即可(1.21.11 后 Yarn 停更,需迁移到 Mojang mappings)。
