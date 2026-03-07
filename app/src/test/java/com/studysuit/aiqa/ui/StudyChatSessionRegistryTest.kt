package com.studysuit.aiqa.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyChatSessionRegistryTest {

  @Test
  fun put_and_touch_moves_session_to_front() {
    val registry = SessionRegistry()
    val sessionA = buildSession("a", updatedAt = 10L)
    val sessionB = buildSession("b", updatedAt = 20L)

    registry.put(sessionA, moveToFront = false)
    registry.put(sessionB, moveToFront = false)
    registry.put(sessionA.copy(updatedAt = 30L), moveToFront = true)

    val ordered = registry.orderedSessions()
    assertEquals(listOf("a", "b"), ordered.map { session -> session.id })
    assertEquals(30L, ordered.first().updatedAt)
  }

  @Test
  fun replaceAll_resets_registry_content() {
    val registry = SessionRegistry()
    registry.put(buildSession("old", updatedAt = 1L), moveToFront = false)

    registry.replaceAll(listOf(buildSession("x", updatedAt = 11L), buildSession("y", updatedAt = 12L)))

    assertFalse(registry.contains("old"))
    assertEquals(listOf("x", "y"), registry.orderedSessions().map { session -> session.id })
  }

  @Test
  fun remove_and_clear_update_empty_state() {
    val registry = SessionRegistry()
    registry.put(buildSession("a", updatedAt = 1L), moveToFront = false)
    registry.put(buildSession("b", updatedAt = 2L), moveToFront = false)

    registry.remove("a")
    assertFalse(registry.contains("a"))
    assertEquals("b", registry.firstIdOrNull())

    registry.clear()
    assertTrue(registry.isEmpty())
    assertEquals(null, registry.firstIdOrNull())
  }

  @Test
  fun createdAtOf_returnsValueForExistingSession() {
    val registry = SessionRegistry()
    registry.put(buildSession("a", updatedAt = 100L), moveToFront = false)

    assertEquals(100L, registry.createdAtOf("a"))
    assertEquals(null, registry.createdAtOf("missing"))
  }

  private fun buildSession(id: String, updatedAt: Long): StoredSession {
    return StoredSession(
      id = id,
      title = "session-$id",
      createdAt = updatedAt,
      updatedAt = updatedAt,
      messages = emptyList(),
      histories = emptyMap(),
      profile = ProfileState(level = "L"),
      input = "",
      activePage = WorkspacePage.CHAT,
      knowledgePoints = emptyMap(),
      ankiCards = emptyList()
    )
  }
}
