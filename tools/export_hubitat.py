#!/usr/bin/env python3
"""
Export all Hubitat apps, integrations, and automations to text files,
then optionally AI-review them with Claude.

Usage:
  uv run tools/export_hubitat.py            # export only
  uv run tools/export_hubitat.py --review   # export + AI review

Output structure:
  backup/apps/            YYYYMMDD <Name> _base.txt
  backup/integrations/    YYYYMMDD <Name> _base.txt
  backup/automations/     YYYYMMDD <Name> _base.txt

File naming:
  _base.txt    initial export
  _update.txt  corrected JSON ready for reimport (written by --review)
  _review.json structured metadata per item (priority, group, changes)
  REVIEW_YYYYMMDD.md  prioritised summary of all recommendations
"""

import argparse
import asyncio
import io
import json
import os
import re
import sys
from datetime import date
from pathlib import Path
from urllib.request import urlopen

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")

from playwright.async_api import async_playwright, Page
import anthropic

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


def update_path(p: Path) -> Path:
    return p.parent / p.name.replace("_base.txt", "_update.txt")


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


def fetch_device_map() -> dict[str, str]:
    """Return {str(device_id): label} for every device on the hub."""
    try:
        devices = fetch_json("/device/list/all/data")
        return {
            str(d["id"]): (d.get("label") or d.get("name") or "").strip()
            for d in devices
        }
    except Exception as e:
        print(f"  Warning: could not fetch device map: {e}")
        return {}


def load_device_map() -> dict[str, str]:
    """Load from disk if present, otherwise fetch and save."""
    if DEVICES_PATH.exists():
        return json.loads(DEVICES_PATH.read_text(encoding="utf-8"))
    dm = fetch_device_map()
    BACKUP_DIR.mkdir(parents=True, exist_ok=True)
    DEVICES_PATH.write_text(json.dumps(dm, indent=2, sort_keys=True), encoding="utf-8")
    print(f"  Device map saved: {DEVICES_PATH} ({len(dm)} devices)")
    return dm


def _device_context(content: str, device_map: dict[str, str]) -> str:
    """Return only device map entries whose IDs appear in the content string."""
    relevant = {k: v for k, v in device_map.items() if k in content}
    return json.dumps(relevant, ensure_ascii=False) if relevant else "(none referenced)"


# ---------------------------------------------------------------------------
# AI review
# ---------------------------------------------------------------------------

def _meta_path(base: Path) -> Path:
    return base.parent / base.name.replace("_base.txt", "_review.json")


def review_with_claude(export_path: Path, name: str, device_map: dict[str, str]) -> dict:
    """
    Review one exported file with Claude.

    Writes:
      _update.txt   — corrected JSON, clean and directly importable
      _review.json  — structured metadata (priority, group, changes)

    Returns the metadata dict.
    """
    upd = update_path(export_path)
    meta_p = _meta_path(export_path)
    if upd.exists() and meta_p.exists():
        print(f"    [skip] review exists")
        return json.loads(meta_p.read_text(encoding="utf-8"))

    content = export_path.read_text(encoding="utf-8")
    dm_context = _device_context(content, device_map)

    prompt = (
        "You are a Hubitat smart home automation expert. Review this exported Hubitat "
        "app/automation configuration and, where improvements are needed, apply them "
        "directly to produce a corrected version ready for reimport.\n\n"
        f"Device map (id → label, for context only — never change any ID values):\n{dm_context}\n\n"
        f"App/rule name: {name}\n\n"
        f"Exported configuration:\n{content}\n\n"
        "Rules:\n"
        "- Apply fixes directly to the JSON; do not merely describe them\n"
        "- Preserve all device IDs, app IDs, and hub-specific values exactly as-is\n"
        "- Be conservative: only change what is clearly wrong or clearly improvable\n"
        "- If no changes are needed, return the original JSON unchanged\n"
        '- Set priority "none" and group "none" when returning unchanged\n\n'
        "Respond in exactly this format with no other text:\n\n"
        "CORRECTED_JSON\n"
        "<complete corrected JSON here>\n"
        "END_CORRECTED_JSON\n"
        "CHANGES\n"
        '{"priority": "high|medium|low|none", "group": "bug|reliability|simplification|optimisation|none", '
        '"summary": "<one sentence>", "changes": ["<change 1>", "<change 2>"]}\n'
        "END_CHANGES"
    )

    client = anthropic.Anthropic()
    msg = client.messages.create(
        model="claude-opus-4-7",
        max_tokens=8192,
        messages=[{"role": "user", "content": prompt}],
    )
    text = msg.content[0].text

    json_match = re.search(r"CORRECTED_JSON\n(.*?)\nEND_CORRECTED_JSON", text, re.DOTALL)
    corrected = json_match.group(1).strip() if json_match else content

    changes_match = re.search(r"CHANGES\n(.*?)\nEND_CHANGES", text, re.DOTALL)
    try:
        meta = json.loads(changes_match.group(1).strip()) if changes_match else {}
    except json.JSONDecodeError:
        meta = {"priority": "none", "group": "none", "summary": "metadata parse error", "changes": []}

    upd.write_text(corrected, encoding="utf-8")
    meta_p.write_text(json.dumps(meta, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"    -> {upd} [{meta.get('priority', '?')}]")
    return meta


# ---------------------------------------------------------------------------
# Review summary
# ---------------------------------------------------------------------------

_PRIORITY_ORDER = ["high", "medium", "low", "none"]
_PRIORITY_LABELS = {
    "high": "High Priority",
    "medium": "Medium Priority",
    "low": "Low Priority",
}
_GROUP_ORDER = ["bug", "reliability", "simplification", "optimisation", "none"]
_GROUP_LABELS = {
    "bug": "Bugs",
    "reliability": "Reliability",
    "simplification": "Simplification",
    "optimisation": "Optimisation",
    "none": "Other",
}


def build_review_summary(results: list[tuple[Path, str, dict]]) -> Path:
    summary_path = BACKUP_DIR / f"REVIEW_{TODAY}.md"

    buckets: dict[str, dict[str, list]] = {p: {} for p in _PRIORITY_ORDER}
    for path, name, meta in results:
        priority = meta.get("priority", "none")
        if priority not in buckets:
            priority = "none"
        group = meta.get("group", "none")
        if group not in _GROUP_ORDER:
            group = "none"
        buckets[priority].setdefault(group, []).append((path, name, meta))

    lines = [f"# Hubitat Automation Review — {date.today().strftime('%Y-%m-%d')}\n"]

    for priority in _PRIORITY_ORDER:
        groups = buckets[priority]
        if not groups:
            continue

        if priority == "none":
            no_change_names = [name for items in groups.values() for _, name, _ in items]
            if no_change_names:
                lines.append("\n## No Changes Recommended\n")
                lines.append(", ".join(no_change_names) + "\n")
        else:
            lines.append(f"\n## {_PRIORITY_LABELS[priority]}\n")
            for group in _GROUP_ORDER:
                items = groups.get(group, [])
                if not items:
                    continue
                lines.append(f"\n### {_GROUP_LABELS.get(group, group.title())}\n")
                for path, name, meta in items:
                    upd = update_path(path)
                    rel = str(upd.relative_to(BACKUP_DIR)).replace("\\", "/")
                    lines.append(f"- [{name}](<{rel}>) — {meta.get('summary', '')}")
                    for change in meta.get("changes", []):
                        lines.append(f"  - {change}")
                lines.append("")

    summary_path.write_text("\n".join(lines), encoding="utf-8")
    return summary_path


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

async def main() -> None:
    parser = argparse.ArgumentParser(description="Export Hubitat apps and optionally review with Claude")
    parser.add_argument("--review", action="store_true", help="AI-review exported files with Claude")
    parser.add_argument("--headless", default=True, action=argparse.BooleanOptionalAction)
    args = parser.parse_args()

    if args.review and not os.environ.get("ANTHROPIC_API_KEY"):
        print("Error: ANTHROPIC_API_KEY environment variable is not set.")
        print("Set it with:")
        print('  $env:ANTHROPIC_API_KEY = "sk-ant-..."          # PowerShell (current session)')
        print('  [System.Environment]::SetEnvironmentVariable("ANTHROPIC_API_KEY", "sk-ant-...", "User")  # permanent')
        sys.exit(1)

    async with async_playwright() as p:
        browser = await p.chromium.launch(headless=args.headless)
        page = await browser.new_page()

        print(f"Connecting to {HUB}...")
        await page.goto(HUB)
        await page.wait_for_load_state("networkidle")

        exported = await export_all(page)
        await browser.close()

    print(f"\nExported {len(exported)} items")

    if args.review and exported:
        print("\nFetching device map...")
        device_map = load_device_map()
        print(f"  {len(device_map)} devices")

        print("\nReviewing with Claude...")
        review_results: list[tuple[Path, str, dict]] = []
        for path, name in exported:
            print(f"  Reviewing {name}...")
            try:
                meta = review_with_claude(path, name, device_map)
                review_results.append((path, name, meta))
            except Exception as e:
                print(f"    Review failed: {e}")

        summary_path = build_review_summary(review_results)
        print(f"\nReview summary: {summary_path}")

    print("\nDone!")


if __name__ == "__main__":
    asyncio.run(main())
