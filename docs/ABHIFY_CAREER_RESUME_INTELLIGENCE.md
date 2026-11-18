# Resume intelligence for career practice

## What already existed and is reused (Android)
- **`resume/Resume.parse`:** contact details, sections, skills (from a known list, whole words only), measurable achievements and date ranges, all by rules.
- **Uncertain fields:** values from lines OCR wasn't sure about are marked uncertain.
- **Missing fields:** listed, never filled in.
- **Elsewhere:** the skill taxonomy (133 skills), the quality score, the details editor (your corrections) and versions in Career → Resumes.
- **Your file:** the uploaded resume is never changed. Corrections and AI output are separate versions.

## What this milestone adds
- **Dashboard:** the active resume now shows the skills found in its text ("No known skills found" when there are none).
- **MCQs and Generate:** you choose the resume. Its skills and projects can be selected as chips, so you see exactly what will be used.
- **Separation:** several resumes are kept apart, and only the selected one is sent to the model.
- **Technology names for practice:** the Skills section plus mixed-case names (FastAPI, PyTorch, YOLO), excluding names, job titles, degrees and generic words (`techTerms` / `tech_terms`).
- **Desktop:** a resume is any document in the knowledge folder (PDF, TXT, MD). The desktop has no resume editor, so Resumes, Jobs and Match remain Android features.

## Grounding rules
- **Resume-based questions:** may use only what the resume says. A question's quote is checked against the text.
- **General questions:** about the resume's technologies, never claiming anything about the candidate.
- **Fenced data:** resume text is fenced as untrusted data, and instructions inside it are ignored.
