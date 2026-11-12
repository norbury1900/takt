# Takt

**English | [简体中文](README.zh-CN.md)**

> An Android task reminder that stays silent — unless you really haven't done it.

A regular alarm fires at the set time, no matter what. Takt works the opposite way: **at the reminder time it first checks whether you've actually done the task. Done — it stays silent. Not done — it rings.** Then it re-checks at the follow-up time. It is an *execution* reminder, not a *logging* habit tracker — the goal is getting things actually done.

Example: set "Hanging leg raises × 60", deadline 20:00, prompt 30 minutes early, re-check 2 hours later. At 19:30 Takt checks once — done, silence; not done, it rings. At 22:00 it re-checks, and rings again if you still haven't.

## Works with AI agents

Takt exposes a LAN HTTP interface designed for AI coding agents — **Claude Code, WorkBuddy, or anything that can call HTTP**. This is what sets it apart from every other reminder app: your agent turns the conversation into real, ringing tasks.

1. Enable "External control" in the app's Settings page (off by default; the page shows the LAN address and the `X-Token`)
2. Point your agent at the interface — it can read tasks, push new ones, edit, delete:

```bash
# Let the agent read the current plan
curl -s -H "X-Token: $TOKEN" http://<phone-ip>:8931/tasks

# Push a plan extracted from the conversation (any format tolerance)
curl -s -X POST -H "X-Token: $TOKEN" -H "Content-Type: application/json" \
     --data-binary @plan.json http://<phone-ip>:8931/import
```

3. Nothing lands in the database until **you confirm it on the phone** — the agent proposes, you decide.

Typical flows: "把这些加进我的训练计划" mid-conversation and the plan is on your phone before you unlock it; weekly review where the agent reads the past week's records and rebalances the schedule. Full endpoint reference in `util/ControlServer.kt`.

## Requirements

| Item | Requirement |
| --- | --- |
| OS | **Android 8.0 or later** (minSdk 26) |
| Tested device | **Xiaomi 15 (HyperOS 2 / Android 15)** — the only device tested so far |
| Other devices | Should work, but ROM-level background policies vary widely; not verified |
| Storage | APK ~10 MB; all data stays on device |

## Features

- **Prompt + re-check two-stage reminders**: the prompt only tells you to start; the re-check verifies the result. Both stages stay silent once the task is done
- **Multi-time tasks**: several fixed times per day (e.g. drink water at 11:25 / 17:25), each confirmed separately; the day counts as complete only when all times are confirmed. Also supports interval loops (every 90 minutes)
- **Per-time reminder offsets**: each time can have its own start/deadline window
- **Checklists and sets**: a list of sub-items for one time slot; workout tasks tracked in sets (60 reps / 10 per set = 6 slots)
- **Statutory holidays and makeup workdays**: "weekday" tasks follow the official Chinese public holiday calendar — holidays are silent, makeup workdays still remind; separate deadlines for rest days
- **Weekly / date-based / lunar repeats**: bill-type tasks repeat every N months; recurring tasks without an end date never expire
- **Quiet windows**: silence specified time ranges on specified dates
- **Statistics**: current streak, longest streak, completion rate, 30-day trend bars
- **Home-screen widget**: mini timeline of today's tasks with one-tap check-in
- **Xiaomi integration**: tasks can be written to the system clock and calendar, so a system alarm still fires even if the app is killed
- **Batch import / export**: fault-tolerant JSON parsing (field aliases, Chinese/English forms); export round-trips with import
- **External control (off by default)**: LAN HTTP interface for PC-side AI tools; every write requires manual confirmation on the phone
- **Auto cleanup**: expired tasks and records are kept for 30 days, then purged weekly
- **Fully local**: data lives only in on-device SQLite — no network, no analytics SDK, no ads

## Install

Download `TaskAlarm-v<version>-debug.apk` from [Releases](../../releases) (Android 8.0+).

After installing, **you must finish the permission setup first**, or alarms may be late or silent. The in-app "Environment Check" page shows every item with a red/green light — tap a red light to jump to the system setting:

| Permission | Why it's required |
| --- | --- |
| Exact alarms | Off by default on Android 12+; without it reminders may be delayed or silent |
| Notifications | Without it you see nothing when ringing |
| Ring notification channel | Keep it at "Urgent" so ringing uses the alarm volume even in silent mode |
| Full-screen on lock | Shows the ring screen over the lock screen (separate grant on Android 14+) |
| Calendar read/write | Only needed for the Xiaomi calendar integration |
| Background / battery whitelist | Without it the system may kill the process and alarms won't fire |

On Chinese ROMs (Xiaomi / Huawei / OPPO / vivo etc.) also enable autostart, lock the app in Recents, and remove battery restrictions. The Environment Check page shows your device model and per-item status.

## Usage notes

- New task: name, target count (optional), deadline, prompt lead, re-check delay, repeat rule, checklist/sets
- The ring screen lists **all unfinished tasks** (not only those due at that moment); confirm one by one or finish at once; "Remind later" postpones the whole list by 5 minutes
- Home tabs: Today / Plan (month calendar — flip months to see which days have something) / History
- Tasks group by tag, headers collapsible; lists sort by priority then time
- Completed tasks sink to History (~30 days); records stay in the database for statistics

## Import / Export JSON

The import page accepts pasted text, a file, or the system share sheet; parsing shows a preview with per-item checkboxes before anything is written. Minimal example:

```json
{
  "version": 4,
  "tasks": [
    {
      "name": "Hanging leg raises",
      "targetCount": 60,
      "repsPerSet": 10,
      "deadline": "20:00",
      "urgeBefore": 30,
      "recheckAfter": 120,
      "repeat": "daily",
      "tag": "Workout"
    }
  ]
}
```

The parser is tolerant: field aliases, Chinese/English names, multiple time formats, object-form repeat rules. See the in-app "JSON format help" for the full field reference.

## External control (LAN HTTP)

Off by default. Once enabled in Settings, a PC-side tool (e.g. Claude Code or any AI agent) can read and push tasks over LAN:

- LAN only, never exposed to the internet; every endpoint except a health probe requires an `X-Token` header (visible in Settings)
- Pushed tasks **never write directly** — you confirm each import on the phone preview
- On untrusted networks (public Wi-Fi) turn this off; HTTP is plaintext and sniffable on the same segment

## Privacy and data

Everything stays on the device: no network access, no upload, no analytics SDK, no ads. The code is open — audit it yourself. The only outward-facing surface is the opt-in external control described above.

## Important notes

- Takt is a **reminder aid, not a safety net**. For things you cannot afford to miss (medication, payments, exams), keep a system alarm or another reliable means as backup. How reliably ringing works depends on your device, OS version, and ROM background policies.
- Finish the in-app permission checklist before relying on it, especially the ROM-specific steps (autostart, battery whitelist).
- External control is off by default. If you enable it, keep the token private and use it only on networks you control.

## Build

JDK 17 + Android SDK (platform 35):

```bash
cd TaskAlarm
./gradlew.bat assembleDebug testDebugUnitTest
```

Output lands in `app/build/outputs/apk/debug/`.

## Project layout (key files)

```
app/src/main/java/com/norbury/taskalarm/
├── MainActivity.kt            Home task list
├── TaskEditActivity.kt        Task editor
├── ImportActivity.kt          Batch import / export
├── AlarmRingActivity.kt       Full-screen ring activity
├── RingTaskActivity.kt        Per-task check-in from the ring screen
├── StatsActivity.kt           Statistics
├── SettingsActivity.kt        Environment check
├── data/                      Room DB, task entity, pure functions (status, lists, retention)
├── alarm/
│   ├── AlarmScheduler.kt      Schedules alarms (setAlarmClock, bypasses Doze)
│   ├── AlarmReceiver.kt       Fires: checks the DB, rings only if undone
│   ├── RingService.kt         Foreground ring service (USAGE_ALARM channel)
│   └── BootReceiver.kt        Re-schedules after boot / app update
└── util/
    ├── TaskImporter.kt        Fault-tolerant JSON parser (unit-tested)
    ├── ControlServer.kt       LAN HTTP external control (off by default)
    ├── MiLinkBridge.kt        Xiaomi clock + calendar integration
    └── HolidayCalendar.kt     Statutory holidays / makeup workdays
```

## Support

If Takt saves you some missed reminders, a small donation is appreciated: [Afdian / 爱发电](https://afdian.com/a/norbury). Takt is and stays fully free — donations only fund development.

## License

Code licensed under the [MIT License](LICENSE). The bundled Zhuque Fangsong font is licensed under the SIL Open Font License 1.1 ([TrionesType/zhuque](https://github.com/TrionesType/zhuque)).
