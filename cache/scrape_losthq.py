"""
Scrapes 2004.losthq.rs pages and saves them as bundled resources.
Applies the same HTML transformations as GLRenderer.fetchLostHqHtml.
Crawls the 8 main pages and all internal sub-pages linked from them.
Output: src/main/resources/losthq/
"""
import re
import time
import urllib.request
import urllib.parse
import urllib.error
import os
import sys
from pathlib import Path

BASE_URL = "https://2004.losthq.rs/"
OUT_DIR = Path(__file__).parent.parent / "src/main/resources/losthq"
ICON_DIR = OUT_DIR / "img"
QUEST_COMPLETE_IMAGE_WIDTH = 284

MAIN_URLS = [
    "https://2004.losthq.rs/?p=questguides",
    "https://2004.losthq.rs/?p=skillguides",
    "https://2004.losthq.rs/?p=npcdb",
    "https://2004.losthq.rs/?p=itemdb",
    "https://2004.losthq.rs/?p=specialguides",
    "https://2004.losthq.rs/?p=calculators",
    "https://2004.losthq.rs/?p=cluetables",
    "https://2004.losthq.rs/?p=clueguides",
]

ICON_URLS = [
    "https://2004.losthq.rs/img/questicon.png",
    "https://2004.losthq.rs/img/stats.png",
    "https://2004.losthq.rs/img/skeleton.png",
    "https://2004.losthq.rs/img/itemdb.png",
    "https://2004.losthq.rs/img/specialguides.png",
    "https://2004.losthq.rs/img/swordicon.png",
    "https://2004.losthq.rs/img/casket.png",
    "https://2004.losthq.rs/img/clueicon.png",
]

# Must match injectCompactCss in GLRenderer.java. Widths stay responsive because
# the maximized client panel uses the margin available beside the game.
COMPACT_CSS = """<style>
body { width: 292px !important; max-width: 292px !important;
       margin: 0; padding: 2px 6px; background: #111; color: #ddd;
       font-family: Arial, sans-serif; font-size: 12px; line-height: 1.5; }
div.body, .main-content, .main-page, .quest-container, .row,
.stone-box, #narrowscroll, .narrowscroll-bg, .narrowscroll-bgimg, .narrowscroll-content {
    width: auto !important; max-width: 100% !important;
    margin: 0; padding: 2px; display: block; }
.quest-column { background: #2a2a2a; border: 1px solid #444; margin: 4px 0; padding: 4px; }
img, canvas { max-width: 100% !important; height: auto; display: block; margin: 3px 0; }
table { width: 100% !important; max-width: 100% !important; margin: 3px 0; border-collapse: collapse; }
td, th { padding: 2px 4px; font-size: 11px; }
th { color: #ffd700; font-weight: bold; border-bottom: 1px solid #444; }
tr:nth-child(even) td { background: #1a1a1a; }
h1, h2, h3 { font-size: 13px; margin: 6px 0 3px; color: #ff981f; font-weight: bold; }
ul { margin: 3px 0; padding-left: 14px; }
a { color: #90c040; text-decoration: none; }
.quest-header { color: #ff981f; font-weight: bold; }
.quest-entry { margin: 1px 0; }
.main-menu, #site-options-container, #scrollToTop, .img-modal { display: none; }
</style>"""


def uri_to_filename(url: str) -> str:
    """Map a 2004.losthq.rs URL to a safe resource filename using all query params."""
    parsed = urllib.parse.urlparse(url)
    params = urllib.parse.parse_qs(parsed.query)
    parts = []
    for key in sorted(params.keys()):
        for val in params[key]:
            safe_val = re.sub(r'[^a-zA-Z0-9_-]', '_', val)
            parts.append(f"{key}_{safe_val}")
    return "_".join(parts) + ".html" if parts else "index.html"


def fetch_raw(url: str, retries: int = 3) -> str:
    headers = {"User-Agent": "Progressive-Java-Client"}
    for attempt in range(retries):
        try:
            req = urllib.request.Request(url, headers=headers)
            with urllib.request.urlopen(req, timeout=15) as r:
                return r.read().decode("utf-8", errors="replace")
        except Exception as e:
            if attempt == retries - 1:
                raise
            print(f"  retry {attempt+1}/{retries} for {url}: {e}")
            time.sleep(1.5)


def collect_image_urls(html: str) -> list[str]:
    """Return all image src values that belong to losthq (relative or absolute /img/...)."""
    imgs = []
    for m in re.finditer(r'<img[^>]+src=["\']([^"\']+)["\']', html, re.IGNORECASE):
        src = m.group(1)
        if src.startswith('http') and '2004.losthq.rs' not in src:
            continue  # external domain
        if src.startswith('data:'):
            continue
        imgs.append(src)
    return imgs


def download_image(src: str, out_dir: Path) -> None:
    """Download a single image, preserving relative path under out_dir."""
    # Normalise: strip leading / so /img/foo.png → img/foo.png
    rel = src.lstrip('/')
    dest = out_dir / rel
    if dest.exists():
        return
    dest.parent.mkdir(parents=True, exist_ok=True)
    url = src if src.startswith('http') else 'https://2004.losthq.rs/' + rel
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "Progressive-Java-Client"})
        with urllib.request.urlopen(req, timeout=10) as r:
            dest.write_bytes(r.read())
    except Exception as e:
        print(f"  WARN: image {rel}: {e}", file=sys.stderr)


def wrap_text_nodes(html: str) -> str:
    """Insert narrow-reader line breaks into text nodes without touching tags."""
    def wrap(match: re.Match) -> str:
        text = match.group(1)
        normalized = re.sub(r"\s+", " ", text).strip()
        if not normalized:
            return match.group(0)
        lines = []
        line = ""
        for word in normalized.split(" "):
            if line and len(line) + 1 + len(word) > 36:
                lines.append(line)
                line = word
            else:
                line = f"{line} {word}".strip()
        if line:
            lines.append(line)
        leading = ("<br>" if len(normalized) > 28 else " ") if text[0].isspace() else ""
        trailing = " " if text[-1].isspace() else ""
        return ">" + leading + "<br>".join(lines) + trailing + "<"

    body = re.search(r"(?is)<body[^>]*>", html)
    if not body:
        return html
    return html[:body.end()] + re.sub(r">([^<]+)<", wrap, html[body.end():])


def transform_html(html: str) -> str:
    """Apply same transformations as GLRenderer.fetchLostHqHtml."""
    skill_guides_index = 'content="https://2004.losthq.rs/?p=skillguides"' in html
    html = re.sub(r'(?is)<body[^>]*>', '<body>', html, count=1)
    html = re.sub(r'(?is)<link[^>]*rel="stylesheet"[^>]*>', '', html)
    html = re.sub(r'(?is)<script[^>]*>.*?</script>', '', html)
    html = re.sub(r'(?is)<a href="/">\s*<img[^>]*losthq\.png[^>]*>\s*</a>', '', html, count=1)
    html = re.sub(r'(?is)<div class="main-menu e">.*?</div>', '', html)
    html = re.sub(r'(?is)<div class="top-border"></div>', '', html)
    html = re.sub(r'(?is)<div class="left-border"></div>', '', html)
    html = re.sub(r'(?is)<div class="right-border"></div>', '', html)
    html = re.sub(r'(?is)<div class="bottom-border"></div>', '', html)
    html = re.sub(r'(?is)<div style="text-align:center;[^"]*"[^>]*>.*?site-options-container.*?</div>\s*</div>', '', html)
    # Strip elements JEditorPane CSS can't hide with display:none
    html = re.sub(r'(?is)<[^>]+id="scrollToTop"[^>]*>.*?</[a-z]+>', '', html)
    html = re.sub(r'(?is)<div[^>]+class="[^"]*img-modal[^"]*"[^>]*>.*?</div>', '', html)
    html = re.sub(r'(?is)<img[^>]+class="[^"]*narrowscroll-(?:top|bottom)[^"]*"[^>]*>', '', html)
    # Rewrite absolute /img/... paths to relative so they resolve from the classpath base
    html = re.sub(r'(?i)(src=["\'])/img/', r'\1img/', html)
    # Swing's HTML renderer handles percentage image widths inconsistently.
    html = re.sub(
        r'(?i)(<img\b[^>]*src=["\'][^"\']*questimages/quest_complete/[^"\']*["\'][^>]*\bwidth=["\'])\d+%(["\'])',
        rf'\g<1>{QUEST_COMPLETE_IMAGE_WIDTH}\2',
        html,
    )
    # Swing does not execute the site script that paints item canvases. Replace
    # them with readable labels so checklist entries do not appear blank.
    def replace_canvas(match: re.Match[str]) -> str:
        attributes = match.group(1)

        def attr(name: str) -> str | None:
            value = re.search(rf'(?i)\b{re.escape(name)}\s*=\s*(["\'])(.*?)\1', attributes)
            return value.group(2) if value else None

        item_name = attr("itemname")
        if not item_name:
            if skill_guides_index and attr("skills") == "skillTree":
                return """<div class="quest-column">
<div class="quest-entry"><a href="?p=skillguides&amp;skill=cooking">Cooking</a></div>
<div class="quest-entry"><a href="?p=skillguides&amp;skill=crafting">Crafting</a></div>
<div class="quest-entry"><a href="?p=skillguides&amp;skill=firemaking">Firemaking</a></div>
<div class="quest-entry"><a href="?p=skillguides&amp;skill=fletching">Fletching</a></div>
<div class="quest-entry"><a href="?p=skillguides&amp;skill=magic">Magic</a></div>
<div class="quest-entry"><a href="?p=skillguides&amp;skill=mining">Mining</a></div>
<div class="quest-entry"><a href="?p=skillguides&amp;skill=runecraft">Runecraft</a></div>
<div class="quest-entry"><a href="?p=skillguides&amp;skill=smithing">Smithing</a></div>
<div class="quest-entry"><a href="?p=skillguides&amp;skill=woodcutting">Woodcutting</a></div>
</div>"""
            return ""
        replacement = attr("name-replace")
        label = replacement or item_name.replace("_", " ").strip().capitalize()
        amount = attr("amount")
        if amount and not replacement:
            label = f"{amount} {label}"
        return label + (attr("name-append") or "")

    html = re.sub(r'(?is)<canvas\b([^>]*)>.*?</canvas>', replace_canvas, html)
    html = wrap_text_nodes(html)
    html = re.sub(r'(?is)</head>', COMPACT_CSS + '\n</head>', html, count=1)
    return html


DATA_BASE_CSS = """<style>
body { width: 312px; margin: 0; padding: 2px 6px; background: #111; color: #ddd;
       font-family: Arial, sans-serif; font-size: 12px; line-height: 1.5; }
div.body, .main-content { width: 300px; margin: 0; padding: 2px; display: block; }
table { width: 312px; margin: 3px 0; border-collapse: collapse; }
td, th { padding: 2px 4px; font-size: 11px; }
th { color: #ffd700; font-weight: bold; border-bottom: 1px solid #444; }
tr:nth-child(even) td { background: #1a1a1a; }
h1, h2, h3 { font-size: 13px; margin: 6px 0 3px; color: #ff981f; font-weight: bold; }
a { color: #90c040; text-decoration: none; }
</style>"""

SHARED_TABLE_LABELS = {
    "godpage": "God item (Saradomin / Guthix / Zamorak)",
    "hard_ultra": "Ultra-rare hard reward",
    "hard_ultra_potions": "Ultra-rare hard potion",
    "hard_ultra_gilded": "Gilded armour set",
}


def _static_page(title: str, body: str) -> str:
    return (
        '<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN"'
        ' "http://www.w3.org/TR/html4/loose.dtd">\n'
        f"<html><head>\n<meta http-equiv='content-type' content='text/html; charset=UTF-8'>\n"
        f"<title>{title}</title>\n{DATA_BASE_CSS}\n"
        f"</head><body><div class='body'><div class='main-content'>\n{body}\n"
        "</div></div></body></html>"
    )


def generate_static_databases(out_dir: Path) -> None:
    """Fetch live JSON data and generate static HTML for NPC DB, item DB, and clue tables."""
    print("\nGenerating static database pages...")
    ver = str(int(time.time()))  # version-bust the JSON fetches

    def _fetch_json(path: str) -> object:
        url = BASE_URL + path + f"?v={ver}"
        print(f"  Fetching {path}")
        return json.loads(fetch_raw(url))

    import json as _json

    try:
        items_raw = _fetch_json("js/itemdb/item_data.json")
    except Exception as e:
        print(f"  WARN: could not fetch item_data.json: {e}", file=sys.stderr)
        items_raw = []

    try:
        npcs_raw = _fetch_json("js/npcdb/npc_data.json")
    except Exception as e:
        print(f"  WARN: could not fetch npc_data.json: {e}", file=sys.stderr)
        npcs_raw = {}

    try:
        clue_raw = _fetch_json("pages/cluetables/clue_rewards.json")
    except Exception as e:
        print(f"  WARN: could not fetch clue_rewards.json: {e}", file=sys.stderr)
        clue_raw = {}

    item_names: dict[str, str] = {
        it["debugname"]: it["name"]
        for it in items_raw
        if "debugname" in it and it.get("name")
    }

    def _humanize(debugname: str) -> str:
        if debugname in item_names:
            return item_names[debugname]
        s = debugname.replace("_", " ")
        return ("Noted " + s[5:].title()) if s.startswith("cert ") else s.title()

    def _fmt_reward(entry: list) -> str:
        parts = []
        for slot in entry:
            for dname, qty in slot.items():
                if dname.startswith("~"):
                    parts.append(SHARED_TABLE_LABELS.get(dname[1:], dname[1:].replace("_", " ").title()))
                else:
                    name = _humanize(dname)
                    if isinstance(qty, str) and "-" in qty:
                        parts.append(f"{name} ({qty})")
                    elif qty == 1:
                        parts.append(name)
                    else:
                        parts.append(f"{name} x{qty}")
        return " / ".join(parts)

    # ── Clue tables ──
    if clue_raw:
        html = "<h2>Treasure Trail Rewards</h2>\n"
        for tier in ["easy", "medium", "hard"]:
            t = clue_raw.get(tier, {})
            if not t:
                continue
            mn, mx = t.get("min_rolls", "?"), t.get("max_rolls", "?")
            html += f"<h3>{tier.capitalize()} Clues</h3>\n"
            html += f"<p>Rolls {mn}&#8211;{mx} rewards per clue.</p>\n"
            html += "<table>\n<col width='222'><col width='90'>\n"
            html += "<tr><th>Reward</th><th>Rarity</th></tr>\n"
            for r in t.get("rewards", []):
                html += f"<tr><td>{_fmt_reward(r)}</td><td>Common</td></tr>\n"
            for r in t.get("rare_rewards", []):
                html += f"<tr><td>{_fmt_reward(r)}</td><td>Rare</td></tr>\n"
            html += "</table>\n<br>\n"
        (out_dir / "p_cluetables.html").write_text(_static_page("Treasure Trail Rewards", html), encoding="utf-8")
        print("  -> p_cluetables.html")

    # ── NPC DB ──
    if npcs_raw:
        npc_list = sorted(npcs_raw.values(), key=lambda n: (n.get("name") or "").lower())
        html = f"<h2>NPC Database</h2>\n<p>{len(npc_list):,} NPCs sorted by name.</p>\n"
        html += "<table>\n<col width='130'><col width='50'><col width='132'>\n"
        html += "<tr><th>Name</th><th>Level / HP</th><th>Description</th></tr>\n"
        for n in npc_list:
            name = n.get("name", "?")
            vis = str(n.get("vislevel", ""))
            hp = n.get("hitpoints", "")
            stat = ""
            if vis and vis not in ("hide", ""):
                stat = f"Lv {vis}"
            if hp:
                stat = (stat + "<br>" if stat else "") + f"{hp} HP"
            desc = n.get("desc", "")
            html += f"<tr><td>{name}</td><td>{stat}</td><td>{desc}</td></tr>\n"
        html += "</table>\n"
        (out_dir / "p_npcdb.html").write_text(_static_page("NPC Database", html), encoding="utf-8")
        print("  -> p_npcdb.html")

    # ── Item DB ──
    if items_raw:
        item_list = sorted([it for it in items_raw if it.get("name")], key=lambda i: i["name"].lower())
        html = f"<h2>Item Database</h2>\n<p>{len(item_list):,} items sorted by name. * = Members only.</p>\n"
        html += "<table>\n<col width='130'><col width='40'><col width='142'>\n"
        html += "<tr><th>Item</th><th>Cost</th><th>Description</th></tr>\n"
        for it in item_list:
            name = it["name"]
            cost = it.get("cost", "")
            if isinstance(cost, int) and cost >= 1000:
                cost = f"{cost:,}"
            desc = it.get("desc", "")
            m = " *" if it.get("members") else ""
            html += f"<tr><td>{name}{m}</td><td>{cost}</td><td>{desc}</td></tr>\n"
        html += "</table>\n"
        (out_dir / "p_itemdb.html").write_text(_static_page("Item Database", html), encoding="utf-8")
        print("  -> p_itemdb.html")


def find_internal_links(html: str) -> set[str]:
    """Find all ?p= links on the losthq domain."""
    links = set()
    for m in re.finditer(r'href=["\']([^"\']*)["\']', html, re.IGNORECASE):
        href = m.group(1)
        if href.startswith("http") and "2004.losthq.rs" not in href:
            continue
        if "?p=" not in href:
            continue
        if href.startswith("/") or href.startswith("?"):
            href = urllib.parse.urljoin(BASE_URL, href)
        if href.startswith(BASE_URL):
            links.add(href)
    return links


def scrape_page(url: str, label: str) -> tuple[str, set[str], list[str]]:
    print(f"  Fetching {label}: {url}")
    html = fetch_raw(url)
    sub_links = find_internal_links(html)
    img_urls = collect_image_urls(html)
    html = transform_html(html)
    return html, sub_links, img_urls


def main():
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    ICON_DIR.mkdir(parents=True, exist_ok=True)

    queued: list[tuple[str, str]] = [(u, f"main[{i}]") for i, u in enumerate(MAIN_URLS)]
    visited: set[str] = set()
    saved: list[str] = []

    while queued:
        url, label = queued.pop(0)
        if url in visited:
            continue
        visited.add(url)

        try:
            html, sub_links, img_urls = scrape_page(url, label)
        except Exception as e:
            print(f"  ERROR fetching {url}: {e}", file=sys.stderr)
            continue

        filename = uri_to_filename(url)
        out_path = OUT_DIR / filename
        out_path.write_text(html, encoding="utf-8")
        saved.append(filename)
        print(f"    -> saved {filename} ({len(html)} bytes, {len(sub_links)} sub-links, {len(img_urls)} images)")

        for src in img_urls:
            download_image(src, OUT_DIR)

        for link in sorted(sub_links):
            if link not in visited:
                queued.append((link, f"sub of {uri_to_filename(url)}"))

        time.sleep(0.15)

    # Download icons
    print("\nDownloading icons...")
    for icon_url in ICON_URLS:
        name = icon_url.split("/")[-1]
        out_path = ICON_DIR / name
        try:
            req = urllib.request.Request(icon_url, headers={"User-Agent": "Progressive-Java-Client"})
            with urllib.request.urlopen(req, timeout=10) as r:
                out_path.write_bytes(r.read())
            print(f"  -> {name}")
        except Exception as e:
            print(f"  WARN: could not fetch {name}: {e}", file=sys.stderr)

    manifest_lines = []
    for url in sorted(visited):
        filename = uri_to_filename(url)
        if (OUT_DIR / filename).exists():
            manifest_lines.append(f"{url}={filename}")
    manifest_path = OUT_DIR / "manifest.properties"
    manifest_path.write_text("\n".join(manifest_lines) + "\n", encoding="utf-8")
    print(f"\nManifest written: {manifest_path}")

    generate_static_databases(OUT_DIR)

    print(f"Done. {len(saved)} pages saved to {OUT_DIR}")


if __name__ == "__main__":
    main()
