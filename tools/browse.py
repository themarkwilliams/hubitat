#!/usr/bin/env python3
"""
Browse a live Hubitat hub via its local HTTP API.

Configuration - copy .env.example to .env and fill in your values, or set env vars directly:
  HUBITAT_IP     hub's local IP address
  HUBITAT_TOKEN  Maker API access token (Apps > Maker API > copy from URL examples)

What works (no auth required):
  /hub2/hubData              hub metadata, alerts, firmware version
  /device/list/all/data      every device with status and last-activity time
  /app/list/data             user-installed app types
  /installedapp/list/data    installed app instances

What requires the Maker API token:
  /apps/api/{id}/devices/all full device detail including current attribute values
  /apps/api/{id}/modes       location modes and which is active
  /apps/api/{id}/hsm         Hubitat Safety Monitor armed state

What is NOT accessible via any API (as of firmware 2.5.x):
  Rule Machine rules         stored in H2 database only; no HTTP endpoint exists.
                             Export rules manually from the Rule Machine UI (each
                             rule has an "Export Rule" button that produces JSON).
"""

import json
import os
import sys
from urllib.request import urlopen, Request
from urllib.error import URLError

from dotenv import load_dotenv
load_dotenv()

# -- configuration --------------------------------------------------------------
HUB        = os.environ.get("HUBITAT_IP",    "http://192.168.1.200")
MAKER_ID   = 618          # installed app ID of the Maker API instance
TOKEN      = os.environ.get("HUBITAT_TOKEN", "")
# -------------------------------------------------------------------------------

OPEN_SECTIONS = {
    "hub":     ("/hub2/hubData",           "Hub metadata, alerts, firmware"),
    "devices": ("/device/list/all/data",   "All devices (status, last activity)"),
    "apps":    ("/app/list/data",          "User-installed app types"),
    "iapps":   ("/installedapp/list/data", "Installed app instances"),
}

MAKER_SECTIONS = {
    "makerdevices": (f"/apps/api/{MAKER_ID}/devices/all", "Devices with live attribute values"),
    "modes":        (f"/apps/api/{MAKER_ID}/modes",       "Location modes"),
    "hsm":          (f"/apps/api/{MAKER_ID}/hsm",         "Hubitat Safety Monitor state"),
}


def fetch(path: str, token: bool = False) -> object:
    url = f"{HUB}{path}"
    if token:
        sep = "&" if "?" in url else "?"
        url = f"{url}{sep}access_token={TOKEN}"
    try:
        with urlopen(url, timeout=10) as r:
            raw = r.read().decode()
    except URLError as e:
        print(f"  Error fetching {path}: {e}")
        return None
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return raw


def pp(obj, indent=2):
    if isinstance(obj, (dict, list)):
        print(json.dumps(obj, indent=indent, default=str))
    else:
        print(obj)


def show_devices(devices: list, filt: str = ""):
    filt = filt.lower()
    shown = [d for d in devices if not filt or filt in json.dumps(d).lower()]
    if not shown:
        print("No devices match.")
        return
    print(f"\n{'ID':>5}  {'Label':<35}  {'Type':<30}  {'Status':<10}  {'Last Activity'}")
    print("-" * 100)
    for d in shown:
        label  = (d.get("label") or d.get("name") or "")[:34]
        dtype  = (d.get("deviceTypeName") or "")[:29]
        status = (d.get("status") or "")[:9]
        last   = (d.get("lastActivityTime") or "never")[:19]
        print(f"{d['id']:>5}  {label:<35}  {dtype:<30}  {status:<10}  {last}")
    print(f"\n{len(shown)} device(s)")


def show_maker_devices(devices: list, filt: str = ""):
    filt = filt.lower()
    shown = [d for d in devices if not filt or filt in json.dumps(d).lower()]
    if not shown:
        print("No devices match.")
        return
    print(f"\n{'ID':>5}  {'Label':<35}  {'Type':<35}  {'Attributes'}")
    print("-" * 110)
    for d in shown:
        label  = (d.get("label") or d.get("name") or "")[:34]
        dtype  = (d.get("type") or "")[:34]
        attrs  = ", ".join(
            f"{a['name']}={a.get('currentValue', '?')}"
            for a in (d.get("attributes") or [])
            if a.get("currentValue") is not None
        )[:60]
        print(f"{d['id']:>5}  {label:<35}  {dtype:<35}  {attrs}")
    print(f"\n{len(shown)} device(s)")


def show_apps(apps: list, filt: str = ""):
    filt = filt.lower()
    shown = [a for a in apps if not filt or filt in json.dumps(a).lower()]
    if not shown:
        print("No apps match.")
        return
    print(f"\n{'ID':>5}  {'Name':<40}  {'Namespace':<25}  {'Category'}")
    print("-" * 95)
    for a in shown:
        name = (a.get("name") or a.get("label") or "")[:39]
        ns   = (a.get("namespace") or "")[:24]
        cat  = (a.get("category") or "")[:20]
        print(f"{a.get('id', ''):>5}  {name:<40}  {ns:<25}  {cat}")
    print(f"\n{len(shown)} app(s)")


def show_iapps(apps: list, filt: str = ""):
    filt = filt.lower()
    shown = [a for a in apps if not filt or filt in json.dumps(a).lower()]
    if not shown:
        print("No installed apps match.")
        return
    print(f"\n{'ID':>5}  {'Label':<45}  {'Disabled'}")
    print("-" * 60)
    for a in shown:
        label    = (a.get("label") or a.get("name") or "")[:44]
        disabled = "DISABLED" if a.get("disabled") else ""
        print(f"{a.get('id', ''):>5}  {label:<45}  {disabled}")
    print(f"\n{len(shown)} installed app(s)")


HELP = """
Commands (no token needed):
  hub                 Hub metadata, firmware version, alerts
  devices [filter]    All devices - status and last activity
  apps [filter]       User-installed app types
  iapps [filter]      Installed app instances

Commands (Maker API token):
  makerdevices [f]    Devices with live attribute values
  modes               Location modes (Day/Evening/Night/Away)
  hsm                 Hubitat Safety Monitor state

Universal:
  show <id>           Full JSON for any device or app by ID
  raw <path>          Fetch any hub path, e.g. raw /hub2/hubData

  export <section>    Save a section to JSON (hub/devices/apps/iapps/makerdevices)
  help                This help text
  q / quit            Exit

Not available via API (as of firmware 2.5.x):
  Rule Machine rules - export each rule manually from the Rule Machine UI
  (open a rule > Export Rule button > JSON file)
"""


def main():
    print(f"Hubitat browser  |  hub={HUB}  |  maker_app={MAKER_ID}")
    if not TOKEN:
        print("  Note: HUBITAT_TOKEN not set - Maker API commands unavailable")
    print(HELP)

    cache: dict[str, object] = {}

    def get(section: str):
        if section not in cache:
            use_token = section in MAKER_SECTIONS
            path = (MAKER_SECTIONS if use_token else OPEN_SECTIONS)[section][0]
            print(f"  Fetching {path}...")
            cache[section] = fetch(path, token=use_token)
        return cache[section]

    while True:
        try:
            line = input("\n> ").strip()
        except (EOFError, KeyboardInterrupt):
            print()
            break
        if not line:
            continue
        parts = line.split(None, 1)
        cmd   = parts[0].lower()
        arg   = parts[1] if len(parts) > 1 else ""

        if cmd in ("q", "quit", "exit"):
            break

        elif cmd == "hub":
            data = get("hub")
            # Surface the most useful fields directly
            if isinstance(data, dict):
                print(f"\n  Hub:      {data.get('baseModel', {}).get('locationName')}  ({data.get('model')})")
                print(f"  Firmware: {data.get('version')}")
                print(f"  IP:       {data.get('ipAddress')}")
                print(f"  DB size:  {data.get('alerts', {}).get('databaseSize')} MB")
                active_alerts = [k for k, v in data.get("alerts", {}).items()
                                 if v is True and k != "runAlerts"]
                print(f"  Alerts:   {', '.join(active_alerts) if active_alerts else 'none'}")
                print()
            pp(data)

        elif cmd == "devices":
            show_devices(get("devices"), arg)

        elif cmd == "makerdevices":
            data = get("makerdevices")
            if isinstance(data, list):
                show_maker_devices(data, arg)
            else:
                pp(data)

        elif cmd == "modes":
            data = get("modes")
            if isinstance(data, list):
                print()
                for m in data:
                    active = " <-- active" if m.get("active") else ""
                    print(f"  [{m['id']}] {m['name']}{active}")
            else:
                pp(data)

        elif cmd == "hsm":
            data = get("hsm")
            pp(data)

        elif cmd == "apps":
            show_apps(get("apps"), arg)

        elif cmd == "iapps":
            show_iapps(get("iapps"), arg)

        elif cmd == "show":
            if not arg:
                print("Usage: show <id>")
                continue
            try:
                target_id = int(arg)
            except ValueError:
                print("id must be a number")
                continue
            found = False
            for section in ("devices", "apps", "iapps"):
                data = get(section)
                if isinstance(data, list):
                    for item in data:
                        if item.get("id") == target_id:
                            print(f"\n[{section}]")
                            pp(item)
                            found = True
            if not found:
                print(f"No item with id={target_id} found.")

        elif cmd == "raw":
            if not arg:
                print("Usage: raw /some/path")
                continue
            use_token = "?" not in arg  # let user add ?access_token= manually if needed
            pp(fetch(arg))

        elif cmd == "export":
            section = arg.strip().lower()
            all_sections = {**OPEN_SECTIONS, **MAKER_SECTIONS}
            if section not in all_sections:
                print(f"Unknown section. Choose: {', '.join(all_sections)}")
                continue
            data = get(section)
            fname = f"hubitat_{section}.json"
            with open(fname, "w") as f:
                json.dump(data, f, indent=2, default=str)
            print(f"Saved {fname}")

        elif cmd == "help":
            print(HELP)

        else:
            print(f"Unknown command '{cmd}'. Type 'help' for commands.")


if __name__ == "__main__":
    main()
