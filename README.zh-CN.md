<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="./docs/assets/taktu-logo-dark.svg">
    <img src="./docs/assets/taktu-logo.svg" alt="Taktu logo" width="480">
  </picture>
</p>
<p align="center">
  <a href="https://github.com/norbury1900/taktu/stargazers"><img src="https://img.shields.io/github/stars/norbury1900/taktu?logo=github&label=stars" alt="GitHub stars"></a>
  <a href="https://github.com/norbury1900/taktu/releases"><img src="https://img.shields.io/github/v/release/norbury1900/taktu" alt="release"></a>
  <a href="./LICENSE"><img src="https://img.shields.io/github/license/norbury1900/taktu" alt="license"></a>
  <a href="./README.md"><img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white" alt="Android 8.0+"></a>
</p>
<p align="center">
  <a href="./README.md">English</a> |
  <a href="./README.zh-CN.md">简体中文</a>
</p>

> 一个"到点不响，除非你真的没做"的安卓任务提醒 App。

普通闹钟到点就响，响完与你做没做无关。Taktu 的逻辑相反：**到点先去查你做了没有，做了就一声不响；没做才响**。之后复查时间再查一次，还没做就再响。它是"执行型"的提醒，不是"记录型"的打卡——目标是把事情真的做完，而不是留下一条完成记录。

例子：设定「单杠抬腿 60 次」，截止 20:00，催促提前 30 分钟，复查延后 2 小时。App 在 19:30 查一次，做了就静默；没做才响铃。22:00 复查，仍未做就再响一次。

## AI 集成（与其他提醒 App 的根本区别）

Taktu 提供一套专为 AI agent 设计的局域网 HTTP 接口——**Claude Code、WorkBuddy，任何能发 HTTP 的工具都行**。对话里说一句，agent 直接把任务排进手机：这是它和其他所有提醒类 App 不一样的地方。

1. 在 App 设置页打开「外部控制」（默认关闭；页面显示局域网地址和 `X-Token`）
2. 让 agent 对接接口——可以读任务、推任务、改、删：

```bash
# agent 读取当前全部任务
curl -s -H "X-Token: $TOKEN" http://<手机IP>:8931/tasks

# 把对话里聊好的计划推进手机（解析带容错）
curl -s -X POST -H "X-Token: $TOKEN" -H "Content-Type: application/json" \
     --data-binary @plan.json http://<手机IP>:8931/import
```

3. 推送的内容**不直接落库**——必须由你在手机预览页亲手确认：agent 提议，你拍板。

典型场景：聊完训练计划说一句「把这些排进去」，解锁手机时任务已经在响铃队列里了；每周复盘时 agent 读完过去一周的记录，自动重排下一周。接口全表见 `util/ControlServer.kt`。

## 运行要求

| 项 | 要求 |
| --- | --- |
| 系统 | **Android 8.0 及以上**（minSdk 26） |
| 实测机型 | **小米 15（HyperOS 2 / Android 15）**——目前唯一实测机型 |
| 其他机型 | 理论可用，但各家 ROM 后台策略差异大，未验证 |
| 存储 | APK 约 10 MB；所有数据留在本机 |

## 核心特性

- **催促 + 复查双阶段提醒**：催促只是提醒去做，复查才是核对结果。两条都静默跳过，任务才算完成
- **多时刻任务**：一天多个固定时刻（如喝水 11:25 / 17:25），逐时刻提醒、逐时刻确认，全部确认才算当天完成；也支持间隔循环（每 90 分钟一次）
- **逐时刻提醒配置**：每个时刻可以单独设开始/截止偏移
- **清单与组数**：一个时间点要办一串事，用任务清单（逐项勾）；运动类按组打卡（悬挂抬腿 60 次 / 每组 10 次 = 6 格）
- **法定节假日与调休**：「工作日」按国务院公布的法定日历判定，放假的周一至周五不提醒，调休补班的周末照常提醒；休息日可另设截止时间
- **按星期 / 按日期 / 农历重复**：房租账期类任务用「每 N 个月」，循环任务不设结束日就永不停止
- **免打扰窗口**：指定日期的指定时段静默
- **统计页**：当前连续、最长连续、完成率、30 天趋势条
- **桌面小组件**：迷你时间轴，今天任务一目了然，可直接打卡
- **小米联动**：任务可写入小米系统闹钟与系统日历，即使 App 被系统清理，系统闹钟仍兜底响铃
- **批量导入 / 导出**：JSON 格式带容错解析（字段别名、中英文写法都认），导出可与导入完全互还原
- **外部控制（默认关闭）**：局域网 HTTP 接口，PC 端 AI 可以读任务、推任务，所有写操作需在手机上人工确认
- **过期数据自动清理**：过期任务及其记录保留 30 天后每周自动清理
- **纯本地**：数据只存手机本地 SQLite，不联网、不上传、无统计 SDK、无广告

## 安装

从 [Releases](../../releases) 下载 `TaskAlarm-v<版本号>-debug.apk` 安装（Android 8.0+）。

安装后**必须先完成权限设置**，否则闹钟可能不准或根本不响。App 内「环境自检」页有全部项目的红绿灯，点红灯即可跳转开启：

| 权限 | 为什么必须开 |
| --- | --- |
| 精确闹钟权限 | Android 12+ 默认关闭，不开提醒可能延迟或不响 |
| 通知权限 | 不开响铃时看不到提示 |
| 响铃通知渠道 | 保持「紧急」，保证静音模式下走闹钟音量也能响 |
| 锁屏全屏提醒 | 锁屏时直接弹出响铃页（Android 14+ 需单独授予） |
| 日历读写 | 仅使用小米日历联动时需要 |
| 后台运行 / 电池优化 | 不加白名单，锁屏后进程可能被系统杀掉，闹钟不响 |

国产 ROM（小米/华为/OPPO/vivo 等）还需额外放行：允许自启动、最近任务加锁、关闭省电限制。App 的环境自检页会显示当前机型与各项状态。

## 使用要点

- 新建任务：名称、目标次数（可空）、截止时间、催促提前量、复查延后量、重复规则、清单/组数
- 响铃页列出**全部还没做的任务**（不只同一时刻到点的），逐条确认或一键完成；「稍后提醒」把整个清单延后 5 分钟
- 首页三个页签：今天 / 计划（月历视图，翻月即知本月哪几天有事）/ 历史
- 任务按标签分组，组头可折叠；列表先按优先级再按时间排序
- 完成的任务自动沉到历史页，保留约 30 天；记录长期留库供统计

## 导入 / 导出 JSON

导入页支持粘贴文本、选文件、系统分享三种入口，解析前先预览、逐条可勾选。最小示例：

```json
{
  "version": 4,
  "tasks": [
    {
      "name": "单杠抬腿",
      "targetCount": 60,
      "repsPerSet": 10,
      "deadline": "20:00",
      "urgeBefore": 30,
      "recheckAfter": 120,
      "repeat": "daily",
      "tag": "运动"
    }
  ]
}
```

解析器带容错：字段别名、中英文写法、多种时间格式、对象式重复规则都识别。完整字段说明见 App 内「查看 JSON 格式说明」。

## 外部控制（局域网 HTTP）

默认关闭。在设置页打开后，PC 端（例如 Claude Code 等 AI 工具）可通过局域网接口读取任务、推送任务：

- 仅监听局域网，公网不可达；除探活外所有接口需 `X-Token` 鉴权（设置页可见）
- 推送的任务**不会直接落库**——必须由你在手机预览页亲手确认
- 公共 WiFi 等不可信网络下请关闭此功能（明文 HTTP 理论上可被同网段嗅探）

## 隐私与数据

所有数据只存手机本地：不联网、不上传、无统计 SDK、无广告。代码开源，可自行审计。唯一对外的口是默认关闭的「外部控制」，见上文。

## 重要说明

- Taktu 是**提醒辅助工具，不是保险**。用药、缴费、考试等不容错过的重大事项，请保留系统自带闹钟或其他可靠手段作兜底。响铃是否可靠取决于你的设备、系统版本与 ROM 的后台策略。
- 请先完成 App 内的权限自检再依赖它，尤其是 ROM 相关步骤（自启动、电池白名单）。
- 外部控制默认关闭。开启后请自行保管 token，并只在自己可控的网络中使用。

## 构建

需要 JDK 17 + Android SDK（platform 35）：

```bash
cd TaskAlarm
./gradlew.bat assembleDebug testDebugUnitTest
```

产物在 `app/build/outputs/apk/debug/`。

## 工程结构（关键文件）

```
app/src/main/java/com/norbury/taskalarm/
├── MainActivity.kt            首页任务列表
├── TaskEditActivity.kt        新建 / 编辑任务
├── ImportActivity.kt          批量导入 / 导出
├── AlarmRingActivity.kt       响铃全屏页
├── RingTaskActivity.kt        响铃页逐任务打卡
├── StatsActivity.kt           统计页
├── SettingsActivity.kt        环境自检
├── data/                      Room 数据库、任务实体、状态/列表/保留期等纯函数
├── alarm/
│   ├── AlarmScheduler.kt      闹钟排定（AlarmManager.setAlarmClock 穿透 Doze）
│   ├── AlarmReceiver.kt       到点查库：完成了就静默，没完成才拉响铃
│   ├── RingService.kt         响铃前台服务（USAGE_ALARM 音量通道）
│   └── BootReceiver.kt        开机 / 应用更新后重排闹钟
└── util/
    ├── TaskImporter.kt        容错 JSON 解析器（附单测）
    ├── ControlServer.kt       局域网 HTTP 外部控制（默认关闭）
    ├── MiLinkBridge.kt        小米闹钟 + 日历联动
    └── HolidayCalendar.kt     法定节假日 / 调休数据
```

## 支持作者

如果 Taktu 帮你省掉过几次漏掉的事，欢迎请作者喝杯奶茶：[爱发电](https://afdian.com/a/norbury)。Taktu 会一直免费下去，赞助只用于支持开发。

## License

代码以 [MIT](LICENSE) 协议开源；内置的朱雀仿宋字体遵循 SIL OFL 1.1（[TrionesType/zhuque](https://github.com/TrionesType/zhuque)）。
