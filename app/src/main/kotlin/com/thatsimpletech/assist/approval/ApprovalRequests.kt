package com.thatsimpletech.assist.approval

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Pending approvals by id. The overlay, the notification and the receiver all resolve into here. */
object ApprovalRequests {
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<Boolean>>()
    private val ids = AtomicLong(1)

    fun open(): Pair<Long, CompletableDeferred<Boolean>> {
        val id = ids.getAndIncrement()
        val d = CompletableDeferred<Boolean>()
        pending[id] = d
        return id to d
    }

    /** First answer wins; later answers for the same id are ignored. */
    fun resolve(id: Long, approved: Boolean): Boolean {
        val d = pending.remove(id) ?: return false
        return d.complete(approved)
    }

    fun close(id: Long) {
        pending.remove(id)?.let { if (it.isActive) it.complete(false) }
    }
}
