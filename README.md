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
- Auto-disables debug logging after 30 minutes

**Preferences:**
| Setting | Description |
|---|---|
| Battery Devices | One or more devices with the `battery` capability |
| Low Battery Threshold | Alert when battery falls below this % (default: 20) |
| Notification Device | Any device implementing `capability.notification` |
| Daily Check Time | When to run the scheduled full scan (default: 08:00) |
| Notify on Recovery | Send an alert when battery recovers above threshold |

---

## Tools

Local Python scripts for exploring and backing up your hub. Requires [uv](https://docs.astral.sh/uv/).

**Setup:**
```bash
cp .env.example .env   # then fill in your HUBITAT_TOKEN
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

The Maker API token is read from `.env` (`HUBITAT_TOKEN`). Without it, token-required commands are unavailable but everything else works.

**What is not accessible via API (as of firmware 2.5.x):** Rule Machine rules are stored in the hub's H2 database only. Export rules manually from the Rule Machine UI (open a rule → Export Rule button).

---

### Hub Exporter
**File:** `tools/export_hubitat.py`

Exports all installed Hubitat apps, integrations, and automations to timestamped text files using Playwright to drive the hub's Export/Import/Clone UI. All logic is self-contained in a single file.

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
