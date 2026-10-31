package com.subtrack.sync

/** Vector clocks never compare wall-clock timestamps. Concurrent values remain separate heads. */
data class CausalVersion(val id: String, val clock: Map<String, Long>, val deleted: Boolean) {
    init {
        require(id.matches(Regex("[A-Za-z0-9_-]{16,128}")))
        require(clock.size in 1..128 && clock.all { (device, counter) ->
            device.matches(Regex("[A-Za-z0-9_-]{1,128}")) && counter in 1..Long.MAX_VALUE - 1
        })
    }
}

enum class CausalRelation { BEFORE, AFTER, EQUAL, CONCURRENT }

object Causality {
    fun compare(a: Map<String, Long>, b: Map<String, Long>): CausalRelation {
        var less = false; var greater = false
        for (id in a.keys + b.keys) {
            val av = a[id] ?: 0L; val bv = b[id] ?: 0L
            if (av < bv) less = true
            if (av > bv) greater = true
        }
        return when {
            less && greater -> CausalRelation.CONCURRENT
            less -> CausalRelation.BEFORE
            greater -> CausalRelation.AFTER
            else -> CausalRelation.EQUAL
        }
    }

    /** Reject equal clocks with different IDs and resurrection that did not explicitly resolve a tombstone. */
    fun integrate(heads: List<CausalVersion>, incoming: CausalVersion, resolves: Set<String> = emptySet()): List<CausalVersion> {
        require(heads.map { it.id }.distinct().size == heads.size)
        val known = heads.firstOrNull { it.id == incoming.id }
        if (known != null) { require(known == incoming); return heads }
        val relations = heads.associateWith { compare(it.clock, incoming.clock) }
        require(relations.values.none { it == CausalRelation.EQUAL }) { "Different operations cannot share a causal clock" }
        if (relations.values.any { it == CausalRelation.AFTER }) return heads
        val covered = relations.filterValues { it == CausalRelation.BEFORE }.keys
        // A resolution must causally cover every cited version it *knows about*; citations of versions this device has
        // not received yet are vacuously satisfied (they will be ignored as older-than-resolution when they arrive).
        val knownIds = heads.map { it.id }.toSet()
        require(resolves.all { id -> covered.any { it.id == id } || id !in knownIds }) {
            "Resolution must causally cover every cited version"
        }
        val protectedDeletes = covered.filter { it.deleted && !incoming.deleted && it.id !in resolves }
        val unresolved = if (covered.size > 1) covered.filter { it.id !in resolves } else emptyList()
        return (heads - covered.toSet() + protectedDeletes + unresolved + incoming).distinctBy { it.id }.sortedBy { it.id }
    }

    fun nextClock(heads: List<CausalVersion>, device: String): Map<String, Long> {
        require(device.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        val result = mutableMapOf<String, Long>()
        heads.forEach { h -> h.clock.forEach { (id, n) -> result[id] = maxOf(result[id] ?: 0, n) } }
        val current = result[device] ?: 0
        require(current < Long.MAX_VALUE - 1 && (device in result || result.size < 128))
        result[device] = current + 1
        return result.toSortedMap()
    }
}
