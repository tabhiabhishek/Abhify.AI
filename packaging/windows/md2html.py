"""
md2html.py: turn docs/WINDOWS_INSTALLATION.md into a self-contained INSTALL.html (F6, dev.3).

The installer's finish page used to open INSTALL.md, and many Windows PCs have no
app for .md files. Every PC can open .html. This converter has no dependencies
and covers only what the install guide uses: headings, paragraphs, ordered and
unordered (nested) lists, tables, fenced code, block quotes, **bold**, *italic*,
`code` and links. Relative links to other .md files become plain text (those
files aren't installed); http(s) links stay links. No scripts, no external assets.
"""
from __future__ import annotations

import html
import re
import sys
from pathlib import Path

CSS = """
:root { color-scheme: light dark; --bg: #ffffff; --fg: #0f172a; --muted: #475569; --line: #e2e8f0;
        --code: #f1f5f9; --accent: #7c3aed; }
@media (prefers-color-scheme: dark) {
  :root { --bg: #0b1220; --fg: #e2e8f0; --muted: #94a3b8; --line: #1e293b; --code: #111a2e; --accent: #a78bfa; }
}
body { margin: 0; background: var(--bg); color: var(--fg); font: 16px/1.6 "Segoe UI", system-ui, sans-serif; }
main { max-width: 860px; margin: 0 auto; padding: 24px 16px 48px; }
h1 { font-size: 1.8rem; margin: .2em 0 .6em; } h2 { margin-top: 1.8em; border-bottom: 1px solid var(--line); }
h3 { margin-top: 1.4em; } a { color: var(--accent); }
code { background: var(--code); padding: .1em .35em; border-radius: 4px; font-size: .92em; word-break: break-word; }
pre { background: var(--code); padding: 12px; border-radius: 8px; overflow-x: auto; } pre code { padding: 0; }
.table { overflow-x: auto; } table { border-collapse: collapse; width: 100%; margin: 1em 0; }
th, td { border: 1px solid var(--line); padding: 6px 10px; text-align: left; vertical-align: top; }
blockquote { margin: 1em 0; padding: .2em 1em; border-left: 4px solid var(--accent); color: var(--muted); }
footer { color: var(--muted); font-size: .85rem; margin-top: 3em; }
"""


def inline(text: str) -> str:
    codes: list[str] = []

    def keep_code(m):
        codes.append(f"<code>{html.escape(m.group(1))}</code>")
        return f"\x00{len(codes) - 1}\x00"
    text = re.sub(r"`([^`]+)`", keep_code, text)
    text = html.escape(text, quote=False)

    def link(m):
        label, url = m.group(1), m.group(2)
        if re.match(r"https?://", url):
            return f'<a href="{html.escape(url)}" rel="noopener">{label}</a>'
        return label                                     # relative docs aren't installed: keep the text
    text = re.sub(r"\[([^\]]+)\]\(([^)\s]+)\)", link, text)
    text = re.sub(r"\*\*([^*]+)\*\*", r"<strong>\1</strong>", text)
    text = re.sub(r"(?<![*\w])\*([^*\n]+)\*(?![*\w])", r"<em>\1</em>", text)
    return re.sub(r"\x00(\d+)\x00", lambda m: codes[int(m.group(1))], text)


def convert(md: str, title: str = "Installing Abhify.AI") -> str:
    lines = md.replace("\r\n", "\n").split("\n")
    out: list[str] = []
    para: list[str] = []
    lists: list[tuple[int, str]] = []                    # stack of (indent, "ul"/"ol")
    i = 0

    def flush_para():
        if para:
            out.append(f"<p>{inline(' '.join(para))}</p>")
            para.clear()

    def close_lists(to_indent: int = -1):
        while lists and lists[-1][0] > to_indent:
            out.append(f"</li></{lists.pop()[1]}>")

    while i < len(lines):
        line = lines[i]
        if line.startswith("```"):
            flush_para(); close_lists()
            code = []
            i += 1
            while i < len(lines) and not lines[i].startswith("```"):
                code.append(lines[i]); i += 1
            out.append(f"<pre><code>{html.escape(chr(10).join(code))}</code></pre>")
        elif m := re.match(r"(#{1,6})\s+(.*)", line):
            flush_para(); close_lists()
            n = len(m.group(1))
            out.append(f"<h{n}>{inline(m.group(2))}</h{n}>")
        elif line.startswith("|") and i + 1 < len(lines) and re.match(r"\|[\s:|-]+\|?$", lines[i + 1]):
            flush_para(); close_lists()
            cells = lambda l: [c.strip() for c in l.strip().strip("|").split("|")]
            head = cells(line)
            rows = []
            i += 2
            while i < len(lines) and lines[i].startswith("|"):
                rows.append(cells(lines[i])); i += 1
            i -= 1
            out.append('<div class="table"><table><thead><tr>' + "".join(f"<th>{inline(c)}</th>" for c in head)
                       + "</tr></thead><tbody>" + "".join(
                           "<tr>" + "".join(f"<td>{inline(c)}</td>" for c in r) + "</tr>" for r in rows)
                       + "</tbody></table></div>")
        elif m := re.match(r"(\s*)([-*]|\d+[.)])\s+(.*)", line):
            flush_para()
            indent, kind = len(m.group(1)), "ol" if m.group(2)[0].isdigit() else "ul"
            if lists and indent < lists[-1][0]:
                close_lists(indent)
            if not lists or indent > lists[-1][0]:
                out.append(f"<{kind}><li>")
                lists.append((indent, kind))
            else:
                out.append("</li><li>")
            out.append(inline(m.group(3)))
        elif line.startswith(">"):
            flush_para(); close_lists()
            quote = []
            while i < len(lines) and lines[i].startswith(">"):
                quote.append(lines[i].lstrip("> ").rstrip()); i += 1
            i -= 1
            out.append(f"<blockquote><p>{inline(' '.join(q for q in quote if q))}</p></blockquote>")
        elif not line.strip():
            flush_para()
            nxt = next((l for l in lines[i + 1:] if l.strip()), "")
            if lists and not re.match(r"\s*([-*]|\d+[.)])\s+|\s{2,}\S", nxt):
                close_lists()
        elif lists and line.startswith("  "):
            out.append(" " + inline(line.strip()))       # continuation line inside a list item
        else:
            close_lists()
            para.append(line.strip())
        i += 1
    flush_para(); close_lists()
    body = "\n".join(out)
    return (f'<!doctype html>\n<html lang="en"><head><meta charset="utf-8">\n'
            f'<meta name="viewport" content="width=device-width, initial-scale=1">\n'
            f"<title>{html.escape(title)}</title>\n<style>{CSS}</style></head>\n"
            f"<body><main>\n{body}\n<footer>Installed with Abhify.AI. This page is a copy of "
            f"docs/WINDOWS_INSTALLATION.md and works offline.</footer>\n</main></body></html>\n")


if __name__ == "__main__":
    src, dst = Path(sys.argv[1]), Path(sys.argv[2])
    dst.write_text(convert(src.read_text(encoding="utf-8")), encoding="utf-8")
    print(f"wrote {dst}")
