package de.lenox.lightoverlay

/**
 * Picks torch positions that light up all dark floor (a set cover), growing out from the player.
 *
 * Positions from the previous plan are kept first, as long as enough of what they light is still dark, so the
 * suggestions stay where they are while the player moves. Then the dark floor is walked from the player outwards:
 * each target that is still dark gets the position that covers it and lights the most other dark floor. That way
 * the first new torch lights up the floor the player stands on (or the closest dark floor), the next ones continue
 * from there, and no dark gaps are left between them, however small.
 */
object TorchOptimizer {
    /**
     * @param covers ids of the dark targets the torch would light up
     * @param penalty subtracted from the gain, breaks ties in favour of positions close to the player
     * @param pickedGain for kept positions, the gain they had when they were picked
     */
    class Candidate(val cell: Int, val covers: IntArray, val penalty: Float, val pickedGain: Float = 0f) {
        /** The (weighted) dark floor this position lit up that no position before it did, set when it is chosen. */
        var gain = 0f
            internal set
    }

    /** A position needs to light at least this much (weighted) dark floor to be kept. */
    private const val MIN_GAIN = 1f

    /**
     * A kept position is dropped when it lights less than this share of the dark floor it did when it was picked,
     * e.g. because a torch was placed a block off from it. The floor around it is then planned again.
     */
    private const val KEEP_SHARE = 0.25f

    /**
     * @param kept positions of the previous plan, oldest first; older ones win where they overlap
     * @param candidates all positions a new torch could go
     * @param order the targets that have to be lit, closest to the player first
     * @param maxPicks how many new positions to add at most
     * @return the kept positions that are still useful, then the new ones
     */
    fun plan(
        kept: List<Candidate>,
        candidates: List<Candidate>,
        weights: FloatArray,
        order: IntArray,
        maxPicks: Int
    ): List<Candidate> {
        val covered = BooleanArray(weights.size)
        val chosen = ArrayList<Candidate>()
        fun pick(candidate: Candidate) {
            candidate.gain = gain(candidate, weights, covered)
            chosen.add(candidate)
            for (target in candidate.covers) covered[target] = true
        }

        for (candidate in kept) {
            val gain = gain(candidate, weights, covered)
            if (gain >= MIN_GAIN && gain >= candidate.pickedGain * KEEP_SHARE) pick(candidate)
        }

        val coveringTarget = CoverIndex(candidates, weights.size)
        var picks = 0
        for (target in order) {
            if (covered[target]) continue
            if (picks == maxPicks) break
            var best: Candidate? = null
            var bestScore = Float.NEGATIVE_INFINITY
            coveringTarget.forEach(target) { candidate ->
                val score = gain(candidate, weights, covered) - candidate.penalty
                if (score > bestScore) {
                    best = candidate
                    bestScore = score
                }
            }
            pick(best ?: continue)
            picks++
        }
        return chosen
    }

    private fun gain(candidate: Candidate, weights: FloatArray, covered: BooleanArray): Float {
        var sum = 0f
        for (target in candidate.covers) if (!covered[target]) sum += weights[target]
        return sum
    }

    /** The candidates that cover each target, as one flat array with an offset per target. */
    private class CoverIndex(val candidates: List<Candidate>, targetCount: Int) {
        val start = IntArray(targetCount + 1)
        val entries: IntArray

        init {
            for (candidate in candidates) for (target in candidate.covers) start[target + 1]++
            for (target in 0 until targetCount) start[target + 1] += start[target]
            entries = IntArray(start[targetCount])
            val fill = start.copyOf(targetCount)
            candidates.forEachIndexed { i, candidate ->
                for (target in candidate.covers) entries[fill[target]++] = i
            }
        }

        inline fun forEach(target: Int, action: (Candidate) -> Unit) {
            for (k in start[target] until start[target + 1]) action(candidates[entries[k]])
        }
    }
}
