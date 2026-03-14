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
