# 外屏状态上报（HachimoPhone Bridge）

本工作区配了一个「手机外屏状态显示器」：PC 上的 Bridge 在 `http://127.0.0.1:8765`，手机 App 通过 Wi-Fi 连过来，把状态渲染成小狗动画 + 文字。

Trae 没有 hook 机制，只能由你在执行过程中主动上报。规则如下。

> 已有自动兜底：`bridge/hooks/trae-watch.mjs` 会监听 Trae 的会话数据库，检测到活动就自动推 `trae / Trae 交互中`，25 秒无活动转 `待机`。
> 你上报的详细文案优先（45 秒内不会被兜底文案覆盖），所以建议每个阶段都自己报一次。

## 什么时候上报

| 时机 | state | label 示例 |
|---|---|---|
| 收到用户请求、开始分析 | `thinking` | `思考：检查 Claude 安装` |
| 开始执行工具/命令（同一批只报一次） | `tool` | `执行：构建 hachimo APK` |
| 需要用户拍板、等确认 | `waiting` | `需要你确认：是否覆盖配置` |
| 任务完成 | `done` | `任务完成：Claude 已安装` |
| 出错、被阻塞 | `error` | `出错：npm 权限不足` |

## 怎么上报

在仓库根目录执行（几十毫秒，失败静默退出）：

```bash
node bridge/report.mjs thinking "思考：检查 Claude 安装"
node bridge/report.mjs tool "执行：构建 hachimo APK"
node bridge/report.mjs done "任务完成：Claude 已安装"
```

`label` 控制在 32 字以内。也可以用环境变量改身份：`AGENT_NAME`、`AGENT_SESSION`、`BRIDGE_PORT`。

## 约束

- 上报只是锦上添花：命令失败、Bridge 没开，一律忽略，**不得重试，不得因此中断或阻塞任务**。
- 不要每个工具都报，粒度按「用户能感知的阶段」来；一次任务至少要有 `thinking` 和 `done`。
- 不要在纯问答/闲聊时上报。
- 不要把这个上报写进用户的业务代码或提交内容里。
