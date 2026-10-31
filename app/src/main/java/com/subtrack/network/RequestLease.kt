package com.subtrack.network

import kotlinx.coroutines.Job

/** A configuration owns its requests and results. Revoked leases cannot publish late responses. */
class RequestLease {
    private var generation = 0L
    private var closed = false
    var job: Job? = null
        private set
    @Synchronized fun ticket(): Long { check(!closed); return generation }
    @Synchronized fun valid(ticket: Long): Boolean = !closed && generation == ticket
    @Synchronized fun attach(value: Job) { if (closed) value.cancel() else { job?.cancel(); job = value } }
    @Synchronized fun invalidate() { generation++; job?.cancel(); job = null }
    @Synchronized fun close() { invalidate(); closed = true }
}
