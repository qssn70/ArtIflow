package com.studysuit.aiqa.ui

import com.studysuit.aiqa.data.MistakeBookItem

internal fun savedQuestionToMistakeBookItem(
  saved: SavedQuestion,
  id: String,
  imageRefs: List<String>,
  now: Long = System.currentTimeMillis()
): MistakeBookItem {
  return MistakeBookItem.create(
    id = id,
    question = saved.question,
    correctAnswer = saved.answer,
    imageRefs = imageRefs,
    subject = saved.subject,
    questionType = saved.questionType,
    knowledgeTags = saved.knowledgeTags,
    studentAnswer = "",
    explanation = saved.analysisSummary,
    createdAt = now,
    sourceSavedQuestionId = saved.id
  )
}
