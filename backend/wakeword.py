"""
wakeword.py: optional wake phrase ("Hey Nova") for the Windows desktop app (7.1.0-dev.2).

Engine: the Windows built-in offline speech recognizer (System.Speech / SAPI, en-US), run in a hidden PowerShell child with a
grammar that contains ONLY the wake phrases, plus a dictation grammar so ordinary speech matches "something else" instead of
being forced onto a wake phrase. No new dependency, no download, no network: audio never leaves the process and is never
stored. (Chosen over a continuous Whisper loop, which would keep the GPU/CPU busy, and over cloud recognisers.)

Privacy rules:
* Off by default; turning it on needs {"confirm": true} after the page has explained it.
* Listens only while an Abhify.AI page on this computer is open and VISIBLE (the page reports itself every second; after
  8 s without a visible page the engine is stopped). Windows shows its microphone-in-use indicator while it runs.
* After an app update it stays paused until you confirm again (never silently re-enabled by an update).
* After a detection it pauses until the page says the interaction is over (or 90 s), so the answer being read aloud can't
  trigger it again.
"""
from __future__ import annotations

import base64
import json
import logging
import subprocess
import sys
import threading
import time
from typing import Any

import local_file_service as lfs

log = logging.getLogger(__name__)
SENSITIVITY = {"low": 0.85, "medium": 0.7, "high": 0.55}          # minimum recognizer confidence for a detection
DEFAULTS = {"enabled": False, "use_name": True, "phrase": "", "name_only": False, "sensitivity": "medium", "confirmed_version": ""}
VISIBLE_GRACE_S = 8.0
PAUSE_AFTER_DETECTION_S = 90.0

PS_SCRIPT = r"""
$ErrorActionPreference = 'Stop'
try {
  Add-Type -AssemblyName System.Speech
  $cfg = [System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('__CFG__')) | ConvertFrom-Json
  $culture = New-Object System.Globalization.CultureInfo('en-US')
  $rec = New-Object System.Speech.Recognition.SpeechRecognitionEngine($culture)
  $choices = New-Object System.Speech.Recognition.Choices
  foreach ($p in $cfg.phrases) { $choices.Add([string]$p) }
  $gb = New-Object System.Speech.Recognition.GrammarBuilder; $gb.Culture = $culture; $gb.Append($choices)
  $wake = New-Object System.Speech.Recognition.Grammar($gb); $wake.Name = 'wake'; $rec.LoadGrammar($wake)
  if ($cfg.garbage) { $other = New-Object System.Speech.Recognition.DictationGrammar; $other.Name = 'other'; $rec.LoadGrammar($other) }
  if ($cfg.file) { $rec.SetInputToWaveFile([string]$cfg.file) } else { $rec.SetInputToDefaultAudioDevice() }
  [Console]::Out.WriteLine('READY'); [Console]::Out.Flush()
  $start = [DateTime]::UtcNow
  while ($true) {
    if ($cfg.timeout -and ([DateTime]::UtcNow - $start).TotalSeconds -gt $cfg.timeout) { break }
    $t0 = [DateTime]::UtcNow
    try { $r = $rec.Recognize([TimeSpan]::FromSeconds(5)) } catch { if ($cfg.file) { break } else { throw } }   # a file's end detaches the input
    if ($r -eq $null) { if ($cfg.file) { break } else { continue } }
    $ms = [int]([DateTime]::UtcNow - $t0).TotalMilliseconds
    $pos = 0; if ($r.Audio -ne $null) { $pos = [int]$r.Audio.AudioPosition.TotalMilliseconds }
    $line = [string]::Format([System.Globalization.CultureInfo]::InvariantCulture, "{0}`t{1}`t{2:F3}`t{3}`t{4}", $r.Grammar.Name, $r.Text, $r.Confidence, $ms, $pos)
    [Console]::Out.WriteLine("RESULT`t" + $line); [Console]::Out.Flush()
  }
  [Console]::Out.WriteLine('END'); [Console]::Out.Flush()
} catch { [Console]::Out.WriteLine("ERROR`t" + $_.Exception.Message); [Console]::Out.Flush() }
"""


# --------------------------------------------------------------------------- settings
def settings() -> dict[str, Any]:
    return {**DEFAULTS, **(lfs.load_settings().get("wake") or {})}


def save(changes: dict[str, Any]) -> dict[str, Any]:
    cur = settings()
    cur.update({k: v for k, v in changes.items() if k in DEFAULTS and v is not None})
    if cur["sensitivity"] not in SENSITIVITY:
        raise ValueError("Sensitivity must be low, medium or high.")
    lfs.update_settings(wake=cur)
    return cur


def phrases(cfg: dict[str, Any] | None = None, name: str | None = None) -> list[str]:
    import identity
    cfg = cfg or settings()
    name = name or identity.current()
    if not cfg.get("use_name") and cfg.get("phrase"):
        return [cfg["phrase"]]
    out = [f"Hey {name}", f"Hello {name}"]
    if cfg.get("name_only"):
        out.append(name)
    return out


def compatibility(phrase_list: list[str]) -> str | None:
    """Why the Windows English recognizer can't be used for these phrases, or None."""
    for p in phrase_list:
        if not p or len(p) > 40:
            return "Use a wake phrase of 1 to 40 characters."
        if any(ord(ch) > 0x24F for ch in p):
            return f"\"{p}\" uses letters the Windows English (en-US) recognizer can't pronounce. Choose a name in Latin letters, or use the microphone button."
        words = [w for w in p.split() if w]
        if len(words) == 1 and len(words[0]) < 4:
            return f"\"{p}\" alone is too short to recognise reliably. Use \"Hey {p}\"."
    return None


# --------------------------------------------------------------------------- availability
_avail: dict[str, Any] | None = None


def availability() -> dict[str, Any]:
    """Is the Windows offline recognizer installed? (Cached; checked once per run.)"""
    global _avail
    if _avail is not None:
        return _avail
    if sys.platform != "win32":
        _avail = {"supported": False, "why": "The wake phrase uses the Windows speech recognizer, so it is available on Windows only."}
        return _avail
    try:
        out = subprocess.run(["powershell", "-NoProfile", "-NonInteractive", "-Command",
                              "Add-Type -AssemblyName System.Speech; [System.Speech.Recognition.SpeechRecognitionEngine]::InstalledRecognizers() "
                              "| ForEach-Object { $_.Culture.Name }"], capture_output=True, text=True, timeout=30,
                             creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        langs = [x.strip() for x in out.stdout.splitlines() if x.strip()]
    except Exception as e:  # noqa: BLE001
        langs, err = [], type(e).__name__
    else:
        err = None
    ok = "en-US" in langs
    _avail = {"supported": ok, "engine": "Windows Speech Recognition (offline, built in)", "languages": langs,
              "why": None if ok else ("The Windows English (United States) speech recognizer isn't installed. Add it in Windows Settings → Time & "
                                      "language → Speech, or use the microphone button." + (f" ({err})" if err else ""))}
    return _avail


# --------------------------------------------------------------------------- engine process
def _command(cfg: dict[str, Any]) -> list[str]:
    script = PS_SCRIPT.replace("__CFG__", base64.b64encode(json.dumps(cfg).encode("utf-8")).decode("ascii"))
    enc = base64.b64encode(script.encode("utf-16-le")).decode("ascii")
    return ["powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", enc]


def recognize_file(path: str, phrase_list: list[str], garbage: bool = True) -> list[dict[str, Any]]:
    """Test helper and "test my phrase" support: every result the engine produces for a WAV file."""
    out = subprocess.run(_command({"phrases": phrase_list, "garbage": garbage, "file": path}), capture_output=True, text=True,
                         timeout=120, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    rows = []
    for line in out.stdout.splitlines():
        if line.startswith("RESULT\t"):
            _, grammar, text, conf, ms, pos = line.split("\t")
            rows.append({"grammar": grammar, "text": text, "confidence": float(conf), "process_ms": int(ms), "audio_ms": int(pos)})
        elif line.startswith("ERROR\t"):
            raise RuntimeError(line.split("\t", 1)[1])
    return rows


def test_live(phrase_list: list[str], seconds: float = 8.0, sensitivity: str = "medium") -> dict[str, Any]:
    """"Test my wake phrase": listens to the microphone for [seconds] (the page shows a countdown) and reports whether the
    phrase was recognised, with what confidence and after how long. Nothing is recorded or kept. The background engine is
    stopped meanwhile so the two don't compete for the microphone."""
    ENGINE._stop("off")
    t0 = time.time()
    try:
        out = subprocess.run(_command({"phrases": phrase_list, "garbage": True, "file": None, "timeout": seconds}), capture_output=True,
                             text=True, timeout=seconds + 25, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    except subprocess.TimeoutExpired:
        return {"ok": False, "error": "The test didn't finish in time."}
    heard, best = [], None
    for line in out.stdout.splitlines():
        if line.startswith("ERROR\t"):
            msg = line.split("\t", 1)[1]
            return {"ok": False, "error": "No microphone is available to the Windows speech recognizer." if "audio" in msg.lower() else msg[:160]}
        if line.startswith("RESULT\t"):
            _, grammar, text, conf, ms, pos = line.split("\t")
            heard.append(text if grammar == "other" else f"[{text}]")
            if grammar == "wake" and (best is None or float(conf) > best["confidence"]):
                best = {"phrase": text, "confidence": round(float(conf), 2), "after_ms": int(pos)}
    need = SENSITIVITY.get(sensitivity, 0.7)
    return {"ok": True, "detected": bool(best and best["confidence"] >= need), "best": best, "threshold": need, "heard": heard[-5:],
            "seconds": round(time.time() - t0, 1)}


class Engine:
    def __init__(self) -> None:
        self.proc: subprocess.Popen | None = None
        self.lock = threading.Lock()
        self.state = "off"                      # off | starting | listening | paused | error
        self.error: str | None = None
        self.detection: dict[str, Any] | None = None
        self.count = 0
        self.last_visible = 0.0
        self.paused_until = 0.0
        self.phrases: list[str] = []
        self.started_at: float | None = None
        self._watch: threading.Thread | None = None

    # the page reports itself; the watchdog starts/stops the process accordingly
    def heartbeat(self, visible: bool) -> None:
        if visible:
            self.last_visible = time.time()
        self._ensure_watch()

    def _ensure_watch(self) -> None:
        if self._watch is None or not self._watch.is_alive():
            self._watch = threading.Thread(target=self._loop, name="wake-watchdog", daemon=True)
            self._watch.start()

    def wanted(self) -> tuple[bool, str]:
        from version import __version__
        cfg = settings()
        if not cfg["enabled"]:
            return False, "off"
        if cfg.get("confirmed_version") != __version__:
            return False, "paused_after_update"
        if not availability()["supported"]:
            return False, "unsupported"
        if compatibility(phrases(cfg)):
            return False, "incompatible"
        if time.time() - self.last_visible > VISIBLE_GRACE_S:
            return False, "page_hidden"
        if time.time() < self.paused_until:
            return False, "paused"
        return True, "listening"

    def _loop(self) -> None:
        while True:
            want, why = self.wanted()
            running = self.proc is not None and self.proc.poll() is None
            if want and not running:
                self._start()
            elif not want and running:
                self._stop(why)
            elif not want:
                self.state = "paused" if why == "paused" else ("off" if why in ("off", "page_hidden") else why)
            if not settings()["enabled"] and not running and time.time() - self.last_visible > 60:
                return                                           # nothing to watch
            time.sleep(0.5)

    def _start(self) -> None:
        with self.lock:
            cfg = settings()
            self.phrases = phrases(cfg)
            self.state, self.error = "starting", None
            try:
                self.proc = subprocess.Popen(_command({"phrases": self.phrases, "garbage": True, "file": None}), stdout=subprocess.PIPE,
                                             stderr=subprocess.DEVNULL, stdin=subprocess.DEVNULL, text=True, bufsize=1,
                                             creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
            except OSError as e:
                self.state, self.error = "error", f"The speech recognizer couldn't start ({type(e).__name__})."
                return
            self.started_at = time.time()
            threading.Thread(target=self._read, args=(self.proc, SENSITIVITY[cfg["sensitivity"]]), name="wake-reader", daemon=True).start()

    def _read(self, proc: subprocess.Popen, min_conf: float) -> None:
        for line in proc.stdout:                                 # type: ignore[union-attr]
            line = line.rstrip("\r\n")
            if line == "READY":
                self.state = "listening"
            elif line.startswith("ERROR\t"):
                msg = line.split("\t", 1)[1]
                self.state = "error"
                self.error = ("No microphone is available to the Windows speech recognizer." if "audio" in msg.lower() or "device" in msg.lower()
                              else "The Windows speech recognizer stopped with an error.")
                log.info("wake engine error: %s", msg[:120])
            elif line.startswith("RESULT\t"):
                parts = line.split("\t")
                if len(parts) >= 4 and parts[1] == "wake" and float(parts[3]) >= min_conf:
                    self.count += 1
                    self.detection = {"id": self.count, "phrase": parts[2], "confidence": round(float(parts[3]), 2), "at": time.time()}
                    self.paused_until = time.time() + PAUSE_AFTER_DETECTION_S
                    self._stop("paused")
                    return

    def _stop(self, why: str = "off") -> None:
        with self.lock:
            p, self.proc = self.proc, None
            if p and p.poll() is None:
                try:
                    p.kill()
                except OSError:
                    pass
            self.state = "paused" if why == "paused" else ("off" if why in ("off", "page_hidden") else why)

    def resume(self) -> None:
        self.paused_until = 0.0
        self._ensure_watch()

    def shutdown(self) -> None:
        self._stop("off")

    def status(self) -> dict[str, Any]:
        cfg = settings()
        want, why = self.wanted()
        return {"settings": cfg, "phrases": phrases(cfg), "state": self.state if (self.proc or self.state == "error") else why,
                "error": self.error, "detection": self.detection, "availability": availability(),
                "incompatible": compatibility(phrases(cfg)), "sensitivity_levels": list(SENSITIVITY)}


ENGINE = Engine()
