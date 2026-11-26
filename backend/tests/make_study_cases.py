"""Generates backend/tests/data/study_cases.json from study_core (the reference). Kotlin and JS ports must reproduce it.
Run: python backend/tests/make_study_cases.py"""
import json
import pathlib
import sys
from datetime import date

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
import study_core as sc  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parents[2]
OUT = pathlib.Path(__file__).parent / "data" / "study_cases.json"
TAX = json.loads((ROOT / "android" / "app" / "src" / "main" / "assets" / "skills_taxonomy.json").read_text(encoding="utf-8"))["skills"]

JDS = {
    "ml_engineer": """Machine Learning Engineer
Requirements:
- 3+ years of Python and SQL
- Experience with PyTorch or TensorFlow, and deploying models (MLOps)
- Strong knowledge of machine learning and deep learning
Nice to have:
- AWS or Google Cloud, Docker, Kubernetes
- Experience with LLMs and RAG""",
    "cloud_engineer": """CLOUD ENGINEER
What you'll need:
* Hands-on AWS (EC2, S3, IAM) and Terraform
* CI/CD with GitHub Actions; Linux and Bash scripting
* Docker and Kubernetes in production
Bonus: Azure certification, Python""",
    "data_analyst": """Data Analyst - we're looking for someone with SQL, Excel and Tableau or Power BI.
You know statistics and A/B testing. Python (pandas) is a plus.""",
}


def build() -> dict:
    today = date(2026, 12, 1)
    cases = {"taxonomy_size": len(TAX), "extract": {}, "gaps": {}, "plan": {}, "readiness": [], "review": [], "streak": []}
    for k, jd in JDS.items():
        cases["extract"][k] = {"jd": jd, "expect": sc.extract_skills(jd, TAX)}
    resume = ["Python", "SQL", "Docker", "Git"]
    g = sc.gaps(cases["extract"]["ml_engineer"]["expect"], resume)
    cases["gaps"] = {"jd": "ml_engineer", "resume_skills": resume, "expect": g}
    cases["plan"] = {"gaps": g, "today": today.isoformat(), "interview": date(2026, 12, 6).isoformat(), "minutes": 60,
                     "expect": sc.make_plan(g, date(2026, 12, 6), today, 60),
                     "no_date_days": len(sc.make_plan(g, None, today, 60)), "past_date_days": len(sc.make_plan(g, date(2026, 11, 1), today, 60))}
    for answered, correct, ld, lt in [(0, 0, 0, 0), (4, 4, 0, 3), (10, 7, 2, 4), (25, 25, 5, 5), (12, 3, 0, 10)]:
        s = sc.skill_score(answered, correct, ld, lt)
        cases["readiness"].append({"answered": answered, "correct": correct, "lessons_done": ld, "lessons_total": lt, "score": s, "level": sc.level(s)})
    cases["readiness_overall"] = {"skills": ["Python", "AWS", "SQL"],
                                  "mcq": {"Python": {"answered": 20, "correct": 16}, "SQL": {"answered": 5, "correct": 2}},
                                  "lessons": {"Python": {"done": 3, "total": 5}, "AWS": {"done": 0, "total": 6}},
                                  "expect": sc.readiness(["Python", "AWS", "SQL"], {"Python": {"answered": 20, "correct": 16}, "SQL": {"answered": 5, "correct": 2}},
                                                         {"Python": {"done": 3, "total": 5}, "AWS": {"done": 0, "total": 6}})}
    for tr, ok, lp in [(0, False, 0), (0, True, 0), (2, True, 0), (4, True, 0), (3, False, 0), (0, False, 1), (0, False, 3), (1, True, 2)]:
        cases["review"].append({"times_right": tr, "answered_on": today.isoformat(), "correct": ok, "lapses": lp, "expect": sc.next_review(tr, today, ok, lp)})
    cases["adapt"] = {"gaps": g, "rows": [{"skill": "Machine Learning", "score": 82}, {"skill": "PyTorch", "score": 69}, {"skill": "Python", "score": 70}],
                      "expect": sc.adapt_gaps(g, [{"skill": "Machine Learning", "score": 82}, {"skill": "PyTorch", "score": 69}, {"skill": "Python", "score": 70}])}
    for days in [[], ["2026-12-01"], ["2026-11-30", "2026-11-29"], ["2026-12-01", "2026-11-30", "2026-11-28"]]:
        cases["streak"].append({"days": days, "today": today.isoformat(), "expect": sc.streak(days, today)})
    mods = [{"id": "a", "lessons": [{"id": "a1"}, {"id": "a2"}]}, {"id": "b", "lessons": [{"id": "b1"}, {"id": "b2"}, {"id": "b3"}]},
            {"id": "c", "lessons": [{"id": f"c{i}"} for i in range(1, 9)]}]
    cases["schedule"] = [{"modules": mods, "minutes": m, "start": today.isoformat(), "optional": o, "done": dn,
                          "expect": sc.course_schedule(mods, m, today, o, dn)}
                         for m, o, dn in [(30, [], []), (60, ["a"], ["b1"]), (90, [], ["a1", "a2"]), (10, ["c"], [])]]
    cases["mastery"] = [{"module": mods[1], "done": dn, "stats": st, "expect": sc.module_mastery(mods[1], dn, st)}
                        for dn, st in [([], {}), (["b1", "b2"], {"b": {"answered": 4, "correct": 1}}), (["b1", "b2", "b3"], {"b": {"answered": 12, "correct": 11}})]]
    cases["next_lesson"] = [{"modules": mods, "done": dn, "optional": o, "expect": sc.next_lesson(mods, dn, o)}
                            for dn, o in [([], []), ([], ["a"]), (["b1", "b2", "b3"] + [f"c{i}" for i in range(1, 9)], ["a"]),
                                          (["a1", "a2", "b1", "b2", "b3"] + [f"c{i}" for i in range(1, 9)], [])]]
    src = "Built REST APIs in Python and FastAPI serving 10k users; cut latency 30% with Redis caching."
    cases["fact_guard"] = [{"source": src, "rewritten": r, "expect": sc.fact_guard(src, r, TAX)} for r in [
        "Designed Python REST APIs (FastAPI) for 10k users and reduced latency by 30% using Redis.",
        "Led Kubernetes migration of Python APIs serving 50k users.",
        "Built APIs; improved latency 30.5% with caching.", ""]]
    comps = [{"id": "leadership", "question": "Tell me about a time you led."}, {"id": "conflict", "question": "A disagreement?"}]
    cases["mock_plan"] = [{"skills": sk, "competencies": comps, "count": n, "expect": sc.mock_plan(sk, comps, n)}
                          for sk, n in [(["Python", "SQL", "AWS"], 6), ([], 4), (["Python"], 2), (["A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K"], 20)]]
    ans = "I used Redis to cache the hot queries, and latency dropped a lot. First I measured, then I fixed the slowest endpoint."
    cases["feedback"] = [{"answer": ans, "o": o, "expect": sc.check_feedback(o, ans)} for o in [
        {"scores": {"relevance": 4, "depth": 3, "structure": 5}, "evidence": ["used Redis to cache the hot queries", "I led a team of 12", "ab"], "tips": ["Give a number."]},
        {"scores": {"relevance": 6, "depth": 3, "structure": 5}},
        {"scores": {"relevance": 4.4, "depth": 2.6, "structure": True}},
        {"scores": {"relevance": 4.4, "depth": 2.6, "structure": 1}, "evidence": "not a list", "tips": []}]]
    f1 = sc.check_feedback({"scores": {"relevance": 4, "depth": 3, "structure": 5}}, ans)
    f2 = sc.check_feedback({"scores": {"relevance": 2, "depth": 2, "structure": 3}}, ans)
    rows = [{"skill": "Python", "feedback": f1}, {"competency": "conflict", "feedback": f2}, {"skill": "SQL", "feedback": None}]
    cases["mock_summary"] = [{"rows": rows, "expect": sc.mock_summary(rows)}, {"rows": [], "expect": sc.mock_summary([])}]
    cases["quality"] = [{"probes": p, "expect": sc.quality_verdict(p)} for p in
                        [{"json_mcq": True, "lesson_sections": True, "fact": True}, {"json_mcq": True, "lesson_sections": False, "fact": True}, {"fact": True}, {}]]
    return cases


if __name__ == "__main__":
    OUT.write_text(json.dumps(build(), ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print("wrote", OUT)
