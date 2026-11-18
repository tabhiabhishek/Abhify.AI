"""Password-reset mail. dev: appended to data_dir/outbox.log (private to the server operator).
prod: SMTP with STARTTLS. The reset code is never logged anywhere else."""
from __future__ import annotations

import smtplib
import ssl
import time
from email.message import EmailMessage

from .config import Settings


def send_reset_code(s: Settings, to: str, code: str) -> None:
    body = (f"Your Abhify password reset code is {code}\n\n"
            f"It expires in {s.reset_code_minutes} minutes. If you didn't ask for this, ignore this email; "
            "your password is unchanged.")
    if s.env == "dev" and not s.smtp_host:
        s.data_dir.mkdir(parents=True, exist_ok=True)
        with open(s.data_dir / "outbox.log", "a", encoding="utf-8") as f:
            f.write(f"{time.strftime('%Y-%m-%dT%H:%M:%S')} to={to} subject='Abhify password reset' code={code}\n")
        return
    msg = EmailMessage()
    msg["From"], msg["To"], msg["Subject"] = s.smtp_from, to, "Your Abhify password reset code"
    msg.set_content(body)
    with smtplib.SMTP(s.smtp_host, s.smtp_port, timeout=20) as smtp:
        smtp.starttls(context=ssl.create_default_context())
        if s.smtp_user:
            smtp.login(s.smtp_user, s.smtp_password)
        smtp.send_message(msg)
