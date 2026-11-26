"""
transcribe.py: Faster-Whisper speech recognition on this computer (the private, local voice engine).

The frontend records audio with MediaRecorder and posts it here; nothing leaves the computer.

7.1: the model is loaded ONLY from the local cache (local_files_only). Before, the first transcription on a computer
without the model silently downloaded it (about 145 MB for "base") from Hugging Face, and offline it failed with a raw
library error. Now status() says whether the model is installed and download() fetches it only when the user presses
Download (with the size shown). The model is loaded lazily on first use and reused afterwards. On machines with an
NVIDIA GPU it runs on CUDA (float16); otherwise on the CPU (int8).
"""
from __future__ import annotations

import io
import logging
import threading
import time

from config import get_settings

log = logging.getLogger(__name__)

_model = None
_lock = threading.Lock()
_device = None
# Download sizes of the official faster-whisper conversions (Systran/faster-whisper-*, model.bin + tokenizer), in MB.
SIZES_MB = {"tiny": 75, "base": 145, "small": 484, "medium": 1530, "large-v3": 3090}
_download = {"state": "idle", "error": None, "started": None, "finished": None}


class NotInstalled(RuntimeError):
    """The speech model isn't on this computer yet (the user hasn't downloaded it)."""


def _repo(name: str) -> str:
    return name if "/" in name else f"Systran/faster-whisper-{name}"


def library_available() -> bool:
    try:
        import faster_whisper  # noqa: F401  (heavy import only to check it exists)
        return True
    except Exception:  # noqa: BLE001 - a broken install is "not available", never a crash
        return False


def model_installed(name: str | None = None) -> bool:
    """True when the model files are in the local cache (checked without any network access)."""
    name = name or get_settings().whisper_model
    try:
        from faster_whisper.utils import download_model
        download_model(name, local_files_only=True)
        return True
    except Exception:  # noqa: BLE001 - missing snapshot, no cache folder, ...
        return False


def status() -> dict:
    s = get_settings()
    lib = library_available()
    installed = lib and model_installed(s.whisper_model)
    return {"engine": "faster-whisper", "available": lib, "model": s.whisper_model, "installed": installed,
            "loaded": _model is not None, "device": _device, "download_mb": SIZES_MB.get(s.whisper_model),
            "source": f"huggingface.co/{_repo(s.whisper_model)}", "download": dict(_download),
            "ready": bool(lib and installed)}


def _run_download(name: str) -> None:
    try:
        from faster_whisper.utils import download_model
        download_model(name)
        _download.update(state="done", finished=time.time())
        log.info("Whisper '%s' downloaded", name)
    except Exception as e:  # noqa: BLE001 - reported to the UI
        _download.update(state="error", error=f"The download failed ({type(e).__name__}). Check the internet connection and try again.",
                         finished=time.time())
        log.warning("Whisper download failed: %s", type(e).__name__)


def download() -> dict:
    """Starts the user-approved download of the configured model (one at a time). Returns the status."""
    name = get_settings().whisper_model
    if not library_available():
        raise RuntimeError("The speech recognition library isn't installed in this copy of Abhify.AI.")
    if model_installed(name):
        _download.update(state="done", error=None)
        return status()
    if _download["state"] != "downloading":
        _download.update(state="downloading", error=None, started=time.time(), finished=None)
        threading.Thread(target=_run_download, args=(name,), name="whisper-download", daemon=True).start()
    return status()


def _load():
    global _model, _device
    with _lock:
        if _model is not None:
            return _model
        s = get_settings()
        if not model_installed(s.whisper_model):
            raise NotInstalled(f"The local speech model (Whisper {s.whisper_model}, about {SIZES_MB.get(s.whisper_model, '?')} MB) "
                               "isn't installed yet. Download it in Settings → Voice, or choose the browser's speech recognition.")
        from faster_whisper import WhisperModel   # heavy import: only when needed
        attempts = [("cuda", "float16"), ("cpu", "int8")] if s.whisper_device == "auto" else \
                   [(s.whisper_device, "float16" if s.whisper_device == "cuda" else "int8")]
        last = None
        for device, compute in attempts:
            try:
                _model = WhisperModel(s.whisper_model, device=device, compute_type=compute, local_files_only=True)
                _device = device
                log.info("Whisper '%s' loaded on %s (%s)", s.whisper_model, device, compute)
                return _model
            except Exception as e:  # no CUDA runtime, etc.
                last = e
                log.warning("Whisper on %s failed: %s", device, e)
        raise RuntimeError(f"Could not load Whisper: {last}")


# 7.1.0: Whisper is known to "hear" these on silence or noise. Kept only when the clip really contains speech.
HALLUCINATIONS = {"thank you", "thank you.", "thanks for watching", "thanks for watching!", "thank you for watching", "you", "bye", "bye.",
                  "bye-bye", "so", "okay", "oh", "hmm", "uh", "subtitles by the amara.org community", "please subscribe", ".", "...", "♪"}
MIN_SPEECH_S = 0.3


def keep_segment(text: str, no_speech_prob: float, avg_logprob: float) -> bool:
    """A segment is speech unless Whisper itself says it's probably not (silence/noise), or it's a known silence phrase
    with low confidence. Pure (tested)."""
    t = text.strip()
    if not t:
        return False
    if no_speech_prob > 0.6 and avg_logprob < -0.8:
        return False
    if t.lower().strip(" !?.,") in {h.strip(" !?.,") for h in HALLUCINATIONS} and (no_speech_prob > 0.3 or avg_logprob < -0.6):
        return False
    return True


def transcribe(audio: bytes, language: str | None = None) -> dict:
    """Transcribe an audio clip (webm/ogg/wav/mp3...). Blocking: call via a thread.
    7.1.0: when the clip holds no speech (silence, a muted or wrong microphone), the text is empty and `speech` is False,
    so the app says the microphone didn't pick up a voice instead of acting on words Whisper invented."""
    model = _load()
    segments, info = model.transcribe(io.BytesIO(audio), language=language, beam_size=5, vad_filter=True,
                                      condition_on_previous_text=False)
    segs = list(segments)
    speech_s = float(getattr(info, "duration_after_vad", info.duration) or 0.0)
    kept = [s.text.strip() for s in segs if keep_segment(s.text, getattr(s, "no_speech_prob", 0.0), getattr(s, "avg_logprob", 0.0))]
    text = " ".join(kept).strip() if speech_s >= MIN_SPEECH_S else ""
    return {"text": text, "language": info.language, "duration": round(info.duration, 2), "speech": speech_s >= MIN_SPEECH_S,
            "speech_seconds": round(speech_s, 2), "dropped": len(segs) - len(kept)}
