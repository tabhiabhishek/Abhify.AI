"""
7.0.1 citation integrity: the app, not the model, decides which citations an answer may show.

The evidence for an answer is retrieved before the model runs (realtime.py) and numbered deterministically:
[W1], [W2] … for outside sources in retrieval order, [L1] … for the user's own records. This module checks the
model's answer against that registry AFTER generation:

  * Registry: an immutable snapshot of the sources (frozen records + a SHA-256 fingerprint), so the citations shown
    can be tied to exactly what was retrieved.
  * Invalid citations: a marker whose id isn't in the registry ([W9] with five sources) is removed.
  * Claim-to-source mapping: each sentence with a marker is compared with the cited source (content words and, above
    all, numbers: a number in the sentence must appear in the source). If the cited source doesn't support it but
    exactly one other source clearly does, the marker is corrected to that source (small local models often put a fact
    under the wrong number). If no source supports it, the marker becomes "[unsupported]".
  * URLs: in an answer with retrieved sources, a link that isn't one of those sources is removed (the model must not
    invent URLs); markdown link text is kept.

Pure and deterministic (no model call); the same rules and the shared cases in tests/data/citation_cases.json are
used by the Android app (intel/Realtime.kt, Citations).
"""
from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass
from types import MappingProxyType
from typing import Any, Mapping

UNSUPPORTED = "[unsupported]"
MARK = re.compile(r"\[\s*([WL])\s*(\d{1,3})\s*\](?:\((?!https?://)[^)\s]*\))?", re.I)       # [W2] and a model's [W2](#)
GROUP = re.compile(r"\[\s*((?:[WL]\s*\d{1,3}\s*[,;&]\s*(?:and\s+)?)+[WL]\s*\d{1,3})\s*\]", re.I)  # [W1, W2] -> [W1][W2]
MD_LINK = re.compile(r"\[([^\]\n]{1,200})\]\((https?://[^)\s]+)\)")
URL = re.compile(r"https?://[^\s<>()\[\]\"']+")
NUM = re.compile(r"(?<![\w.])\d+(?:[.,]\d+)*")
WORD = re.compile(r"[^\W\d_][\w'-]{2,}", re.UNICODE)
STOP = set("""the and for are was were has have had with that this from they their there what when where which who will would
could should about into over than then them these those been being also just more most some such only very can its it's
not but our your you per via according said says say report reports reported today now new latest current currently""".split())


@dataclass(frozen=True)
class Source:
    id: str
    title: str
    publisher: str
    url: str | None
    published_at: int | None
    retrieved_at: int | None
    text: str                    # title + publisher + snippet, lowercased, for matching


class Registry:
    """The immutable source registry for one answer: id -> Source, plus a fingerprint of the exact sources."""

    def __init__(self, sources: list[dict[str, Any]]):
        recs = {}
        for s in sources or []:
            sid = str(s.get("id") or "")
            if not re.fullmatch(r"[WL]\d{1,3}", sid) or sid in recs:
                continue
            text = " ".join(str(s.get(k) or "") for k in ("title", "publisher", "snippet")).lower()
            recs[sid] = Source(sid, str(s.get("title") or ""), str(s.get("publisher") or ""), s.get("url") or None,
                               s.get("published_at"), s.get("retrieved_at"), text)
        self.sources: Mapping[str, Source] = MappingProxyType(recs)
        canon = json.dumps([[r.id, r.title, r.publisher, r.url, r.published_at, r.retrieved_at] for r in recs.values()],
                           ensure_ascii=False, separators=(",", ":"))
        self.fingerprint = hashlib.sha256(canon.encode("utf-8")).hexdigest()[:16]
        self.urls = {r.url for r in recs.values() if r.url}


def _num(s: str) -> str:
    return s.replace(",", "")


def _features(text: str) -> tuple[set[str], set[str]]:
    words = {w.lower().strip("'-") for w in WORD.findall(text)} - STOP
    nums = {_num(n) for n in NUM.findall(text)}
    return words, nums


def support(claim: str, src: Source) -> float:
    """0 = the source can't back this claim; higher = more of the claim's words (and all its numbers) are in the source."""
    words, nums = _features(claim)
    if not words and not nums:
        return 0.0
    src_nums = {_num(n) for n in NUM.findall(src.text)}
    if nums and not (nums & src_nums):
        return 0.0                                              # a number the source doesn't contain: not supported
    hit = sum(1 for w in words if w in src.text)
    score = hit / max(1, min(len(words), 6))
    if nums:
        score += len(nums & src_nums) / len(nums)
    return score


def _supported(score: float, claim: str) -> bool:
    words, nums = _features(claim)
    return score >= (0.5 if nums else 0.34) and (bool(nums) or sum(1 for _ in words) >= 1)


def _clean_urls(text: str, reg: Registry, removed: list[str]) -> str:
    def known(u: str) -> bool:
        u = u.rstrip(".,;:!?")
        return any(u == k or u.rstrip("/") == k.rstrip("/") for k in reg.urls)

    def md(m: re.Match) -> str:
        if known(m.group(2)):
            return m.group(0)
        removed.append(m.group(2))
        return m.group(1)

    text = MD_LINK.sub(md, text)

    def bare(m: re.Match) -> str:
        u = m.group(0)
        if known(u):
            return u
        removed.append(u.rstrip(".,;:!?"))
        trail = u[len(u.rstrip(".,;:!?")):]
        return "(link removed: not a retrieved source)" + trail

    return URL.sub(bare, text)


REF = re.compile(r"(?i)\b(?:information|info|details?|article|story|report|source|item|study|piece|link|news|coverage|data)\s+(?:on|in|from|at|of)(?:[\s,&]|\band\b)*$"
                 r"|\b(?:see|read|check|visit)(?:[\s,&]|\band\b)*$")
AGE = re.compile(r"(?i)\b(\d+)((?:\s*(?:,|and|or|to|-)\s*\d+)*)\s+(minutes?|mins?|hours?|hrs?|days?|weeks?)\s+ago\b")
_UNIT_MS = {"min": 60_000, "hou": 3_600_000, "hrs": 3_600_000, "hr": 3_600_000, "day": 86_400_000, "wee": 604_800_000}


_ATTR_DATE = re.compile(r"(\d{4})-(\d{2})-(\d{2})")


def _strip_attribution(seg: str, marks: list[re.Match], reg: Registry) -> str:
    """'…research impact. [W1] MSUToday, published 2026-11-18 19:29 (6 h ago)': right after a marker, the cited source's
    OWN publisher name (and, optionally, its publication date and age) is an attribution, not a claim, so it's set aside
    before matching. A date that isn't the source's real publication date (±1 day) is kept and checked like any claim."""
    out = seg
    for m in reversed(marks):
        src = reg.sources.get((m.group(1) + m.group(2)).upper())
        if not src or not src.publisher or src.publisher == "unknown":
            continue
        t = re.match(r"\s*[-–—,:(]?\s*" + re.escape(src.publisher) + r"\b[),\s]*(?:published\s+)?(?:on\s+)?"
                     r"(\d{4}-\d{2}-\d{2}(?:[ T]\d{1,2}:\d{2})?)?\s*(?:\(\s*\d+\s*(?:min(?:ute)?s?|h|hrs?|hours?|d|days?)\s*ago\s*\))?",
                     seg[m.end():], re.I)
        if not t:
            continue
        if t.group(1):
            if not src.published_at:
                continue
            from datetime import datetime, timezone
            y, mo, d = (int(x) for x in _ATTR_DATE.match(t.group(1)).groups())
            try:
                said = datetime(y, mo, d, tzinfo=timezone.utc).timestamp() * 1000
            except ValueError:
                continue
            if abs(said - src.published_at) > 2 * 86_400_000:   # ±1 day around the real date (time zones included)
                continue
        out = out[:m.end()] + out[m.end() + t.end():]
    return out


def _ages_check(claim: str, refs: list[Source]) -> tuple[set[str], bool]:
    """Ages in a remark about sources ("is from 5 days ago", "from 1 and 2 days ago"): each number must match the real
    age (retrieved - published, ±1 unit) of one of the referenced sources. (numbers explained, all ages correct)."""
    explained: set[str] = set()
    for m in AGE.finditer(claim):
        unit = _UNIT_MS.get(m.group(3).lower()[:3]) or _UNIT_MS.get(m.group(3).lower()[:2], 0)
        for n in [m.group(1)] + re.findall(r"\d+", m.group(2) or ""):
            ok = any(r.published_at and r.retrieved_at and abs((r.retrieved_at - r.published_at) / unit - int(n)) <= 1 for r in refs)
            if not ok:
                return explained, False
            explained.add(n)
    return explained, True
_END = re.compile(r"[.!?]+(?=\s|$)|\n")


def sentences(text: str) -> list[str]:
    """Splits after . ! ? followed by a space or the end, and at newlines; a dot inside "22.7", "GPT-5.5" or a domain
    doesn't end a sentence. The pieces join back to exactly the input."""
    out, start = [], 0
    for m in _END.finditer(text):
        end = m.end()
        while end < len(text) and text[end] in " \t":
            end += 1
        out.append(text[start:end])
        start = end
    if start < len(text):
        out.append(text[start:])
    return out


def check(answer: str, sources: list[dict[str, Any]]) -> tuple[str, dict[str, Any]]:
    """(the answer with only verified / corrected citations, a report). With no sources the answer is unchanged except
    that markers pointing nowhere are removed."""
    reg = Registry(sources)
    report: dict[str, Any] = {"fingerprint": reg.fingerprint, "sources": len(reg.sources), "checked": 0, "verified": 0,
                              "remapped": [], "unsupported": [], "invalid": [], "urls_removed": []}
    text = GROUP.sub(lambda m: "".join(f"[{p.strip().upper().replace(' ', '')}]" for p in re.split(r"[,;&]|\band\b", m.group(1)) if p.strip()), answer or "")
    if reg.sources:
        text = _clean_urls(text, reg, report["urls_removed"])
    out: list[str] = []
    context: list[str] = []                                       # unmarked text since the last citation, within the paragraph
    for seg in sentences(text):
        marks = list(MARK.finditer(seg))
        if not marks:
            out.append(seg)
            if not seg.strip() and out[-2:-1] and out[-2].endswith("\n"):
                context = []                                      # a blank line ends the paragraph
            elif seg.strip():
                context.append(seg)
            continue
        claim = MARK.sub(" ", _strip_attribution(seg, marks, reg)).strip()
        if not any(_features(claim)):
            # "…human intervention. [W1]": a marker on its own refers to the text it follows (since the last citation)
            claim = " ".join(c.strip() for c in context)[-600:] or claim
        context = []
        report["checked"] += 1
        cited = []
        for m in marks:
            sid = (m.group(1) + m.group(2)).upper()
            if sid not in reg.sources:
                report["invalid"].append(sid)
            elif sid not in cited:
                cited.append(sid)
        # A marker used as the NAME of a source ("the information on [W1] is from a study…", "see [W2]") refers to the source
        # instead of backing a claim: kept as a reference (not counted as verified) when it exists and every number in the
        # sentence is in that source. Narrow on purpose: "According to [W1], …" or "In [W1], X happened" stay fully checked.
        refs = [m for m in marks if (m.group(1) + m.group(2)).upper() in cited and REF.search(MARK.sub(" ", seg[:m.start()]))]
        if refs and len(refs) == len(marks):
            ref_srcs = [reg.sources[(m.group(1) + m.group(2)).upper()] for m in refs]
            aged, ages_ok = _ages_check(claim, ref_srcs)                 # "is from 5 days ago": checked against the real dates
            nums = _features(claim)[1] - aged
            if ages_ok and all(not nums or nums & {_num(n) for n in NUM.findall(r.text)} for r in ref_srcs):
                report["references"] = report.get("references", 0) + 1
                out.append(seg)
                continue
        decision: dict[str, str] = {}
        scores = {sid: support(claim, src) for sid, src in reg.sources.items()}
        any_ok = [sid for sid in cited if _supported(scores[sid], claim)]
        for sid in cited:
            if sid in any_ok:
                decision[sid] = sid
                continue
            better = [o for o, sc in scores.items() if o != sid and o[0] == sid[0] and _supported(sc, claim) and sc > scores[sid]]
            best = max(better, key=lambda o: scores[o]) if better else None
            if best and len([o for o in better if scores[o] == scores[best]]) == 1 and best not in cited and not any_ok:
                decision[sid] = best
                report["remapped"].append({"from": sid, "to": best, "claim": claim[:160]})
            elif any_ok:
                decision[sid] = ""                                # another cited source backs it: drop the wrong one
            else:
                decision[sid] = UNSUPPORTED
        if any_ok or any(v and v != UNSUPPORTED for v in decision.values()):
            report["verified"] += 1
        elif cited:
            report["unsupported"].append(claim[:160])
        seen: set[str] = set()

        def repl(m: re.Match) -> str:
            sid = (m.group(1) + m.group(2)).upper()
            new = decision.get(sid, "")
            if not new or new in seen:
                return ""
            seen.add(new)
            return new if new == UNSUPPORTED else f"[{new}]"
        fixed = MARK.sub(repl, seg)
        out.append(re.sub(r"[ \t]+([.!?,;:])", r"\1", re.sub(r"[ \t]{2,}", " ", fixed)))
    report["invalid"] = list(dict.fromkeys(report["invalid"]))
    return "".join(out), report


CITE_CTX = re.compile(r"(?i)\b(?:sources?|references?|according\s+to|cited|citations?|via|read more at|see)\b[^\n]{0,45}$")
UNVERIFIED = "(unverified: from the model's memory, not retrieved)"


def label_unverified(answer: str) -> tuple[str, list[str]]:
    """An answer WITHOUT retrieved sources that still presents links as its sources ("Source: https://…", "according to
    [AI Weekly](https://…)") would show citations nobody checked. Each such link becomes its site name plus a plain
    "unverified" label (no link; the URL is never fetched). Links that aren't presented as a source are left alone."""
    flagged: list[str] = []

    def host(u: str) -> str:
        from urllib.parse import urlsplit
        try:
            return (urlsplit(u).hostname or u).removeprefix("www.")
        except ValueError:
            return u

    def md(m: re.Match) -> str:
        if not CITE_CTX.search(answer[max(0, m.start() - 60):m.start()]):
            return m.group(0)
        flagged.append(m.group(2))
        return f"{m.group(1)} ({host(m.group(2))}) {UNVERIFIED}"

    text = MD_LINK.sub(md, answer or "")

    def bare(m: re.Match) -> str:
        if not CITE_CTX.search(text[max(0, m.start() - 60):m.start()]):
            return m.group(0)
        u = m.group(0).rstrip(".,;:!?")
        flagged.append(u)
        return f"{host(u)} {UNVERIFIED}" + m.group(0)[len(u):]

    return URL.sub(bare, text), flagged


def sanitize_timestamps(published_at: int | None, retrieved_at: int | None, now: int) -> tuple[int | None, int, list[str]]:
    """Published must not be after retrieval (10 min clock skew allowed) and retrieval not in the future; a published
    time that fails becomes None ("date not given"), never a guess."""
    notes = []
    ret = int(retrieved_at or now)
    if ret > now + 60_000:
        notes.append("retrieval time was in the future; set to now")
        ret = now
    pub = int(published_at) if published_at else None
    if pub is not None and (pub > ret + 600_000 or pub < 0):
        notes.append("publication time after retrieval; dropped")
        pub = None
    return pub, ret, notes
