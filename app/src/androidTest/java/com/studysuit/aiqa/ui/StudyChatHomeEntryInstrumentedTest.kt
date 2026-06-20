package com.studysuit.aiqa.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.studysuit.aiqa.TestComposeActivity
import com.studysuit.aiqa.ui.theme.StudySuitTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class StudyChatHomeEntryInstrumentedTest {

  @get:Rule
  val composeRule = createAndroidComposeRule<TestComposeActivity>()

  @Test
  fun homeMistakeBookEntryOpensMistakeBook() {
    var opened = false

    composeRule.setContent {
      StudySuitTheme {
        HomeMistakeBookEntry(
          totalCount = 12,
          dueCount = 3,
          onOpenMistakeBook = { opened = true }
        )
      }
    }

    composeRule.onNodeWithText("错题本").assertIsDisplayed()
    composeRule.onNodeWithText("今日待复习 3 题").assertIsDisplayed()
    composeRule.onNodeWithText("进入错题本").performClick()

    composeRule.runOnIdle {
      assertTrue(opened)
    }
  }
}
