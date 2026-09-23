package com.example.exp1

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.coroutines.resume

data class EggDay(val date: String, val total: Int, val a: Int, val b: Int, val c: Int)

data class FarmStats(
    val totalBirds: Int?,
    val activeCages: Int?
)

object FarmDataContext {
    // Cache so we don't hit Firebase on every single message. A message sent
    // within CACHE_TTL_MS of the last fetch reuses the same data.
    private var cachedDays: List<EggDay>? = null
    private var cachedFarmStats: FarmStats? = null
    private var cachedAt: Long = 0L
    private const val CACHE_TTL_MS = 45_000L // 45 seconds

    /** Returns cached data if fresh, otherwise fetches from Firebase and updates the cache. */
    suspend fun getSnapshot(forceRefresh: Boolean = false): Pair<List<EggDay>?, FarmStats?> {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedDays != null && now - cachedAt < CACHE_TTL_MS) {
            return Pair(cachedDays, cachedFarmStats)
        }
        val days = fetchDays()
        val stats = fetchFarmStats()
        if (days != null) {
            cachedDays = days
            cachedFarmStats = stats
            cachedAt = now
        }
        return Pair(cachedDays, cachedFarmStats)
    }

    private val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private fun dateMinus(daysAgo: Int): String {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -daysAgo)
        return fmt.format(cal.time)
    }

    // ---------- Realtime Database: egg collections ----------

    suspend fun fetchDays(): List<EggDay>? = withTimeoutOrNull(4000) {
        suspendCancellableCoroutine<List<EggDay>?> { cont ->
            val ref = FirebaseDatabase.getInstance().getReference("egg_collections")
            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val days = snapshot.children.mapNotNull { child ->
                        val key = child.key ?: return@mapNotNull null
                        EggDay(
                            date = key,
                            total = child.child("total").getValue(Long::class.java)?.toInt() ?: 0,
                            a = child.child("gradeA").getValue(Long::class.java)?.toInt() ?: 0,
                            b = child.child("gradeB").getValue(Long::class.java)?.toInt() ?: 0,
                            c = child.child("gradeC").getValue(Long::class.java)?.toInt() ?: 0
                        )
                    }.sortedBy { it.date }
                    if (cont.isActive) cont.resume(days)
                }
                override fun onCancelled(error: DatabaseError) {
                    if (cont.isActive) cont.resume(null)
                }
            }
            ref.addListenerForSingleValueEvent(listener)
            cont.invokeOnCancellation { ref.removeEventListener(listener) }
        }
    }

    // ---------- Firestore: farm_data/stats ----------
    // Path is farm_data (collection) -> stats (document), matching your console screenshot.

    suspend fun fetchFarmStats(): FarmStats? = withTimeoutOrNull(4000) {
        suspendCancellableCoroutine<FarmStats?> { cont ->
            FirebaseFirestore.getInstance()
                .collection("farm_data")
                .document("stats")
                .get()
                .addOnSuccessListener { doc ->
                    if (cont.isActive) {
                        if (doc.exists()) {
                            cont.resume(
                                FarmStats(
                                    totalBirds = doc.getLong("totalBirds")?.toInt(),
                                    activeCages = doc.getLong("activeCages")?.toInt()
                                )
                            )
                        } else {
                            cont.resume(null)
                        }
                    }
                }
                .addOnFailureListener {
                    if (cont.isActive) cont.resume(null)
                }
        }
    }

    // ---------- Summary building ----------

    private fun pct(part: Int, whole: Int) =
        if (whole > 0) String.format(Locale.US, "%.1f%%", part * 100.0 / whole) else "n/a"

    private fun stats(label: String, list: List<EggDay>): String {
        val total = list.sumOf { it.total }
        val a = list.sumOf { it.a }
        val b = list.sumOf { it.b }
        val c = list.sumOf { it.c }
        val active = list.count { it.total > 0 }
        val avg = if (active > 0) String.format(Locale.US, "%.1f", total.toDouble() / active) else "n/a"
        return "$label: $total quail eggs over $active recorded days (avg $avg/day). " +
                "Grade A ${pct(a, total)} ($a), Grade B ${pct(b, total)} ($b), Grade C ${pct(c, total)} ($c)."
    }

    /**
     * Builds the text block fed to the AI as hidden context. Combines egg_collections
     * (Realtime Database) with farm_data/stats (Firestore: totalBirds, activeCages) so the
     * model can reason about laying rate and cage load, not just raw egg counts.
     */
    fun buildSummary(days: List<EggDay>, farmStats: FarmStats?): String {
        val today = dateMinus(0)
        val sb = StringBuilder()

        if (days.isEmpty()) {
            sb.appendLine("QUAIL FARM DATA: No egg collection records exist yet.")
        } else {
            val last7 = days.filter { it.date >= dateMinus(6) && it.date <= today }
            val prev7 = days.filter { it.date >= dateMinus(13) && it.date <= dateMinus(7) }
            val last30 = days.filter { it.date >= dateMinus(29) && it.date <= today }

            sb.appendLine("QUAIL FARM DATA (live, today is $today):")
            sb.appendLine(stats("Last 7 days", last7))
            sb.appendLine(stats("Previous 7 days", prev7))
            sb.appendLine(stats("Last 30 days", last30))
            sb.appendLine(stats("All time", days))

            val t1 = last7.sumOf { it.total }
            val t0 = prev7.sumOf { it.total }
            if (t0 > 0) {
                val change = (t1 - t0) * 100.0 / t0
                sb.appendLine(String.format(Locale.US, "Week-over-week production change: %+.1f%%", change))
            }

            last30.filter { it.total > 0 }.let { active ->
                active.maxByOrNull { it.total }?.let { sb.appendLine("Best day (30d): ${it.date} with ${it.total} eggs") }
                active.minByOrNull { it.total }?.let { sb.appendLine("Lowest day (30d): ${it.date} with ${it.total} eggs") }
            }

            sb.appendLine("Daily log, last 30 days:")
            val byDate = days.associateBy { it.date }
            for (i in 29 downTo 0) {
                val d = dateMinus(i)
                val row = byDate[d]
                sb.appendLine(
                    if (row != null) "  $d: total ${row.total} (A ${row.a}, B ${row.b}, C ${row.c})"
                    else "  $d: no record"
                )
            }
        }

        // ---- Flock info from farm_data/stats ----
        sb.appendLine()
        if (farmStats == null || (farmStats.totalBirds == null && farmStats.activeCages == null)) {
            sb.appendLine("FLOCK INFO: Not available. Do not estimate a laying rate — ask the user " +
                    "for their current bird count if it's needed to answer the question.")
        } else {
            sb.appendLine("FLOCK INFO:")
            val birds = farmStats.totalBirds
            val cages = farmStats.activeCages

            if (birds != null) {
                sb.appendLine("  Total birds (quail): $birds " +
                        "(owner-entered in the app profile — may be stale if birds died, were sold, or added recently)")
            } else {
                sb.appendLine("  Total birds: not set")
            }
            if (cages != null) {
                sb.appendLine("  Active cages: $cages")
            } else {
                sb.appendLine("  Active cages: not set")
            }
            if (birds != null && cages != null && cages > 0) {
                sb.appendLine("  Birds per cage: ${String.format(Locale.US, "%.1f", birds.toDouble() / cages)}")
            }

            // Laying rate off today's and last-7-days totals, with a sanity check.
            if (birds != null && birds > 0 && days.isNotEmpty()) {
                val todayRow = days.find { it.date == today }
                if (todayRow != null) {
                    val rate = todayRow.total * 100.0 / birds
                    sb.appendLine("  Today's laying rate: ${String.format(Locale.US, "%.1f%%", rate)} " +
                            "(${todayRow.total} eggs / $birds birds)")
                    if (rate > 100.0) {
                        sb.appendLine("  WARNING: laying rate exceeds 100%, which isn't biologically possible " +
                                "for quail (max ~1 egg/hen/day). The bird count is likely outdated or too low — " +
                                "flag this to the user instead of treating the rate as accurate.")
                    }
                }
                val last7 = days.filter { it.date >= dateMinus(6) && it.date <= today }
                val activeDays7 = last7.count { it.total > 0 }
                if (activeDays7 > 0) {
                    val avgRate = last7.sumOf { it.total } / activeDays7.toDouble() / birds * 100.0
                    sb.appendLine("  7-day average laying rate: ${String.format(Locale.US, "%.1f%%", avgRate)}")
                }
            }
        }

        return sb.toString()
    }
    data class PeriodStats(
        val label: String, val start: String, val end: String,
        val total: Int, val a: Int, val b: Int, val c: Int, val daysWithData: Int
    )

    fun computePeriodStats(days: List<EggDay>, period: QueryPeriod): PeriodStats {
        val matched = days.filter { it.date in period.start..period.end }
        return PeriodStats(
            label = period.label, start = period.start, end = period.end,
            total = matched.sumOf { it.total },
            a = matched.sumOf { it.a }, b = matched.sumOf { it.b }, c = matched.sumOf { it.c },
            daysWithData = matched.count { it.total > 0 }
        )
    }
}