package ai.abhify.android.intel

/**
 * 5.6 local AI-quality evaluation for the context finder: precision@k and recall over labelled cases. It runs on built-in
 * synthetic fixtures (tests) or on cases the owner writes; no user data is collected or logged, nothing leaves the phone.
 */
object RetrievalEval {
    data class Case(val question: String, val relevantIds: Set<String>)
    data class Score(val precisionAtK: Double, val recall: Double, val emptyWhenNothingRelevant: Double, val cases: Int)

    fun run(cases: List<Case>, records: List<ContextResolver.Record>, permitted: Set<ContextResolver.Source>, k: Int = 3, now: Long = System.currentTimeMillis()): Score {
        var p = 0.0; var r = 0.0; var withRel = 0; var noRel = 0; var noRelEmpty = 0
        for (c in cases) {
            val got = ContextResolver.resolve(c.question, records, permitted, now, limit = k, minShared = ContextResolver.chatMinShared(c.question)).items.map { it.record.id }
            if (c.relevantIds.isEmpty()) { noRel++; if (got.isEmpty()) noRelEmpty++; continue }
            withRel++
            val hits = got.count { it in c.relevantIds }
            p += if (got.isEmpty()) 0.0 else hits.toDouble() / got.size
            r += hits.toDouble() / c.relevantIds.size
        }
        return Score(if (withRel == 0) 1.0 else p / withRel, if (withRel == 0) 1.0 else r / withRel, if (noRel == 0) 1.0 else noRelEmpty.toDouble() / noRel, cases.size)
    }
}
