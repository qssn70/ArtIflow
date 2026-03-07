package com.studysuit.aiqa.ui

internal class SessionRegistry {
  private val sessionsById = linkedMapOf<String, StoredSession>()
  private val sessionOrder = mutableListOf<String>()

  fun get(id: String): StoredSession? {
    return sessionsById[id]
  }

  fun put(session: StoredSession, moveToFront: Boolean) {
    sessionsById[session.id] = session
    if (moveToFront) {
      touch(session.id)
    } else if (!sessionOrder.contains(session.id)) {
      sessionOrder += session.id
    }
  }

  fun replaceAll(sessions: List<StoredSession>) {
    clear()
    sessions.forEach { session ->
      put(session, moveToFront = false)
    }
  }

  fun remove(id: String): StoredSession? {
    sessionOrder.remove(id)
    return sessionsById.remove(id)
  }

  fun clear() {
    sessionsById.clear()
    sessionOrder.clear()
  }

  fun touch(id: String) {
    sessionOrder.remove(id)
    sessionOrder.add(0, id)
  }

  fun isEmpty(): Boolean {
    return sessionsById.isEmpty()
  }

  fun contains(id: String): Boolean {
    return sessionsById.containsKey(id)
  }

  fun firstIdOrNull(): String? {
    return sessionOrder.firstOrNull() ?: sessionsById.keys.firstOrNull()
  }

  fun createdAtOf(id: String): Long? {
    return sessionsById[id]?.createdAt
  }

  fun orderedSessions(): List<StoredSession> {
    return sessionOrder.mapNotNull { id -> sessionsById[id] }
  }
}
