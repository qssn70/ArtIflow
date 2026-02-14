package com.studysuit.aiqa.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AnkiDeckArchiveScreen(
  decks: List<AnkiDeckSummary>,
  cards: List<AnkiCard>,
  onClose: () -> Unit,
  onRenameDeck: (deckName: String, newDeckName: String) -> Unit,
  onArchiveDeck: (deckName: String) -> Unit
) {
  var selectedDeckName by remember { mutableStateOf<String?>(null) }
  var renameTargetDeck by remember { mutableStateOf<String?>(null) }
  var renameInput by remember { mutableStateOf("") }

  Surface(
    modifier = Modifier.fillMaxSize(),
    color = Color(0xFFF4FAF6)
  ) {
    val activeDeck = selectedDeckName
    if (activeDeck == null) {
      Column(
        modifier = Modifier
          .fillMaxSize()
          .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
              text = "卡组档案",
              style = MaterialTheme.typography.titleMedium,
              color = Color(0xFF235E4E)
            )
            Text(
              text = "瀑布流浏览卡组，点击进入卡片列表",
              style = MaterialTheme.typography.labelSmall,
              color = Color(0xFF5F756D)
            )
          }
          IconButton(onClick = onClose) {
            Icon(
              imageVector = Icons.Rounded.Close,
              contentDescription = "关闭卡组档案",
              tint = Color(0xFF2C6756)
            )
          }
        }

        if (decks.isEmpty()) {
          Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = "暂无卡组", color = Color(0xFF5D7069))
          }
        } else {
          LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Fixed(2),
            verticalItemSpacing = 10.dp,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
          ) {
            items(decks, key = { deck -> deck.name }) { deck ->
              DeckWaterfallCard(
                deck = deck,
                onClick = { selectedDeckName = deck.name }
              )
            }
          }
        }
      }
    } else {
      val deckCards = remember(cards, activeDeck) {
        cards.filter { card -> (normalizeDeckName(card.deckName) ?: DEFAULT_ANKI_DECK_NAME) == activeDeck }
      }

      Column(
        modifier = Modifier
          .fillMaxSize()
          .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { selectedDeckName = null }) {
              Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "返回卡组瀑布流",
                tint = Color(0xFF2C6756)
              )
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
              Text(
                text = activeDeck,
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFF235E4E)
              )
              Text(
                text = "共 ${deckCards.size} 张卡片",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF5F756D)
              )
            }
          }

          if (activeDeck != DEFAULT_ANKI_DECK_NAME) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
              TextButton(onClick = {
                renameTargetDeck = activeDeck
                renameInput = activeDeck
              }) {
                Text(text = "重命名", style = MaterialTheme.typography.labelSmall)
              }
              TextButton(onClick = {
                onArchiveDeck(activeDeck)
                selectedDeckName = null
              }) {
                Text(text = "归档", style = MaterialTheme.typography.labelSmall, color = Color(0xFF8E4D4D))
              }
            }
          }
        }

        if (deckCards.isEmpty()) {
          Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = "该卡组暂无卡片", color = Color(0xFF5D7069))
          }
        } else {
          LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            items(deckCards, key = { card -> card.id }) { card ->
              DeckCardListItem(card = card)
            }
          }
        }
      }
    }
  }

  val activeRenameDeck = renameTargetDeck
  if (activeRenameDeck != null) {
    AlertDialog(
      onDismissRequest = { renameTargetDeck = null },
      containerColor = Color(0xFFF6FBF7),
      shape = RoundedCornerShape(16.dp),
      title = {
        Text(
          text = "重命名卡组",
          style = MaterialTheme.typography.titleMedium,
          color = Color(0xFF255E4D)
        )
      },
      text = {
        OutlinedTextField(
          value = renameInput,
          onValueChange = { value -> renameInput = value },
          label = { Text("新卡组名", style = MaterialTheme.typography.labelSmall) },
          modifier = Modifier.fillMaxWidth(),
          minLines = 1,
          maxLines = 1,
          shape = RoundedCornerShape(10.dp),
          textStyle = MaterialTheme.typography.bodySmall
        )
      },
      confirmButton = {
        TextButton(onClick = {
          val normalized = normalizeDeckName(renameInput)
          if (!normalized.isNullOrBlank()) {
            onRenameDeck(activeRenameDeck, normalized)
            selectedDeckName = normalized
          }
          renameTargetDeck = null
        }) {
          Text(text = "保存", color = Color(0xFF2D6F5D))
        }
      },
      dismissButton = {
        TextButton(onClick = { renameTargetDeck = null }) {
          Text(text = "取消", color = Color(0xFF4A665C))
        }
      }
    )
  }
}

@Composable
private fun DeckWaterfallCard(
  deck: AnkiDeckSummary,
  onClick: () -> Unit
) {
  Surface(
    color = Color(0xFFFBFEFC),
    shape = RoundedCornerShape(14.dp),
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .border(1.dp, Color(0x1F3A5A4F), RoundedCornerShape(14.dp))
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
      Text(
        text = deck.name,
        style = MaterialTheme.typography.bodyMedium,
        color = Color(0xFF2B4A41),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
      Text(
        text = "${deck.cardCount} 张卡片",
        style = MaterialTheme.typography.labelSmall,
        color = Color(0xFF5F756D)
      )
      Text(
        text = "生疏 ${deck.needsWorkCount} · 熟练 ${deck.proficientCount}",
        style = MaterialTheme.typography.labelSmall,
        color = Color(0xFF5F756D)
      )
      if (deck.topTags.isNotEmpty()) {
        Text(
          text = "标签：${deck.topTags.joinToString(separator = " · ")}",
          style = MaterialTheme.typography.labelSmall,
          color = Color(0xFF688179)
        )
      }
    }
  }
}

@Composable
private fun DeckCardListItem(card: AnkiCard) {
  Surface(
    color = Color(0xFFFBFEFC),
    shape = RoundedCornerShape(12.dp),
    modifier = Modifier
      .fillMaxWidth()
      .border(1.dp, Color(0x1F3A5A4F), RoundedCornerShape(12.dp))
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
      Text(
        text = card.front,
        style = MaterialTheme.typography.bodySmall,
        color = Color(0xFF2F433C),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
      )
      Text(
        text = card.back,
        style = MaterialTheme.typography.labelSmall,
        color = Color(0xFF5D726A),
        maxLines = 3,
        overflow = TextOverflow.Ellipsis
      )
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = if (card.tags.isEmpty()) "无标签" else card.tags.joinToString(separator = " · ").take(24),
          style = MaterialTheme.typography.labelSmall,
          color = Color(0xFF6A8179),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f)
        )
        Text(
          text = card.mastery.label,
          style = MaterialTheme.typography.labelSmall,
          color = Color(0xFF53756A),
          modifier = Modifier.padding(start = 8.dp)
        )
      }
    }
  }
}
