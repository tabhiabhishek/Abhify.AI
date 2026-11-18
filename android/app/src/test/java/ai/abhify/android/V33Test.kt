package ai.abhify.android

import ai.abhify.android.career.AiJson
import ai.abhify.android.career.CareerAdvice
import ai.abhify.android.career.DocxWriter
import ai.abhify.android.career.Experience
import ai.abhify.android.career.JobFields
import ai.abhify.android.career.JobParser
import ai.abhify.android.career.MatchEngine
import ai.abhify.android.career.ResumeLayout
import ai.abhify.android.career.ResumeQuality
import ai.abhify.android.career.SectionImprove
import ai.abhify.android.career.SkillMatch
import ai.abhify.android.career.SkillTaxonomy
import ai.abhify.android.chat.JobApplication
import ai.abhify.android.chat.SchemaV5
import ai.abhify.android.chat.SchemaV6
import ai.abhify.android.chat.SchemaV7
import ai.abhify.android.chat.SchemaV8
import ai.abhify.android.docs.Documents
import ai.abhify.android.life.Career
import ai.abhify.android.life.Tracker
import ai.abhify.android.resume.Resume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.DriverManager
import java.time.LocalDate

/** The same taxonomy file the app ships (assets), loaded locally (the global instance is left alone). */
private val TAX = SkillTaxonomy.parse(File("src/main/assets/skills_taxonomy.json").readText())

private val CV = """
    Asha Verma
    asha.verma@example.com | +91 90000 11111 | github.com/ashav

    Summary
    Machine learning engineer with 4 years of experience building computer vision and NLP systems in production for retail clients.

    Experience
    ML Engineer, Contoso Retail — Jan 2022 - Present
    - Built a YOLO-based object detection service in Python with FastAPI, serving 2 million images per month
    - Trained PyTorch models and cut inference latency by 40%
    Data Scientist, Fabrikam — Jun 2020 - Dec 2021
    - Built churn models with scikit-learn and MongoDB pipelines

    Projects
    - Shelf scanner: OpenCV and PyTorch pipeline that detects missing products on store shelves from phone photos

    Education
    B.Tech Computer Science, IIT Example, 2020

    Skills
    Python, FastAPI, PyTorch, OpenCV, MongoDB, ML, NLP
""".trimIndent()

private val JD = """
    Job title: Machine Learning Engineer
    Company: Northwind AI
    Location: Bengaluru (Hybrid)
    Full-time

    Responsibilities
    - Build and deploy computer vision models for retail products
    - Design REST APIs with FastAPI for model serving
    - Work with data engineers on SQL pipelines

    Requirements
    - 3+ years of experience in machine learning
    - Strong Python, PyTorch, SQL and Docker
    - Bachelor's degree in Computer Science or related field

    Nice to have
    - Kubernetes, AWS certification
""".trimIndent()

class SkillTaxonomyTest {
    @Test fun normalizationAndAliases() {
        assertEquals("Machine Learning", TAX.normalize("ML")); assertEquals("Machine Learning", TAX.normalize(" machine   learning "))
        assertEquals("Artificial Intelligence", TAX.normalize("AI")); assertEquals("Natural Language Processing", TAX.normalize("nlp"))
        assertEquals("Computer Vision", TAX.normalize("CV")); assertEquals("FastAPI", TAX.normalize("Fast API")); assertEquals("FastAPI", TAX.normalize("fastapi"))
        assertEquals("PyTorch", TAX.normalize("pytorch")); assertEquals("OpenCV", TAX.normalize("Open CV")); assertEquals("SQL", TAX.normalize("sql"))
        assertEquals("Node.js", TAX.normalize("nodejs")); assertEquals("scikit-learn", TAX.normalize("sklearn"))
        assertNull(TAX.normalize("basket weaving"))
    }

    @Test fun unrelatedSkillsAreNeverEquivalent() {
        for ((a, b) in listOf("Python" to "Java", "TensorFlow" to "PyTorch", "Machine Learning" to "Deep Learning", "FastAPI" to "Flask", "SQL" to "MongoDB"))
            assertFalse("$a vs $b", TAX.normalize(a) == TAX.normalize(b))
        assertFalse(TAX.related("OpenCV", "SQL")); assertFalse(TAX.related("Python", "Java"))
        assertTrue(TAX.related("OpenCV", "Computer Vision")); assertTrue(TAX.related("PyTorch", "Deep Learning")); assertTrue(TAX.related("YOLO", "Object Detection"))
        assertTrue(TAX.related("Whisper", "Speech Recognition")); assertTrue(TAX.related("FastAPI", "Backend Development"))
        assertTrue(TAX.find("JavaScript developer").none { it.skill == "Java" })                    // no partial words
    }

    @Test fun ambiguousWordsNeedContext() {
        assertTrue(TAX.find("Please find my CV attached.").none { it.skill == "Computer Vision" })   // curriculum vitae
        assertTrue(TAX.find("CV models with OpenCV and YOLO").any { it.skill == "Computer Vision" && it.alias })
        assertTrue(TAX.find("Spring 2020 internship in marketing").none { it.skill == "Spring Boot" })
        assertTrue(TAX.find("Java microservices with Spring").any { it.skill == "Spring Boot" })
        assertTrue(TAX.find("I excel at communication and the rest of the team agrees").none { it.skill == "Excel" || it.skill == "REST APIs" })
        assertTrue(TAX.find("Skills: Python, SQL, Excel, Tableau").any { it.skill == "Excel" })      // a skills list is context
        assertTrue(TAX.find("Built REST APIs with JSON endpoints").any { it.skill == "REST APIs" })
        assertTrue(TAX.find("R&D department, Go to market").none { it.skill == "R" || it.skill == "Go" })
    }

    @Test fun theSpecExample() {
        val m = SkillMatch.of(TAX, listOf("Python", "FastAPI", "PyTorch", "SQL", "Docker"), "Skills: Python, FastAPI, PyTorch, OpenCV, MongoDB")
        assertEquals(listOf("Python", "FastAPI", "PyTorch"), m.matched)
        assertEquals(listOf("SQL", "Docker"), m.missing)
        assertEquals(60, m.coverage)
        assertTrue(m.related.none { it.first == "SQL" && it.second == "OpenCV" })                     // OpenCV never covers SQL
        assertTrue(m.resume.containsAll(listOf("OpenCV", "MongoDB")))
        val alias = SkillMatch.of(TAX, listOf("Machine Learning", "Computer Vision"), "Experienced in ML; built image CV pipelines with OpenCV")
        assertEquals(listOf("Machine Learning", "Computer Vision"), alias.alias.map { it.skill }); assertTrue(alias.direct.isEmpty())
        val rel = SkillMatch.of(TAX, listOf("Computer Vision"), "Skills: OpenCV, Python, SQL")
        assertEquals(listOf("Computer Vision" to "OpenCV"), rel.related); assertEquals(0, rel.coverage)    // related ≠ match
        assertNull(SkillMatch.of(TAX, emptyList(), "x").coverage)
    }
}

class JobAndMatchTest {
    @Test fun jobDescriptionFieldsAreExtracted() {
        val f = JobParser.parse(JD, TAX)
        assertEquals("Machine Learning Engineer", f.title); assertEquals("Northwind AI", f.company)
        assertTrue(f.location.startsWith("Bengaluru")); assertEquals("full-time", f.employmentType); assertEquals(3, f.minYears)
        assertTrue(f.required.containsAll(listOf("Python", "PyTorch", "SQL", "Docker", "FastAPI")))
        assertTrue("Kubernetes" in f.preferred && "Kubernetes" !in f.required)
        assertEquals(3, f.responsibilities.size); assertTrue(f.education.single().contains("Bachelor"))
        assertTrue(f.certifications.single().contains("AWS certification"))
        assertEquals(f, JobFields.fromJson(f.toJson()))
        assertEquals(JobFields(), JobParser.parse("", TAX))                                           // nothing found → empty, not guessed
    }

    @Test fun experienceYearsMergeOverlaps() {
        assertEquals(2.0, Experience.years("Jan 2020 - Dec 2021", LocalDate.of(2026, 11, 5))!!, 0.01)
        assertEquals(3.0, Experience.years("Jan 2020 - Dec 2021\nJun 2021 - Dec 2022", LocalDate.of(2026, 11, 5))!!, 0.01)   // overlap counted once
        assertEquals(1.0, Experience.years("Dec 2025 - Present", LocalDate.of(2026, 11, 5))!!, 0.01)
        assertNull(Experience.years("No dates here"))
    }

    @Test fun explainableMatch() {
        val f = JobParser.parse(JD, TAX)
        val m = MatchEngine.match(CV, f, TAX, today = LocalDate.of(2026, 11, 5))
        assertTrue(m.skills.missing.containsAll(listOf("SQL", "Docker"))); assertTrue(m.skills.matched.containsAll(listOf("Python", "PyTorch", "FastAPI")))
        assertEquals(0, m.overall!! % 5); assertTrue(m.overall!! in 40..95)
        assertTrue(m.dimensions.all { it.score == null || it.score!! % 5 == 0 })                     // no false precision
        val ev = m.evidence.associateBy { it.requirement }
        assertTrue(ev["Python"]!!.result.startsWith("Direct evidence") && ev["Python"]!!.evidence!!.contains("Python"))
        assertTrue(ev["SQL"]!!.result.startsWith("No supporting evidence") && ev["SQL"]!!.result.contains("doesn't mean you lack it"))
        assertNull(ev["SQL"]!!.evidence)
        assertTrue(m.years!! > 5.5)                                                                 // Jun 2020 → Nov 2026
        assertTrue(m.dimensions.first { it.name == "Experience relevance" }.score!! >= 70)                 // 6+ years vs 3 required; title words partly match
        assertTrue(m.gaps.any { it == "Required: SQL" })
        // a job with only skills: the other dimensions are "not assessed" and don't drag the score down
        val only = MatchEngine.match(CV, JobFields(required = listOf("Python", "PyTorch")), TAX)
        // required 100% (weight 40) + projects 50% (weight 10: the projects section shows PyTorch but not Python) → 90; the rest not assessed
        assertEquals(90, only.overall); assertTrue(only.dimensions.count { it.score == null } >= 3)
        val adv = CareerAdvice.of(m, f, ResumeQuality.score(CV, tax = TAX))
        val sql = adv.first { it.recommendation == "Strengthen SQL" }
        assertTrue(sql.reason.isNotBlank() && sql.requirement.contains("SQL") && sql.evidence.isNotBlank() && sql.nextAction.contains("only if true"))
        assertEquals("SQL", CareerAdvice.learningPriorities(m, JD).first())
    }

    @Test fun qualityScoreIsTransparent() {
        val good = ResumeQuality.score(CV, tax = TAX)
        assertEquals(100, good.categories.sumOf { it.max }); assertEquals(0, good.total % 5)
        assertTrue(good.total >= 70); assertTrue(good.strengths.any { it.startsWith("Contact details") })
        assertTrue(good.categories.first { it.name == "Certifications" }.notes.single().contains("optional"))
        val poor = ResumeQuality.score("John\nI like computers.", tax = TAX)
        assertTrue(poor.total < 30); assertTrue(poor.suggestions.any { it.contains("No experience section") })
        assertEquals(good, ResumeQuality.score(CV, tax = TAX))                                        // deterministic
    }
}

class ResumeImproveExportTest {
    @Test fun sectionRewriteParsingAndReplacement() {
        val (imp, ch) = SectionImprove.parse("## Improved\nML engineer with 4 years building CV systems.\n## Changes\n- Shorter")!!
        assertEquals("ML engineer with 4 years building CV systems.", imp); assertEquals("- Shorter", ch)
        assertNull(SectionImprove.parse("Here is a better summary: …"))
        val out = SectionImprove.replaceSection(CV, Resume.Section.SUMMARY, "New summary line.")
        assertTrue(out.contains("Summary\nNew summary line.")); assertFalse(out.contains("Machine learning engineer with 4 years"))
        assertTrue(out.contains("Built a YOLO-based object detection service") && out.contains("B.Tech Computer Science"))   // other sections untouched
        assertTrue(SectionImprove.prompt("Summary", "x", "").contains("[ask:"))
        assertTrue(Resume.unsupported("Cut latency by 70% using Kubernetes", CV).containsAll(listOf("70%", "Kubernetes")))
    }

    @Test fun docxRoundTripsThroughTheAppsOwnReader() {
        val blocks = ResumeLayout.blocks(CV + "\nNotes: <b>R&D</b> \u0001 control char")
        assertTrue(blocks.first() is ResumeLayout.Block.Title)
        assertTrue(blocks.any { it is ResumeLayout.Block.Heading && it.text == "Experience" } && blocks.any { it is ResumeLayout.Block.Bullet })
        val bytes = DocxWriter.write(blocks)
        val back = Documents.extract("out.docx", null, bytes).text
        assertTrue(back, back.startsWith("Asha Verma")); assertTrue(back, back.contains("•\tTrained PyTorch models and cut inference latency by 40%"))
        assertTrue(back.contains("<b>R&D</b>")); assertFalse(back.contains("\u0001"))                  // escaped, invalid XML chars dropped
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("abhify_provider"))
        assertTrue(ResumeLayout.preview(blocks).contains("EXPERIENCE"))
    }
}

class AiJsonTest {
    @Test fun safeParsingAndValidation() {
        val ok = "Sure! ```json\n{\"scores\": {\"correctness\": 4, \"completeness\": 3, \"clarity\": 5, \"practical\": 2, \"communication\": 4}, " +
            "\"strengths\": [\"clear\"], \"missing\": [\"edge cases\"], \"structure\": \"STAR\", \"recommendations\": [\"add an example\"]}\n```"
        val (fb, why) = AiJson.feedback(ok)
        assertNull(why); assertEquals(4, fb!!.scores["correctness"]); assertEquals(listOf("edge cases"), fb.missing)
        assertTrue(AiJson.feedback("no json here").second!!.contains("JSON"))
        assertTrue(AiJson.feedback(ok.replace("\"practical\": 2", "\"practical\": 9")).second!!.contains("1–5"))
        assertTrue(AiJson.feedback(ok.replace("\"clarity\": 5, ", "")).second!!.contains("clarity"))
        assertTrue(AiJson.feedback("{\"scores\": {\"correctness\": 4, \"completeness\": 3, \"clarity\": 5, \"practical\": 2, \"communication\": 4}, \"strengths\": [], \"missing\": []}").second!!.contains("Empty"))
        assertNotNull(AiJson.extract("text {\"a\": \"brace } inside\", \"b\": {\"c\": 1}} more"))
        assertNull(AiJson.extract("{broken"))
    }

    @Test fun interviewPromptsAreScopedAndStructured() {
        val p = Career.questionPrompt(Career.Topic.JOB, "intermediate", null, emptyList(), "Mandatory skills: SQL")
        assertTrue(p.contains("<job>") && p.contains("Requirement:"))
        assertFalse(Career.questionPrompt(Career.Topic.FASTAPI, "senior", "secret resume", emptyList(), "secret job").let { it.contains("secret") })
        assertEquals("Mandatory skill: SQL", Career.requirementOf("Question: Explain joins.\nRequirement: Mandatory skill: SQL\nOutline: - x"))
        assertEquals("Explain joins.", Career.parseQuestion("Question: Explain joins.\nRequirement: SQL\nOutline: - x")!!.first)
        assertTrue(Career.feedbackPrompt("Q", "A").contains("\"scores\"") && Career.feedbackPrompt("Q", "A").contains("not a hiring decision"))
        assertTrue(Career.Topic.entries.map { it.label }.containsAll(listOf("Python", "AI/ML", "Machine Learning", "Deep Learning", "Computer Vision", "NLP",
            "Backend", "FastAPI", "SQL", "My projects", "From job description", "Behavioural")))
    }
}

class TrackerTest {
    private fun a(c: String, t: String, s: String, applied: String?, follow: String?, interview: String? = null, upd: Long = 0) =
        JobApplication(0, c, t, null, null, null, applied, s, null, follow, interview, upd)
    private val today = LocalDate.of(2026, 11, 5)
    private val all = listOf(
        a("Contoso", "ML Engineer", "APPLIED", "2026-10-01", "2026-11-03", upd = 1),
        a("Northwind", "Data Analyst", "INTERVIEW", "2026-10-20", "2026-11-08", "2026-11-10", upd = 2),
        a("Fabrikam", "ML Engineer", "REJECTED", "2026-09-01", "2026-10-01", upd = 3),
        a("Litware", "Backend Dev", "OFFER", "2026-08-01", null, upd = 4),
        a("Adatum", "Analyst", "SAVED", null, null, upd = 5))

    @Test fun filtersSortsAndFollowUps() {
        assertEquals(listOf("Contoso", "Fabrikam"), Tracker.apply(all, Tracker.Query(title = "ml eng", sort = Tracker.Sort.APPLIED)).map { it.company })
        assertEquals(listOf("Northwind"), Tracker.apply(all, Tracker.Query(company = "north")).map { it.company })
        assertEquals(listOf("Northwind", "Contoso"), Tracker.apply(all, Tracker.Query(appliedFrom = "2026-10-01", appliedTo = "2026-10-31", sort = Tracker.Sort.APPLIED)).map { it.company })
        assertEquals(listOf("Contoso", "Northwind"), Tracker.apply(all, Tracker.Query(followFrom = "2026-11-01", sort = Tracker.Sort.FOLLOW_UP)).map { it.company })
        assertEquals("Adatum", Tracker.apply(all, Tracker.Query()).first().company)                                   // last changed first
        assertEquals(listOf("Contoso"), Tracker.overdue(all, today).map { it.company })                             // rejected ones aren't overdue
        assertEquals(listOf("Northwind"), Tracker.upcoming(all, today).map { it.company })
        assertEquals(Tracker.Stats(5, 2, 1, 1, 1, 1), Tracker.stats(all, today))
    }
}

class V33SafetyTest {
    @Test fun schemaV8MigratesAPopulatedV7Database() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            val st = c.createStatement()
            st.execute("CREATE TABLE conversations (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
            st.execute("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, conversation_id INTEGER NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL, created_at INTEGER NOT NULL)")
            SchemaV5.apply { st.execute(it) }; SchemaV6.apply { st.execute(it) }; SchemaV7.apply { st.execute(it) }
            st.execute("INSERT INTO job_descriptions (title, company, body, created_at, updated_at) VALUES ('ML Eng', 'Contoso', 'Python and SQL', 1, 1)")
            st.execute("INSERT INTO job_applications (company, title, jd_id, status, created_at, updated_at) VALUES ('Contoso', 'ML Eng', 1, 'APPLIED', 1, 1)")
            st.execute("INSERT INTO interview_attempts (topic, question, answer, created_at) VALUES ('Python', 'Q', 'A', 1)")
            st.execute("INSERT INTO library_docs (uid, name, kind, size_bytes, chars, text, created_at, updated_at, role) VALUES ('d', 'cv.pdf', 'PDF', 1, 5, 'Hello', 1, 1, 'resume')")
            st.execute("INSERT INTO resume_versions (doc_id, kind, title, body, created_at) VALUES (1, 'corrected', 'C', 'Hello!', 2)")
            SchemaV8.apply { st.execute(it) }
            c.createStatement().executeQuery("SELECT title, company, body, fields FROM job_descriptions").use { r ->
                assertTrue(r.next()); assertEquals("ML Eng", r.getString(1)); assertEquals("Python and SQL", r.getString(3)); assertNull(r.getString(4)) }
            c.createStatement().executeQuery("SELECT (SELECT COUNT(*) FROM job_applications), (SELECT COUNT(*) FROM interview_attempts), (SELECT body FROM resume_versions), (SELECT text FROM library_docs)").use { r ->
                r.next(); assertEquals(1, r.getInt(1)); assertEquals(1, r.getInt(2)); assertEquals("Hello!", r.getString(3)); assertEquals("Hello", r.getString(4)) }
            st.execute("UPDATE job_descriptions SET fields = '{\"title\":\"ML Eng\"}' WHERE id = 1")
        }
        assertTrue(SchemaV8.STATEMENTS.none { Regex("\\b(DROP|DELETE FROM|RENAME|ON CONFLICT)\\b", RegexOption.IGNORE_CASE).containsMatchIn(it) })
        val db = File("src/main/java/ai/abhify/android/chat/ChatDb.kt").readText()
        val version = Regex("const val VERSION = (\\d+)").find(db)!!.groupValues[1].toInt()
        assertTrue(version >= 8 && db.contains("if (oldVersion < 8) SchemaV8.apply"))     // runs once per upgrade (version-gated)
    }

    @Test fun careerCodeRespectsPrivacyAndRouting() {
        for (f in listOf("ui/CareerState.kt", "ui/CareerScreens.kt", "career/CareerEngine.kt", "career/Skills.kt", "career/Export.kt"))
            File("src/main/java/ai/abhify/android/$f").readText().let { t -> assertFalse(f, t.contains("providerStore") || t.contains("saveKey(") || t.contains("Log.")) }
        val st = File("src/main/java/ai/abhify/android/ui/CareerState.kt").readText()
        assertFalse(st.contains("hybrid.generate") || st.contains("engine.generate")); assertTrue(st.contains("vm.aiTask("))
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:exported=\"false\"") && manifest.contains("FileProvider"))
        assertEquals(1, Regex("<paths>[\\s\\S]*?</paths>").find(File("src/main/res/xml/shared_files.xml").readText())!!.value.lines().count { it.contains("-path ") })   // only the export folder
    }
}
