# Abhify.AI 3.2: Career Copilot (Career tab)

| Section | Features (implemented) |
|---|---|
| **Resume** | Opens the 3.1 Resume Assistant. Upload (PDF, DOCX, TXT, MD, photo with OCR), review and correct, details found by rules, summary, suggestions, interview questions, improved draft, job-description comparison, missing skills, saved versions, export (.md, .txt). The original is never changed. New facts in AI output are flagged ("Not found in your resume"). |
| **Jobs** | Saved job descriptions: add, edit, delete, "Compare with resume" (exact local skill match, then an optional AI comparison). **Application tracker** with company, title, job description, resume and resume version (original, corrected or draft), application/interview/follow-up dates (yyyy-mm-dd, validated), status (Saved, Applied, Assessment, Interview, Offer, Rejected, Withdrawn), notes. Create, edit, search (company, title, notes), status filters with counts, a "Due now" list, delete with confirmation. |
| **Interview practice** | Topics: Technical, Python, AI/ML, My projects (from the open resume, never invented), Behavioural (STAR). Levels: beginner, intermediate, senior. Each question comes with an answer outline. Answer by typing or 🎙 voice (the transcription is editable before submitting). AI feedback: good points, gaps, a stronger answer outline, a follow-up question. Attempts are saved locally. |
| **Question bank** | The existing offline interview questions (moved from Learn) |

## Rules

- **No hiring probability.** Exact matches (word match on a skills list) are shown separately from AI suggestions.
- Missing skills are listed for you to decide on; they are **never** added to the resume.

## Privacy

- **Stays on the phone:** applications, job descriptions and attempts (database v7, not synced).
- **Online use:** resume text and answers go to an online provider only after the consent dialog, which also offers "This phone".
- **Sharing a resume analysis:** emails, phone numbers and links are hidden unless you tick "Include contact details". Key-like strings are always removed. Tracker data is never part of a share.
- **Voice:** recognition runs through Android's recognizer, which on some phones uses Google's online service (shown in Settings → Voice). No audio is saved. Leaving the screen stops the microphone.
