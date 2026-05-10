#!/usr/bin/env python3
"""
Export all Hubitat apps, integrations, and automations to text files.

Usage:
  uv run tools/export_hubitat.py

Output:
  backup/apps/            YYYYMMDD <Name> _base.txt
  backup/integrations/    YYYYMMDD <Name> _base.txt
  backup/automations/     YYYYMMDD <Name> _base.txt
  backup/devices.json     device id → label map

Files are skipped if they already exist for today's date.

To review exports and get AI recommendations, open Claude Code and ask it to
review the backup/ directory. Claude Code reads the exported files and
backup/devices.json directly and writes:
  _update.txt      corrected JSON ready for reimport
  _review.json     structured metadata (priority, group, changes)
  REVIEW_YYYYMMDD.md  prioritised/grouped summary with links
"""

import argparse
import asyncio
import io
import json
import re
import sys
from datetime import date
from pathlib import Path
from urllib.request import urlopen

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")

from playwright.async_api import async_playwright, Page

HUB = "http://192.168.1.200"
RULE_MACHINE_ID = 95  # Rule Machine parent app

BACKUP_DIR = Path("backup")
TODAY = date.today().strftime("%Y%m%d")

CATEGORY_DIRS = {
    "Apps": BACKUP_DIR / "apps",
    "Integrations": BACKUP_DIR / "integrations",
    "Automations": BACKUP_DIR / "automations",
}

SKIP_IDS = {RULE_MACHINE_ID}


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def fetch_json(path: str) -> object:
    with urlopen(f"{HUB}{path}", timeout=15) as r:
        return json.loads(r.read())


def safe_name(name: str) -> str:
    return re.sub(r'[<>:"/\\|?*]', "_", name).strip()


def base_path(category: str, name: str) -> Path:
    folder = CATEGORY_DIRS.get(category, BACKUP_DIR / "apps")
    folder.mkdir(parents=True, exist_ok=True)
    return folder / f"{TODAY} {safe_name(name)} _base.txt"


# ---------------------------------------------------------------------------
# Playwright helpers
# ---------------------------------------------------------------------------

_MODEL_HAS_DOWNLOAD = """() => {
    if (!window.model || !window.model.configPage) return false;
    const inputs = (window.model.configPage.sections || []).flatMap(s => s.input || []);
    return inputs.some(i => (i.name === 'ruledownload' && i.filecontent) || i.name === 'fileName');
}"""

_MODEL_HAS_CONTENT = """() => {
    if (!window.model || !window.model.configPage) return false;
    const inputs = (window.model.configPage.sections || []).flatMap(s => s.input || []);
    return inputs.some(i => i.name === 'ruledownload' && i.filecontent);
}"""

_MODEL_EXTRACT = """() => {
    const inputs = (window.model.configPage.sections || []).flatMap(s => s.input || []);
    const rd = inputs.find(i => i.name === 'ruledownload');
    const fn = inputs.find(i => i.name === 'fileName');
    if (rd && rd.filecontent) return {type: 'content', data: rd.filecontent};
    if (fn) return {type: 'filename'};
    return null;
}"""


def _find_filecontent(data) -> str | None:
    """Recursively find a non-empty filecontent value anywhere in the response."""
    if isinstance(data, dict):
        fc = data.get("filecontent")
        if fc:
            return str(fc)
        for v in data.values():
            result = _find_filecontent(v)
            if result:
                return result
    elif isinstance(data, list):
        for item in data:
            result = _find_filecontent(item)
            if result:
                return result
    return None


async def export_one(page: Page, app_id: int, app_name: str) -> str | None:
    """
    Export a single app and return the JSON content string, or None on failure.

    Some exports have an intermediate 'fileName' step (e.g. rules with a
    required expression that is currently false). After clicking the export
    button, window.model is checked; if a fileName input appears the default
    name is submitted to proceed to the actual download.
    """
    try:
        await page.goto(f"{HUB}/installedapp/sysAppApi/appCloner/app/{app_id}")
        await page.wait_for_load_state("networkidle")
    except Exception as e:
        print(f"    Navigation failed for id={app_id}: {e}")
        return None

    export_btn = page.locator('button[id="settings[exportRuleButton]"]')
    if not await export_btn.count():
        body = await page.inner_text("body")
        if "may not be" in body or "Cannot" in body:
            print(f"    Not exportable: {app_name}")
        else:
            print(f"    No export button for {app_name} (URL: {page.url})")
        return None

    try:
        await export_btn.click(force=True)
        await page.wait_for_load_state("networkidle")
    except Exception as e:
        print(f"    Export click failed for {app_name}: {e}")
        return None

    # Wait for window.model to reflect either direct content or the fileName step
    try:
        await page.wait_for_function(_MODEL_HAS_DOWNLOAD, timeout=30000)
    except Exception as e:
        print(f"    Timeout waiting for export result: {e}")
        return None

    result = await page.evaluate(_MODEL_EXTRACT)

    # Direct content — done
    if result and result["type"] == "content":
        return result["data"]

    # Intermediate fileName step — intercept the AJAX response directly
    if result and result["type"] == "filename":
        safe = re.sub(r"[^\w\-]", "_", app_name)[:60]
        try:
            async with page.expect_response(
                lambda r: "/installedapp/update/json" in r.url,
                timeout=30000,
            ) as resp_info:
                await page.evaluate(
                    """(name) => { jsonSubmit('settings[fileName]', name, false, false, false); }""",
                    safe,
                )
            response = await resp_info.value
            ct = response.headers.get("content-type", "")
            if "json" in ct or "javascript" in ct:
                data = await response.json()
                fc = _find_filecontent(data)
                if fc:
                    return fc
                # Some rule types (paused, required-expression-false, repeating)
                # return the exported content in configPage.content instead of
                # in a sections-based filecontent field.
                config_content = (data.get("configPage") or {}).get("content", "").strip()
                if config_content:
                    return config_content
                def _summarise(d, depth=0):
                    if depth > 3:
                        return "..."
                    if isinstance(d, dict):
                        return {k: _summarise(v, depth + 1) for k, v in list(d.items())[:8] if k != "filecontent"}
                    if isinstance(d, list):
                        return [_summarise(i, depth + 1) for i in d[:3]]
                    return type(d).__name__
                print(f"    No filecontent in response: {json.dumps(_summarise(data))[:400]}")
            else:
                # Direct file body (non-JSON download)
                body_bytes = await response.body()
                text = body_bytes.decode("utf-8", errors="replace").strip()
                if text:
                    return text
                print(f"    Empty non-JSON response (content-type: {ct})")
        except Exception as e:
            print(f"    Timeout after filename step: {e}")
        return None

    print(f"    Empty content for {app_name}")
    return None


async def export_children(page: Page, cloner_url: str, parent_name: str, category: str) -> list[tuple[Path, str]]:
    """
    Export children of a parent app via its exportRule multi-select dropdown.
    Page must already be at the cloner configure page (cloner_url).
    Returns list of (path, name) for each successfully saved child.

    The Hubitat old UI uses jsonSubmit() which:
    1. Serialises multi-selects as JSON arrays: settings[exportRule]=["620"]
    2. POSTs to /installedapp/update/json and stores the response in window.model
    3. filecontent is in model.configPage.sections[].input[].filecontent
    """
    select_sel = 'select[name="settings[exportRule]"]'

    option_elements = await page.locator(f"{select_sel} option").all()
    options = [(await o.get_attribute("value"), (await o.text_content() or "").strip())
               for o in option_elements]
    options = [(v, l) for v, l in options if v and l]

    if not options:
        print(f"    No child options found for {parent_name}")
        return []

    results = []
    for value, label in options:
        out = base_path(category, label)
        if out.exists():
            print(f"    [skip] {label}")
            results.append((out, label))
            continue

        print(f"    Exporting child: {label} (id={value})...")

        try:
            await page.goto(cloner_url)
            await page.wait_for_load_state("networkidle")

            # Select the option and call jsonSubmit exactly as the Hubitat UI does.
            # Multi-select values must be a JSON-encoded array string e.g. '["620"]'.
            # After the AJAX call, window.model holds the response JSON.
            await page.evaluate("""(v) => {
                const sel = document.querySelector('select[name="settings[exportRule]"]');
                if (!sel) throw new Error('select not found');
                for (const opt of sel.options) opt.selected = (opt.value === v);
                jsonSubmit('settings[exportRule]', JSON.stringify($(sel).val()), false, false, false);
            }""", value)

            # jsonSubmit uses $.post (async); wait for the response to land in window.model
            await page.wait_for_function(
                """() => window.model && window.model.configPage &&
                   window.model.configPage.sections &&
                   window.model.configPage.sections.some(
                       s => s.input && s.input.some(i => i.name === 'ruledownload')
                   )""",
                timeout=30000
            )

            filecontent = await page.evaluate("""() => {
                for (const s of window.model.configPage.sections || []) {
                    for (const inp of s.input || []) {
                        if (inp.name === 'ruledownload' && inp.filecontent) {
                            return inp.filecontent;
                        }
                    }
                }
                return null;
            }""")

            if filecontent:
                out.write_text(filecontent, encoding="utf-8")
                print(f"      -> {out}")
                results.append((out, label))
            else:
                print(f"      Empty filecontent for {label}")

        except Exception as e:
            print(f"      Export failed for {label}: {e}")

    return results


async def get_rule_machine_rules(page: Page) -> list[dict]:
    """Return list of Rule Machine child rules with id and label."""
    # Must navigate to the Rule Machine configure page first so the hub
    # creates a session context for this request.
    await page.goto(f"{HUB}/installedapp/sysAppApi/appCloner/app/{RULE_MACHINE_ID}")
    await page.wait_for_load_state("networkidle")

    r = await page.request.get(f"{HUB}/installedapp/configure/json/{RULE_MACHINE_ID}/main")
    if not r.ok:
        print(f"  Failed to fetch Rule Machine rules: HTTP {r.status}")
        return []
    data = await r.json()
    child_apps = []
    for section in data.get("configPage", {}).get("sections", []):
        for item in section.get("input", []):
            if item.get("type") == "childApps":
                child_apps.extend(item.get("childApps", []))
    if not child_apps:
        child_apps = data.get("childApps", [])
    return child_apps


# ---------------------------------------------------------------------------
# Export pipeline
# ---------------------------------------------------------------------------

async def export_all(page: Page) -> list[tuple[Path, str]]:
    """Export every app and return list of (path, name) for successfully saved files."""
    exported: list[tuple[Path, str]] = []

    # ---- Regular installed apps ----
    apps = fetch_json("/installedapp/list/data")
    regular = [
        a for a in sorted(apps, key=lambda x: x["id"])
        if a["id"] not in SKIP_IDS
        and a.get("appType", {}).get("menu") in CATEGORY_DIRS
        and a.get("name") != "Export/Import/Clone"  # skip sysAppApi-created instances
    ]
    print(f"Found {len(regular)} regular apps/integrations/automations")

    for app in regular:
        aid = app["id"]
        name = (app.get("label") or app.get("name") or "").strip()
        # Strip HTML tags (some names contain <span> elements)
        name = re.sub(r"<[^>]+>", "", name).strip()
        category = app.get("appType", {}).get("menu", "Apps")
        out = base_path(category, name)

        if out.exists():
            print(f"  [skip] {name}")
            exported.append((out, name))
            continue

        print(f"  Exporting {name} (id={aid}, {category})...")
        content = await export_one(page, aid, name)
        if content:
            out.write_text(content, encoding="utf-8")
            print(f"    -> {out}")
            exported.append((out, name))
        elif await page.locator('select[name="settings[exportRule]"]').count():
            children = await export_children(page, page.url, name, category)
            exported.extend(children)
            if not children:
                print(f"    -> FAILED (no children exported)")
        else:
            print(f"    -> FAILED")

    # ---- Rule Machine child rules ----
    print(f"\nFetching Rule Machine child rules...")
    rules = await get_rule_machine_rules(page)
    print(f"Found {len(rules)} rules")

    for rule in rules:
        rid = rule.get("id")
        rname = (rule.get("label") or rule.get("name") or f"Rule {rid}").strip()
        rname = re.sub(r"<[^>]+>", "", rname).strip()
        out = base_path("Automations", rname)

        if out.exists():
            print(f"  [skip] {rname}")
            exported.append((out, rname))
            continue

        print(f"  Exporting rule: {rname} (id={rid})...")
        content = await export_one(page, rid, rname)
        if content:
            out.write_text(content, encoding="utf-8")
            print(f"    -> {out}")
            exported.append((out, rname))
        else:
            print(f"    -> FAILED")

    return exported


# ---------------------------------------------------------------------------
# Device map
# ---------------------------------------------------------------------------

DEVICES_PATH = BACKUP_DIR / "devices.json"


def save_device_map() -> None:
    """Fetch all hub devices and write backup/devices.json (id → label)."""
    try:
        devices = fetch_json("/device/list/all/data")
        dm = {
            str(d["id"]): (d.get("label") or d.get("name") or "").strip()
            for d in devices
        }
        BACKUP_DIR.mkdir(parents=True, exist_ok=True)
        DEVICES_PATH.write_text(json.dumps(dm, indent=2, sort_keys=True), encoding="utf-8")
        print(f"  Device map: {DEVICES_PATH} ({len(dm)} devices)")
    except Exception as e:
        print(f"  Warning: could not save device map: {e}")


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

async def main() -> None:
    parser = argparse.ArgumentParser(description="Export all Hubitat apps to backup/")
    parser.add_argument("--headless", default=True, action=argparse.BooleanOptionalAction)
    args = parser.parse_args()

    async with async_playwright() as p:
        browser = await p.chromium.launch(headless=args.headless)
        page = await browser.new_page()

        print(f"Connecting to {HUB}...")
        await page.goto(HUB)
        await page.wait_for_load_state("networkidle")

        exported = await export_all(page)
        await browser.close()

    print(f"\nExported {len(exported)} items")
    save_device_map()
    print("\nDone! To review exports, open Claude Code and ask it to review the backup/ directory.")
    print("Claude Code reads the exported files and devices.json directly, then writes")
    print("_update.txt (corrected importable JSON) and REVIEW_YYYYMMDD.md per run.")


if __name__ == "__main__":
    asyncio.run(main())
