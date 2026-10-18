# Hubitat Packages

A collection of Hubitat apps and drivers for home automation.

---

## Drivers

### Ntfy Notification Driver
**File:** `drivers/ntfy/ntfy-notification-driver.groovy`

Sends push notifications through the [ntfy](https://ntfy.sh) service (self-hosted or ntfy.sh). Implements the `Notification` and `Speech Synthesis` capabilities so it works as a drop-in notification device for any Hubitat app.

**Features:**
- Supports self-hosted ntfy instances or ntfy.sh
- Bearer token authentication
- Configurable default title, priority, and tags per ntfy spec
- Override priority, title, and tags at runtime via commands
- Optional auto context: picks title, priority, and tags from the message text (see below)
- Auto-disables debug logging after 30 minutes

**Preferences:**
| Setting | Description |
|---|---|
| Ntfy Server | Server hostname (without `https://`) |
| Topic Name | ntfy topic to publish to |
| Authentication Token | Bearer token for your ntfy instance |
| Default Priority | min / low / default / high / max |
| Default Title | Title shown in push notification |
| Default Tags | Comma-separated ntfy emoji tags (e.g. `warning,home`) |
| Set title, priority and tags from message content | Auto context on/off (default: off) |
| Enable debug logging | Debug logs; turns itself off after 30 minutes |

**Commands:** `testMessage`, `clearLastMessage`, `setPriority`, `setTitle`, `setTags`

**Auto context:** when enabled, messages matching these patterns override the defaults. Anything else uses the default title, priority, and tags.

| Message | Title | Priority | Tags |
|---|---|---|---|
| contains `could be malfunctioning` | Freezer Alert | max | `rotating_light` 🚨 |
| starts with `Activity Report` | Device Not Checking In | high | `warning,zzz` ⚠️💤 |
| starts with `Battery Report` | Low Battery | high | `warning,battery` ⚠️🔋 |
| starts with `Nothing to report` | Device Watchdog | low | `white_check_mark` ✅ |

The freezer row matches a Rule Machine freezer alert; the other three match Device Watchdog reports (below).

---

## Apps

### Battery Monitor
**File:** `apps/battery-monitor-app.groovy`

Monitors battery levels across any number of devices and sends an alert when a device drops below a configurable threshold. Works with any notification driver that implements the `Notification` capability (ntfy, Pushover, etc.).

**Features:**
- Subscribes to live battery events for instant alerts
- Daily scheduled scan to catch devices that report infrequently
- Deduplication — only notifies once per low-battery episode, not on every event
- Optional recovery notification when a device climbs back above threshold
- Clears low-battery state automatically on recovery
- "Check All Devices Now" button for an on-demand scan
- Auto-disables debug logging after 30 minutes

**Preferences:**
| Setting | Description |
|---|---|
| Battery Devices | One or more devices with the `battery` capability |
| Low Battery Threshold | Alert when battery falls below this % (default: 20) |
| Notification Device | Any device implementing `capability.notification` |
| Daily Check Time | When to run the scheduled full scan (default: 08:00) |
| Notify on Recovery | Send an alert when battery recovers above threshold |

### Device Watchdog (third-party, modified)
**Files:** `apps/device-watchdog/device-watchdog-parent.groovy`, `apps/device-watchdog/device-watchdog-child.groovy`

Daily reports of devices that haven't checked in, low batteries, and device status. Written by Bryan Turcotte ([@bptworld](https://github.com/bptworld)), version 2.4.8. The author has since removed the source from GitHub, so this is a copy of what's installed on the hub, licensed under Apache 2.0 (see `apps/device-watchdog/LICENSE`).

**Local changes** (child app only, marked `2.4.8a` in its change log):
- Report dates include seconds (the original format was `h:mm: a`)

**Things to know:**
- "Number of hours for Devices to be considered inactive" is in **hours**, not minutes
- With push enabled and nothing wrong, it sends one "Nothing to report." per report type each run. Turn on "Only send Push if there is something to actually report" to stop that
- Needs the `BPTWorld.bpt-normalStuff` library installed on the hub (not included here)

### Orbit Bhyve Controller (third-party, modified)
**File:** `apps/orbit-bhyve/orbit-bhyve-controller.groovy`

Mirrors Orbit B-Hyve hose timers into Hubitat (valve state, battery, watering status) through the Orbit cloud. Written by Kurt Sanders and Dominick Meglio, licensed under Apache 2.0 (see `apps/orbit-bhyve/LICENSE`). The first commit of this file is the unmodified copy from the hub.

**Local changes:**
- `refresh()` no longer throws a `NullPointerException` when the Orbit cloud sends a watering status without a current station. Before, the error stopped the refresh partway, so later timers could miss their update.

---

## Tools

Local Python scripts for exploring and backing up your hub. Requires [uv](https://docs.astral.sh/uv/).

**Setup:**
```bash
cp .env.example .env   # then set HUBITAT_IP and HUBITAT_TOKEN
uv sync
```

### Hub Browser
**File:** `tools/browse.py`

Interactive REPL for querying a live Hubitat hub via its local HTTP API. No browser required — reads directly from the hub's JSON endpoints.

```
uv run tools/browse.py
```

**Commands:**

| Command | Requires token | Description |
|---|---|---|
| `hub` | no | Hub metadata, firmware version, active alerts |
| `devices [filter]` | no | All devices — status and last activity time |
| `apps [filter]` | no | User-installed app types |
| `iapps [filter]` | no | Installed app instances |
| `makerdevices [filter]` | yes | Devices with live attribute values |
| `modes` | yes | Location modes (Day / Evening / Night / Away) |
| `hsm` | yes | Hubitat Safety Monitor armed state |
| `show <id>` | no | Full JSON for any device or app by ID |
| `raw <path>` | no | Fetch any hub path, e.g. `raw /hub2/hubData` |
| `export <section>` | — | Save a section to a local JSON file |
| `help` | no | List commands |

The Maker API token is read from `.env` (`HUBITAT_TOKEN`). Without it, token-required commands are unavailable but everything else works.

The Maker API instance ID is hard-coded as `MAKER_ID = 618` in `browse.py`; change it if your Maker API app has a different ID.

**Not available from these JSON endpoints (as of firmware 2.5.x):** Rule Machine rule definitions. Use the Hub Exporter below, which exports them through the hub's Export/Import/Clone UI.

---

### Hub Exporter
**File:** `tools/export_hubitat.py`

Exports all installed Hubitat apps, integrations, and automations (including Rule Machine rules) to timestamped text files using Playwright to drive the hub's Export/Import/Clone UI. All logic is self-contained in a single file.

```bash
uv run tools/export_hubitat.py              # export only
uv run tools/export_hubitat.py --no-headless  # show browser (useful for debugging)
```

**Output structure** (written to `backup/`, which is local-only and not tracked in git):

```
backup/
  apps/           YYYYMMDD <Name> _base.txt   original export JSON
  integrations/   YYYYMMDD <Name> _base.txt
  automations/    YYYYMMDD <Name> _base.txt
  devices.json    device id → label map
```

Files are skipped if they already exist for today's date, so re-running is safe.

After exporting, open Claude Code in this directory and ask it to review the `backup/` folder. Claude reads the exported files and `devices.json` directly and writes:
- `_review.json` — structured metadata per file (priority, group, summary, changes)
- `_update.txt` — corrected importable JSON where fixes were needed
- `REVIEW_YYYYMMDD.md` — prioritised, grouped summary of all findings
