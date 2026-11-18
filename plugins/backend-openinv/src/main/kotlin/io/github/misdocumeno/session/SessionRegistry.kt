package io.github.misdocumeno.session

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** One open session per viewer (the newest wins). */
class SessionRegistry {

    private val byViewer = ConcurrentHashMap<UUID, OpenInvSession>()

    fun find(viewer: UUID): OpenInvSession? = byViewer[viewer]

    fun add(session: OpenInvSession) {
        byViewer.put(session.viewer, session)?.finish(closeView = false)
    }

    fun remove(session: OpenInvSession) {
        byViewer.remove(session.viewer, session)
    }

    fun closeAll() {
        byViewer.values.toList().forEach { it.finish() }
        byViewer.clear()
    }
}
