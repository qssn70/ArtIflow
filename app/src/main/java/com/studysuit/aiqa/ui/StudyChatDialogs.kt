package com.studysuit.aiqa.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.studysuit.aiqa.data.DEFAULT_OBSIDIAN_MISTAKE_FOLDER
import com.studysuit.aiqa.data.MistakeBookStorageLocation
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

@Composable
internal fun SettingsDialog(
  settings: RuntimeSettings,
  currentSettings: RuntimeSettings,
  isFollowupTreeExportEnabled: Boolean,
  onDismiss: () -> Unit,
  onSave: () -> Unit,
  onSaveWithMigrationChoice: (Boolean) -> Unit,
  onReset: () -> Unit,
  onResetMainThread: () -> Unit,
  onExportFollowupTree: () -> Unit,
  onChooseObsidianVault: () -> Unit,
  onPairFlowStudy: (String) -> Unit,
  onPushSessionsToFlowStudy: (Int?) -> Unit,
  onSettingsChanged: (RuntimeSettings) -> Unit
) {
  val scrollState = rememberScrollState()
  var flowStudyPairCode by remember { mutableStateOf("") }
  var modelPresetName by remember { mutableStateOf("") }
  var isMistakeBookMigrationChoiceOpen by remember { mutableStateOf(false) }
  val isObsidianVaultAuthorized = settings.obsidianVaultTreeUri.isNotBlank()
  val needsMistakeBookMigrationChoice = settings.requiresMistakeBookMigrationChoiceFrom(currentSettings)

  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = Color(0xFFF6FBF7),
    shape = RoundedCornerShape(18.dp),
    title = {
      Text(
        text = "设置",
        style = MaterialTheme.typography.titleMedium,
        color = Color(0xFF255E4D)
      )
    },
    text = {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(max = 520.dp)
          .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        Text(text = "当前模型", style = MaterialTheme.typography.labelMedium, color = Color(0xFF4C635B))
        Surface(
          color = Color(0xFFF0F7F3),
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0x1433564B), RoundedCornerShape(12.dp))
        ) {
          Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
          ) {
            Text(
              text = settings.currentModelDisplayName(),
              style = MaterialTheme.typography.labelMedium,
              color = Color(0xFF2C6251)
            )
            Text(
              text = settings.currentModelDisplayHint().ifBlank { "使用应用内默认配置" },
              style = MaterialTheme.typography.labelSmall,
              color = Color(0xFF6D8079)
            )
          }
        }

        Text(text = "快捷模板", style = MaterialTheme.typography.labelMedium, color = Color(0xFF4C635B))
        ModelPresetActionRow(
          title = "系统豆包",
          summary = "清空自定义模型，直接回到内置 Ark/豆包 配置",
          actionLabel = "启用",
          onAction = { onSettingsChanged(settings.clearCustomModel()) }
        )
        builtinModelPresetTemplates().forEach { preset ->
          ModelPresetActionRow(
            title = preset.name,
            summary = "${preset.baseUrl} · ${preset.modelName}",
            actionLabel = "套用",
            onAction = { onSettingsChanged(settings.applyModelPreset(preset)) }
          )
        }

        HorizontalDivider(color = Color(0x1633564B))

        Text(text = "自定义模型（只填三项）", style = MaterialTheme.typography.labelMedium, color = Color(0xFF4C635B))
        SettingsTextField(
          label = "BASEURL",
          value = settings.customModelBaseUrl,
          onValueChange = { value -> onSettingsChanged(settings.copy(customModelBaseUrl = value)) }
        )
        SettingsTextField(
          label = "APIKEY",
          value = settings.customModelApiKey,
          onValueChange = { value -> onSettingsChanged(settings.copy(customModelApiKey = value)) }
        )
        SettingsTextField(
          label = "MODELNAME",
          value = settings.customModelName,
          onValueChange = { value -> onSettingsChanged(settings.copy(customModelName = value)) }
        )
        Text(
          text = "当 BASEURL / APIKEY / MODELNAME 三项都填写时，将优先使用该自定义模型。设置保存在本机，安装后可直接在这里修改。自建 IP 接口如果没有正式证书，直接填 http://IP:端口/v1，不要填 https://IP。",
          style = MaterialTheme.typography.labelSmall,
          color = Color(0xFF728880)
        )

        OutlinedTextField(
          value = modelPresetName,
          onValueChange = { modelPresetName = it },
          label = { Text("把当前模型存为预设", style = MaterialTheme.typography.labelSmall) },
          modifier = Modifier.fillMaxWidth(),
          minLines = 1,
          maxLines = 1,
          shape = RoundedCornerShape(10.dp),
          textStyle = MaterialTheme.typography.bodySmall
        )
        Button(
          onClick = {
            modelPresetName = modelPresetName.trim()
            onSettingsChanged(settings.saveCurrentModelPreset(modelPresetName))
            modelPresetName = ""
          },
          enabled = settings.hasCompleteCustomModel() && modelPresetName.trim().isNotBlank(),
          shape = RoundedCornerShape(10.dp),
          modifier = Modifier.fillMaxWidth()
        ) {
          Text(text = "保存为模型预设")
        }

        if (settings.customModelPresets.isEmpty()) {
          Text(
            text = "还没有保存的模型预设。填好上面的三项后，可以长期保存多套模型。",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF728880)
          )
        } else {
          Text(text = "已存模型预设", style = MaterialTheme.typography.labelMedium, color = Color(0xFF4C635B))
          settings.customModelPresets.forEach { preset ->
            ModelPresetActionRow(
              title = preset.name,
              summary = "${preset.baseUrl} · ${preset.modelName}",
              actionLabel = "启用",
              onAction = { onSettingsChanged(settings.applyModelPreset(preset)) },
              secondaryLabel = "删除",
              onSecondaryAction = { onSettingsChanged(settings.removeModelPreset(preset.id)) }
            )
          }
        }

        HorizontalDivider(color = Color(0x1633564B))

        Text(text = "错题识别模型", style = MaterialTheme.typography.labelMedium, color = Color(0xFF4C635B))
        MistakeRecognitionModelCountSelector(
          count = settings.mistakeRecognitionModelCount.coerceIn(1, 3),
          onCountChanged = { count -> onSettingsChanged(settings.copy(mistakeRecognitionModelCount = count)) }
        )
        Text(
          text = "OCR+1 使用主多模态；OCR+2 增加融合裁判；OCR+3 再加入第二多模态。",
          style = MaterialTheme.typography.labelSmall,
          color = Color(0xFF728880)
        )
        SettingsTextField(
          label = "第二多模态 BASEURL",
          value = settings.mistakeSecondModelBaseUrl,
          onValueChange = { value -> onSettingsChanged(settings.copy(mistakeSecondModelBaseUrl = value)) }
        )
        SettingsTextField(
          label = "第二多模态 APIKEY",
          value = settings.mistakeSecondModelApiKey,
          onValueChange = { value -> onSettingsChanged(settings.copy(mistakeSecondModelApiKey = value)) }
        )
        SettingsTextField(
          label = "第二多模态 MODELNAME",
          value = settings.mistakeSecondModelName,
          onValueChange = { value -> onSettingsChanged(settings.copy(mistakeSecondModelName = value)) }
        )
        SettingsTextField(
          label = "融合模型 BASEURL",
          value = settings.mistakeFusionModelBaseUrl,
          onValueChange = { value -> onSettingsChanged(settings.copy(mistakeFusionModelBaseUrl = value)) }
        )
        SettingsTextField(
          label = "融合模型 APIKEY",
          value = settings.mistakeFusionModelApiKey,
          onValueChange = { value -> onSettingsChanged(settings.copy(mistakeFusionModelApiKey = value)) }
        )
        SettingsTextField(
          label = "融合模型 MODELNAME",
          value = settings.mistakeFusionModelName,
          onValueChange = { value -> onSettingsChanged(settings.copy(mistakeFusionModelName = value)) }
        )

        HorizontalDivider(color = Color(0x1633564B))

        Text(text = "错题本存储", style = MaterialTheme.typography.labelMedium, color = Color(0xFF4C635B))
        MistakeBookStorageLocationSelector(
          selected = settings.mistakeBookStorageLocation,
          onSelected = { location -> onSettingsChanged(settings.copy(mistakeBookStorageLocation = location)) }
        )
        Text(
          text = "默认保存到 Obsidian；未授权仓库前会先保存在本软件。",
          style = MaterialTheme.typography.labelSmall,
          color = Color(0xFF728880)
        )
        Surface(
          color = Color(0xFFF0F7F3),
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0x1433564B), RoundedCornerShape(12.dp))
        ) {
          Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
              ) {
                Text(
                  text = if (isObsidianVaultAuthorized) "Obsidian 仓库已授权" else "Obsidian 仓库未授权",
                  style = MaterialTheme.typography.labelMedium,
                  color = if (isObsidianVaultAuthorized) Color(0xFF2C6251) else Color(0xFF8A5A24)
                )
                Text(
                  text = settings.obsidianVaultTreeUri.ifBlank { "还没有选择仓库目录" },
                  style = MaterialTheme.typography.labelSmall,
                  color = Color(0xFF6D8079),
                  maxLines = 2,
                  overflow = TextOverflow.Ellipsis
                )
              }
              TextButton(onClick = onChooseObsidianVault) {
                Text(text = "选择 Obsidian 仓库")
              }
            }
            Text(
              text = "Obsidian 错题目录：${settings.obsidianMistakeFolder.ifBlank { DEFAULT_OBSIDIAN_MISTAKE_FOLDER }}",
              style = MaterialTheme.typography.labelSmall,
              color = Color(0xFF4C635B)
            )
          }
        }
        SettingsTextField(
          label = "Obsidian 错题目录",
          value = settings.obsidianMistakeFolder,
          onValueChange = { value -> onSettingsChanged(settings.copy(obsidianMistakeFolder = value)) }
        )

        HorizontalDivider(color = Color(0x1633564B))

        Text(text = "Ark（豆包）", style = MaterialTheme.typography.labelMedium, color = Color(0xFF4C635B))
        Text(
          text = "首次安装后请在这里填写运行参数，保存后立即生效。",
          style = MaterialTheme.typography.labelSmall,
          color = Color(0xFF728880)
        )

        SettingsTextField(
          label = "ARK_API_KEY",
          value = settings.arkApiKey,
          onValueChange = { value -> onSettingsChanged(settings.copy(arkApiKey = value)) }
        )
        SettingsTextField(
          label = "ARK_MODEL",
          value = settings.arkModel,
          onValueChange = { value -> onSettingsChanged(settings.copy(arkModel = value)) }
        )
        SettingsTextField(
          label = "ARK_BASE_URL",
          value = settings.arkBaseUrl,
          onValueChange = { value -> onSettingsChanged(settings.copy(arkBaseUrl = value)) }
        )
        SettingsTextField(
          label = "ARK_ENDPOINT",
          value = settings.arkEndpoint,
          onValueChange = { value -> onSettingsChanged(settings.copy(arkEndpoint = value)) }
        )
        SettingsTextField(
          label = "ARK_SYSTEM_PROMPT",
          value = settings.arkSystemPrompt,
          minLines = 3,
          onValueChange = { value -> onSettingsChanged(settings.copy(arkSystemPrompt = value)) }
        )
        SettingsTextField(
          label = "图片搜题提示词",
          value = settings.imagePrompt,
          minLines = 3,
          onValueChange = { value -> onSettingsChanged(settings.copy(imagePrompt = value)) }
        )

        HorizontalDivider(color = Color(0x1633564B))

        Text(text = "OpenSpeech", style = MaterialTheme.typography.labelMedium, color = Color(0xFF4C635B))
        SettingsTextField(
          label = "OPENSPEECH_API_KEY",
          value = settings.openSpeechApiKey,
          onValueChange = { value -> onSettingsChanged(settings.copy(openSpeechApiKey = value)) }
        )
        SettingsTextField(
          label = "OPENSPEECH_RESOURCE_ID",
          value = settings.openSpeechResourceId,
          onValueChange = { value -> onSettingsChanged(settings.copy(openSpeechResourceId = value)) }
        )
        SettingsTextField(
          label = "OPENSPEECH_SUBMIT_URL",
          value = settings.openSpeechSubmitUrl,
          onValueChange = { value -> onSettingsChanged(settings.copy(openSpeechSubmitUrl = value)) }
        )
        SettingsTextField(
          label = "OPENSPEECH_QUERY_URL",
          value = settings.openSpeechQueryUrl,
          onValueChange = { value -> onSettingsChanged(settings.copy(openSpeechQueryUrl = value)) }
        )
        SettingsTextField(
          label = "OPENSPEECH_UID",
          value = settings.openSpeechUid,
          onValueChange = { value -> onSettingsChanged(settings.copy(openSpeechUid = value)) }
        )

        HorizontalDivider(color = Color(0x1633564B))

        Text(text = "数据", style = MaterialTheme.typography.labelMedium, color = Color(0xFF4C635B))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            text = "导出当前主界面追问图谱（Markdown）",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF3E5A51)
          )
          TextButton(onClick = onExportFollowupTree, enabled = isFollowupTreeExportEnabled) {
            Text(text = "导出文件")
          }
        }
        if (!isFollowupTreeExportEnabled) {
          Text(
            text = "暂无可导出的追问节点",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF728880)
          )
        }

        HorizontalDivider(color = Color(0x1633564B))

        Text(text = "FlowStudy", style = MaterialTheme.typography.labelMedium, color = Color(0xFF4C635B))
        SettingsTextField(
          label = "FlowStudy Server URL (http://<ip>:8000)",
          value = settings.flowStudyServerUrl,
          onValueChange = { value -> onSettingsChanged(settings.copy(flowStudyServerUrl = value)) }
        )
        SettingsTextField(
          label = "FlowStudy Device ID",
          value = settings.flowStudyDeviceId,
          onValueChange = { value -> onSettingsChanged(settings.copy(flowStudyDeviceId = value)) }
        )
        SettingsTextField(
          label = "FlowStudy Device Token",
          value = settings.flowStudyDeviceToken,
          onValueChange = { value -> onSettingsChanged(settings.copy(flowStudyDeviceToken = value)) }
        )

        OutlinedTextField(
          value = flowStudyPairCode,
          onValueChange = { flowStudyPairCode = it },
          label = { Text("配对码", style = MaterialTheme.typography.labelSmall) },
          modifier = Modifier.fillMaxWidth(),
          minLines = 1,
          maxLines = 1,
          shape = RoundedCornerShape(10.dp),
          textStyle = MaterialTheme.typography.bodySmall
        )

        Text(
          text = "当前版本只保留一个主界面，上传时会同步当前主界面的完整快照。",
          style = MaterialTheme.typography.labelSmall,
          color = Color(0xFF728880)
        )

        Button(
          onClick = onResetMainThread,
          shape = RoundedCornerShape(10.dp),
          modifier = Modifier.fillMaxWidth()
        ) {
          Text(text = "清空主界面并新开一题")
        }

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          Button(
            onClick = { onPairFlowStudy(flowStudyPairCode) },
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.weight(1f)
          ) {
            Text(text = "配对")
          }
          Button(
            onClick = { onPushSessionsToFlowStudy(null) },
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.weight(1f)
          ) {
            Text(text = "上传主界面")
          }
        }
      }
    },
    confirmButton = {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onReset) {
          Text(text = "恢复默认", color = Color(0xFF4A665C))
        }
        Button(
          onClick = {
            if (needsMistakeBookMigrationChoice) {
              isMistakeBookMigrationChoiceOpen = true
            } else {
              onSave()
            }
          },
          shape = RoundedCornerShape(10.dp)
        ) {
          Text(text = "保存")
        }
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(text = "取消", color = Color(0xFF4A665C))
      }
    }
  )

  if (isMistakeBookMigrationChoiceOpen) {
    AlertDialog(
      onDismissRequest = { isMistakeBookMigrationChoiceOpen = false },
      containerColor = Color(0xFFF6FBF7),
      shape = RoundedCornerShape(18.dp),
      title = {
        Text(
          text = mistakeBookMigrationChoiceTitle(settings, currentSettings),
          style = MaterialTheme.typography.titleMedium,
          color = Color(0xFF255E4D)
        )
      },
      text = {
        Text(
          text = mistakeBookMigrationChoiceMessage(settings, currentSettings),
          style = MaterialTheme.typography.bodySmall,
          color = Color(0xFF4C635B)
        )
      },
      confirmButton = {
        Button(
          onClick = {
            isMistakeBookMigrationChoiceOpen = false
            onSaveWithMigrationChoice(true)
          },
          shape = RoundedCornerShape(10.dp)
        ) {
          Text(text = "迁移已有错题")
        }
      },
      dismissButton = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          TextButton(
            onClick = {
              isMistakeBookMigrationChoiceOpen = false
              onSaveWithMigrationChoice(false)
            }
          ) {
            Text(text = "仅切换新错题", color = Color(0xFF4A665C))
          }
          TextButton(onClick = { isMistakeBookMigrationChoiceOpen = false }) {
            Text(text = "取消", color = Color(0xFF4A665C))
          }
        }
      }
    )
  }
}


@Composable
private fun MistakeBookStorageLocationSelector(
  selected: MistakeBookStorageLocation,
  onSelected: (MistakeBookStorageLocation) -> Unit
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp)
  ) {
    listOf(
      MistakeBookStorageLocation.OBSIDIAN to "Obsidian",
      MistakeBookStorageLocation.LOCAL to "本软件"
    ).forEach { (location, label) ->
      val isSelected = selected == location
      Surface(
        color = if (isSelected) Color(0xFFDCEFE6) else Color(0xFFF8FCF9),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
          .weight(1f)
          .border(
            width = 1.dp,
            color = if (isSelected) Color(0xFF6A9B86) else Color(0x1F3A5A4F),
            shape = RoundedCornerShape(10.dp)
          )
          .clickable { onSelected(location) }
      ) {
        Text(
          text = label,
          style = MaterialTheme.typography.labelMedium,
          color = if (isSelected) Color(0xFF255E4D) else Color(0xFF60766E),
          textAlign = TextAlign.Center,
          modifier = Modifier.padding(vertical = 8.dp)
        )
      }
    }
  }
}

private fun mistakeBookStorageLocationLabel(location: MistakeBookStorageLocation): String {
  return when (location) {
    MistakeBookStorageLocation.OBSIDIAN -> "Obsidian"
    MistakeBookStorageLocation.LOCAL -> "本软件"
  }
}

private fun mistakeBookMigrationChoiceTitle(settings: RuntimeSettings, currentSettings: RuntimeSettings): String {
  return if (settings.mistakeBookStorageLocation != currentSettings.mistakeBookStorageLocation) {
    "切换错题本存储"
  } else {
    "更新 Obsidian 存储"
  }
}

private fun mistakeBookMigrationChoiceMessage(settings: RuntimeSettings, currentSettings: RuntimeSettings): String {
  return if (settings.mistakeBookStorageLocation != currentSettings.mistakeBookStorageLocation) {
    "将错题本从 ${mistakeBookStorageLocationLabel(currentSettings.mistakeBookStorageLocation)} 切换到 ${mistakeBookStorageLocationLabel(settings.mistakeBookStorageLocation)}。要把已有错题迁移到新位置吗？"
  } else {
    "将 Obsidian 错题位置更新为 ${normalizeObsidianMistakeFolder(settings.obsidianMistakeFolder)}。要把当前可见错题同步到新位置吗？"
  }
}

@Composable
private fun MistakeRecognitionModelCountSelector(
  count: Int,
  onCountChanged: (Int) -> Unit
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp)
  ) {
    listOf(1 to "OCR+1", 2 to "OCR+2", 3 to "OCR+3").forEach { (value, label) ->
      val selected = count == value
      Surface(
        color = if (selected) Color(0xFFDCEFE6) else Color(0xFFF8FCF9),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
          .weight(1f)
          .border(
            width = 1.dp,
            color = if (selected) Color(0xFF6A9B86) else Color(0x1F3A5A4F),
            shape = RoundedCornerShape(10.dp)
          )
          .clickable { onCountChanged(value) }
      ) {
        Text(
          text = label,
          style = MaterialTheme.typography.labelMedium,
          color = if (selected) Color(0xFF255E4D) else Color(0xFF60766E),
          textAlign = TextAlign.Center,
          modifier = Modifier.padding(vertical = 8.dp)
        )
      }
    }
  }
}

@Composable
private fun ModelPresetActionRow(
  title: String,
  summary: String,
  actionLabel: String,
  onAction: () -> Unit,
  secondaryLabel: String? = null,
  onSecondaryAction: (() -> Unit)? = null
) {
  Surface(
    color = Color(0xFFF0F7F3),
    shape = RoundedCornerShape(12.dp),
    modifier = Modifier
      .fillMaxWidth()
      .border(1.dp, Color(0x1433564B), RoundedCornerShape(12.dp))
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(2.dp)
      ) {
        Text(
          text = title,
          style = MaterialTheme.typography.labelMedium,
          color = Color(0xFF2C6251),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
        Text(
          text = summary,
          style = MaterialTheme.typography.labelSmall,
          color = Color(0xFF6D8079),
          maxLines = 2,
          overflow = TextOverflow.Ellipsis
        )
      }
      Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (secondaryLabel != null && onSecondaryAction != null) {
          TextButton(onClick = onSecondaryAction) {
            Text(text = secondaryLabel)
          }
        }
        TextButton(onClick = onAction) {
          Text(text = actionLabel)
        }
      }
    }
  }
}

@Composable
private fun SettingsTextField(
  label: String,
  value: String,
  minLines: Int = 1,
  onValueChange: (String) -> Unit
) {
  OutlinedTextField(
    value = value,
    onValueChange = onValueChange,
    label = { Text(label, style = MaterialTheme.typography.labelSmall) },
    modifier = Modifier.fillMaxWidth(),
    minLines = minLines,
    maxLines = if (minLines == 1) 1 else 8,
    shape = RoundedCornerShape(10.dp),
    textStyle = MaterialTheme.typography.bodySmall
  )
}

@Composable
internal fun DeckManagerDialog(
  decks: List<AnkiDeckSummary>,
  onDismiss: () -> Unit,
  onRenameDeck: (deckName: String, newDeckName: String) -> Unit,
  onArchiveDeck: (deckName: String) -> Unit
) {
  var editingDeck by remember(decks) { mutableStateOf<String?>(null) }
  var renameInput by remember(decks) { mutableStateOf("") }

  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = Color(0xFFF6FBF7),
    shape = RoundedCornerShape(18.dp),
    title = {
      Text(
        text = "卡组管理",
        style = MaterialTheme.typography.titleMedium,
        color = Color(0xFF255E4D)
      )
    },
    text = {
      if (decks.isEmpty()) {
        Text(text = "暂无卡组", color = Color(0xFF5D7069))
      } else {
        LazyColumn(
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 360.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          items(decks, key = { it.name }) { deck ->
            Surface(
              color = Color(0xFFFBFEFC),
              shape = RoundedCornerShape(10.dp),
              modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0x1F3A5A4F), RoundedCornerShape(10.dp))
            ) {
              Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
              ) {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                      text = deck.name,
                      style = MaterialTheme.typography.bodySmall,
                      color = Color(0xFF2F433C),
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis
                    )
                    Text(
                      text = "${deck.cardCount} 张卡片",
                      style = MaterialTheme.typography.labelSmall,
                      color = Color(0xFF668078)
                    )
                  }

                  if (deck.name != DEFAULT_ANKI_DECK_NAME) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                      TextButton(onClick = {
                        editingDeck = deck.name
                        renameInput = deck.name
                      }) {
                        Text(text = "重命名")
                      }
                      TextButton(onClick = { onArchiveDeck(deck.name) }) {
                        Text(text = "归档", color = Color(0xFF8E4D4D))
                      }
                    }
                  }
                }

                if (editingDeck == deck.name) {
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
                  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                      onRenameDeck(deck.name, renameInput)
                      editingDeck = null
                    }) {
                      Text(text = "保存", color = Color(0xFF2D6F5D))
                    }
                    TextButton(onClick = {
                      editingDeck = null
                      renameInput = ""
                    }) {
                      Text(text = "取消", color = Color(0xFF4A665C))
                    }
                  }
                }
              }
            }
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text(text = "关闭", color = Color(0xFF2D6F5D))
      }
    }
  )
}

@Composable
internal fun DeckPracticeSummaryDialog(
  summary: DeckPracticeSummary,
  onRestart: () -> Unit,
  onDismiss: () -> Unit,
  onExit: () -> Unit
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = Color(0xFFF6FBF7),
    shape = RoundedCornerShape(18.dp),
    title = {
      Text(
        text = "专练完成 · ${summary.deckName}",
        style = MaterialTheme.typography.titleMedium,
        color = Color(0xFF255E4D)
      )
    },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          text = "本轮已复习 ${summary.reviewedCards}/${summary.totalCards} 张",
          style = MaterialTheme.typography.bodySmall,
          color = Color(0xFF536A62)
        )
        Text(
          text = "生疏 ${summary.needsWorkCount} · 一般 ${summary.familiarCount} · 熟练 ${summary.proficientCount}",
          style = MaterialTheme.typography.labelSmall,
          color = Color(0xFF5F756D)
        )
      }
    },
    confirmButton = {
      TextButton(onClick = {
        onRestart()
      }) {
        Text(text = "再来一轮", color = Color(0xFF2D6F5D))
      }
    },
    dismissButton = {
      TextButton(onClick = {
        onExit()
      }) {
        Text(text = "返回卡组", color = Color(0xFF4A665C))
      }
    }
  )
}

@Composable
internal fun SpanDetailDialog(
  span: SpanData,
  history: List<SpanDetail>,
  onDismiss: () -> Unit
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = Color(0xFFF6FBF7),
    shape = RoundedCornerShape(18.dp),
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text(text = "关闭", color = Color(0xFF2D6F5D))
      }
    },
    title = {
      Text(
        text = "段落详解",
        style = MaterialTheme.typography.titleMedium,
        color = Color(0xFF255E4D)
      )
    },
    text = {
      LazyColumn(
        modifier = Modifier.heightIn(max = 420.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        item(key = "source-title") {
          Text(
            text = "原始段落",
            style = MaterialTheme.typography.labelMedium,
            color = Color(0xFF4B6058)
          )
        }

        item(key = "source-content") {
          Surface(
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(10.dp))
              .border(1.dp, Color(0x183A5A4F), RoundedCornerShape(10.dp)),
            color = Color(0xFFF4F9F3)
          ) {
            MarkdownFormattedText(
              markdown = span.content,
              textStyle = MaterialTheme.typography.bodySmall,
              textColor = Color(0xFF2F433C),
              textAlign = TextAlign.Start,
              modifier = Modifier.padding(10.dp),
            )
          }
        }

        item(key = "history-title") {
          Text(
            text = "追问/讲解记录",
            style = MaterialTheme.typography.labelMedium,
            color = Color(0xFF4B6058)
          )
        }

        if (history.isEmpty()) {
          item(key = "history-empty") {
            Text(
              text = "这段还没有记录。左滑松手自动讲解，长按松手语音追问，右滑松手看弹窗，右滑停留进精细追问。",
              style = MaterialTheme.typography.bodySmall,
              color = Color(0xFF5D7069)
            )
          }
        } else {
          items(history, key = { it.id }) { detail ->
            Surface(
              color = Color(0xFFFBFEFC),
              shape = RoundedCornerShape(10.dp),
              modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0x1F3A5A4F), RoundedCornerShape(10.dp))
            ) {
              Column(
                modifier = Modifier.padding(9.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
              ) {
                Text(
                  text = "${detail.mode} · ${detail.time}",
                  style = MaterialTheme.typography.labelSmall,
                  color = Color(0xFF67817A)
                )
                detail.question?.takeIf { question -> question.isNotBlank() }?.let { question ->
                  Text(
                    text = "追问",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF5F7A71)
                  )
                  MarkdownFormattedText(
                    markdown = question,
                    textStyle = MaterialTheme.typography.bodySmall,
                    textColor = Color(0xFF355249),
                    textAlign = TextAlign.Start
                  )
                }
                Text(
                  text = if (detail.question.isNullOrBlank()) "讲解" else "回答",
                  style = MaterialTheme.typography.labelSmall,
                  color = Color(0xFF5F7A71)
                )
                MarkdownFormattedText(
                  markdown = detail.answer,
                  textStyle = MaterialTheme.typography.bodySmall,
                  textColor = Color(0xFF2F433C),
                  textAlign = TextAlign.Start
                )
              }
            }
          }
        }
      }
    }
  )
}

@Composable
internal fun FollowupTreeDialog(
  scopes: List<FollowupTreeScope>,
  activeSpanId: String?,
  activeDetailId: String?,
  onDismiss: () -> Unit,
  onOpenBranch: (String, String?) -> Unit
) {
  val minScale = 0.55f
  val maxScale = 2.8f
  var boardScale by remember(scopes) { mutableStateOf(1f) }
  var boardPanOffset by remember(scopes) { mutableStateOf(Offset.Zero) }
  var viewportSizePx by remember(scopes) { mutableStateOf(IntSize.Zero) }
  var hasInitializedViewport by remember(scopes) { mutableStateOf(false) }
  val boardLayout = remember(scopes) {
    buildFollowupTreeBoardLayout(scopes)
  }
  val density = LocalDensity.current
  val boardWidthPx = with(density) { boardLayout.width.dp.toPx() }
  val boardHeightPx = with(density) { boardLayout.height.dp.toPx() }
  val panGuardPx = with(density) { TREE_VIEW_PAN_GUARD_DP.dp.toPx() }

  fun resetViewportToFit() {
    val transform = calculateFollowupTreeViewportTransform(
      boardWidthPx = boardWidthPx,
      boardHeightPx = boardHeightPx,
      viewportWidthPx = viewportSizePx.width.toFloat(),
      viewportHeightPx = viewportSizePx.height.toFloat(),
      minScale = minScale,
      maxScale = maxScale,
      panGuardPx = panGuardPx
    )
    boardScale = transform.scale
    boardPanOffset = transform.panOffset
  }

  fun focusViewportOnActiveNode() {
    val focusPointDp = findFollowupTreeFocusPoint(
      boardLayout = boardLayout,
      activeSpanId = activeSpanId,
      activeDetailId = activeDetailId
    ) ?: return
    if (viewportSizePx.width <= 0 || viewportSizePx.height <= 0) {
      return
    }

    val focusPointPx = with(density) {
      Offset(
        x = focusPointDp.x.dp.toPx(),
        y = focusPointDp.y.dp.toPx()
      )
    }

    val transform = calculateFollowupTreeFocusTransform(
      targetCenterPx = focusPointPx,
      currentScale = boardScale,
      minScale = minScale,
      maxScale = maxScale,
      boardWidthPx = boardWidthPx,
      boardHeightPx = boardHeightPx,
      viewportWidthPx = viewportSizePx.width.toFloat(),
      viewportHeightPx = viewportSizePx.height.toFloat(),
      panGuardPx = panGuardPx
    )
    boardScale = transform.scale
    boardPanOffset = transform.panOffset
  }

  LaunchedEffect(scopes, viewportSizePx, boardWidthPx, boardHeightPx, panGuardPx) {
    if (!hasInitializedViewport && viewportSizePx.width > 0 && viewportSizePx.height > 0) {
      resetViewportToFit()
      hasInitializedViewport = true
    }
  }

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false)
  ) {
    Surface(
      color = Color(0xFFF6FBF7),
      modifier = Modifier.fillMaxSize()
    ) {
      Column(
        modifier = Modifier
          .fillMaxSize()
          .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            text = "追问图谱",
            style = MaterialTheme.typography.titleMedium,
            color = Color(0xFF255E4D)
          )
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = {
              focusViewportOnActiveNode()
            }) {
              Text(text = "定位当前", style = MaterialTheme.typography.labelSmall)
            }

            TextButton(onClick = {
              resetViewportToFit()
            }) {
              Text(text = "重置视图", style = MaterialTheme.typography.labelSmall)
            }

            IconButton(onClick = onDismiss) {
              Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "关闭图谱",
                tint = Color(0xFF2C6756)
              )
            }
          }
        }

        Text(
          text = "当前主界面全局图谱（整题卡、段落卡与追问节点）",
          style = MaterialTheme.typography.labelSmall,
          color = Color(0xFF5D736A)
        )

        if (scopes.isEmpty()) {
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .weight(1f),
            contentAlignment = Alignment.Center
          ) {
            Text(
              text = "暂无追问树。先在聊天里发起追问，再回来查看图谱。",
              style = MaterialTheme.typography.bodySmall,
              color = Color(0xFF5D7069)
            )
          }
        } else {
          Surface(
            color = Color(0xFFFEFFFD),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
              .fillMaxWidth()
              .weight(1f)
              .border(1.dp, Color(0x1F3A5A4F), RoundedCornerShape(12.dp))
          ) {
            Box(
              modifier = Modifier
                .fillMaxSize()
                .padding(10.dp)
                .onSizeChanged { size ->
                  viewportSizePx = size
                }
                .pointerInput(scopes, viewportSizePx, boardWidthPx, boardHeightPx, panGuardPx) {
                  detectTransformGestures { centroid, pan, zoom, _ ->
                    val previousScale = boardScale
                    val nextScale = (previousScale * zoom).coerceIn(minScale, maxScale)
                    val scaleFactor = if (previousScale == 0f) 1f else nextScale / previousScale
                    val nextPan = Offset(
                      x = (boardPanOffset.x - centroid.x) * scaleFactor + centroid.x + pan.x,
                      y = (boardPanOffset.y - centroid.y) * scaleFactor + centroid.y + pan.y
                    )
                    boardScale = nextScale
                    boardPanOffset = clampFollowupTreePan(
                      panOffset = nextPan,
                      scale = nextScale,
                      boardWidthPx = boardWidthPx,
                      boardHeightPx = boardHeightPx,
                      viewportWidthPx = viewportSizePx.width.toFloat(),
                      viewportHeightPx = viewportSizePx.height.toFloat(),
                      panGuardPx = panGuardPx
                    )
                  }
                }
            ) {
              Box(
                modifier = Modifier.size(
                  width = boardLayout.width.dp,
                  height = boardLayout.height.dp
                )
                  .offset {
                    IntOffset(
                      x = boardPanOffset.x.roundToInt(),
                      y = boardPanOffset.y.roundToInt()
                    )
                  }
                  .graphicsLayer {
                    scaleX = boardScale
                    scaleY = boardScale
                    transformOrigin = TransformOrigin(0f, 0f)
                  }
              ) {
                Canvas(modifier = Modifier.matchParentSize()) {
                  val strokePx = 2.2.dp.toPx()
                  val lineColor = Color(0x8A567A6D)
                  val dpToAlignedPx: (Float) -> Float = { value -> value.dp.roundToPx().toFloat() }
                  val rootPortInsetPx = TREE_ROOT_CONNECTOR_PORT_INSET_DP.dp.toPx()

                  boardLayout.scopes.forEach { scopeLayout ->
                    val positions = scopeLayout.treeLayout.positions
                    val rootLeftPx = TREE_ROOT_LEFT_DP.dp.roundToPx().toFloat()
                    val rootWidthPx = scopeLayout.rootWidth.dp.roundToPx().toFloat()
                    val rootTopPx = dpToAlignedPx(
                      scopeLayout.yOffset + scopeLayout.rootCenterY - scopeLayout.rootHeight / 2f
                    )
                    val rootHeightPx = scopeLayout.rootHeight.dp.roundToPx().toFloat()
                    val rootRightX = rootLeftPx + rootWidthPx

                    val anchorByDetailId = positions.associate { position ->
                      val topPx = dpToAlignedPx(scopeLayout.yOffset + position.y)
                      val leftPx = dpToAlignedPx(position.x)
                      val widthPx = position.width.dp.roundToPx().toFloat()
                      val heightPx = position.height.dp.roundToPx().toFloat()
                      position.detail.id to FollowupTreeConnectorAnchor(
                        leftX = leftPx,
                        rightX = leftPx + widthPx,
                        centerY = topPx + heightPx / 2f
                      )
                    }

                    positions
                      .filter { position -> position.parentDetailId == null }
                      .forEach rootChild@{ child ->
                        val childAnchor = anchorByDetailId[child.detail.id] ?: return@rootChild
                        val rootPortY = childAnchor.centerY.coerceIn(
                          minimumValue = rootTopPx + rootPortInsetPx,
                          maximumValue = rootTopPx + rootHeightPx - rootPortInsetPx
                        )
                        drawFollowupTreeConnector(
                          start = Offset(x = rootRightX, y = rootPortY),
                          end = Offset(x = childAnchor.leftX, y = childAnchor.centerY),
                          color = lineColor,
                          strokeWidth = strokePx
                        )
                      }

                    for (child in positions) {
                      val parentId = child.parentDetailId ?: continue
                      val parentAnchor = anchorByDetailId[parentId] ?: continue
                      val childAnchor = anchorByDetailId[child.detail.id] ?: continue
                      drawFollowupTreeConnector(
                        start = Offset(x = parentAnchor.rightX, y = parentAnchor.centerY),
                        end = Offset(x = childAnchor.leftX, y = childAnchor.centerY),
                        color = lineColor,
                        strokeWidth = strokePx
                      )
                    }
                  }
                }

                boardLayout.scopes.forEach { scopeLayout ->
                  FollowupTreeRootCard(
                    scope = scopeLayout.scope,
                    centerY = scopeLayout.yOffset + scopeLayout.rootCenterY,
                    width = scopeLayout.rootWidth,
                    height = scopeLayout.rootHeight,
                    isActiveRoot = scopeLayout.scope.spanId == activeSpanId && activeDetailId == null,
                    onOpenBranch = onOpenBranch
                  )

                  scopeLayout.treeLayout.positions.forEach { position ->
                    FollowupTreeNodeCard(
                      spanId = scopeLayout.scope.spanId,
                      position = position,
                      yOffset = scopeLayout.yOffset,
                      isActive = scopeLayout.scope.spanId == activeSpanId && position.detail.id == activeDetailId,
                      onOpenBranch = onOpenBranch
                    )
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun FollowupTreeRootCard(
  scope: FollowupTreeScope,
  centerY: Float,
  width: Float,
  height: Float,
  isActiveRoot: Boolean,
  onOpenBranch: (String, String?) -> Unit
) {
  Surface(
    color = if (isActiveRoot) Color(0xFFDFF0E7) else Color(0xFFF2FBF6),
    shape = RoundedCornerShape(10.dp),
    modifier = Modifier
      .size(width = width.dp, height = height.dp)
      .offset {
        IntOffset(
          x = TREE_ROOT_LEFT_DP.dp.roundToPx(),
          y = (centerY - height / 2f).dp.roundToPx()
        )
      }
      .border(1.dp, Color(0x24335D4E), RoundedCornerShape(10.dp))
      .clickable { onOpenBranch(scope.spanId, null) }
  ) {
    Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(
        text = "根节点",
        style = MaterialTheme.typography.labelSmall,
        color = Color(0xFF57756B)
      )
      Text(
        text = scope.spanContent,
        style = MaterialTheme.typography.bodySmall,
        color = Color(0xFF2F4A42),
        maxLines = 6,
        overflow = TextOverflow.Ellipsis
      )
      Text(
        text = scope.sourceQuestion,
        style = MaterialTheme.typography.labelSmall,
        color = Color(0xFF658177),
        maxLines = 3,
        overflow = TextOverflow.Ellipsis
      )
      Text(text = "点击进入该段追问分支", style = MaterialTheme.typography.labelSmall, color = Color(0xFF2F6D5C))
    }
  }
}

@Composable
private fun FollowupTreeNodeCard(
  spanId: String,
  position: FollowupTreePosition,
  yOffset: Float,
  isActive: Boolean,
  onOpenBranch: (String, String?) -> Unit
) {
  val detail = position.detail
  val summaryText = resolveFollowupTreeNodeSummary(detail)

  Surface(
    color = if (isActive) Color(0xFFE4F1E9) else Color(0xFFF8FCF9),
    shape = RoundedCornerShape(10.dp),
    modifier = Modifier
      .size(width = position.width.dp, height = position.height.dp)
      .offset {
        IntOffset(
          x = position.x.dp.roundToPx(),
          y = (position.y + yOffset).dp.roundToPx()
        )
      }
      .border(1.dp, Color(0x1F3A5A4F), RoundedCornerShape(10.dp))
      .clickable { onOpenBranch(spanId, detail.id) }
  ) {
    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
      Text(
        text = "${detail.mode} · ${detail.time}",
        style = MaterialTheme.typography.labelSmall,
        color = Color(0xFF637E75)
      )
      Text(
        text = buildFollowupTreeNodeLabel(detail),
        style = MaterialTheme.typography.bodySmall,
        color = Color(0xFF2F463E),
        maxLines = 5,
        overflow = TextOverflow.Ellipsis
      )
      Text(
        text = summaryText,
        style = MaterialTheme.typography.labelSmall,
        color = Color(0xFF68847A),
        maxLines = 4,
        overflow = TextOverflow.Ellipsis
      )
      Text(text = "点击进入该追问分支", style = MaterialTheme.typography.labelSmall, color = Color(0xFF2F6D5C))
    }
  }
}

private data class FollowupTreeCardSize(
  val width: Float,
  val height: Float
)

private data class FollowupTreeNode(
  val detail: SpanDetail,
  val children: List<FollowupTreeNode>
)

private data class FollowupTreePosition(
  val detail: SpanDetail,
  val parentDetailId: String?,
  val x: Float,
  val y: Float,
  val width: Float,
  val height: Float,
  val centerY: Float
)

private data class FollowupTreeLayout(
  val positions: List<FollowupTreePosition>,
  val width: Float,
  val height: Float
)

private data class FollowupTreeScopeLayout(
  val scope: FollowupTreeScope,
  val treeLayout: FollowupTreeLayout,
  val rootWidth: Float,
  val rootHeight: Float,
  val yOffset: Float,
  val rootCenterY: Float
)

private data class FollowupTreeBoardLayout(
  val scopes: List<FollowupTreeScopeLayout>,
  val width: Float,
  val height: Float
)

private data class FollowupTreeViewportTransform(
  val scale: Float,
  val panOffset: Offset
)

private data class FollowupTreeConnectorAnchor(
  val leftX: Float,
  val rightX: Float,
  val centerY: Float
)

private const val TREE_NODE_MIN_WIDTH_DP = 170f
private const val TREE_NODE_MAX_WIDTH_DP = 420f
private const val TREE_NODE_MIN_HEIGHT_DP = 84f
private const val TREE_NODE_MAX_HEIGHT_DP = 340f
private const val TREE_NODE_HORIZONTAL_GAP_DP = 62f
private const val TREE_NODE_VERTICAL_GAP_DP = 28f
private const val TREE_NODE_PADDING_DP = 24f
private const val TREE_ROOT_SHIFT_RIGHT_DP = 30f
private const val TREE_SCOPE_GAP_DP = 36f
private const val TREE_SCOPE_PADDING_DP = 12f
private const val TREE_VIEW_PAN_GUARD_DP = 88f
private const val TREE_ROOT_BASE_HEIGHT_DP = 68f
private const val TREE_NODE_BASE_HEIGHT_DP = 70f
private const val TREE_BODY_LINE_HEIGHT_DP = 17f
private const val TREE_LABEL_LINE_HEIGHT_DP = 16f
private const val TREE_ESTIMATED_TEXT_UNIT_WIDTH_DP = 12.6f
private const val TREE_CONNECTOR_MIN_BEND_DP = 18f
private const val TREE_CONNECTOR_MAX_BEND_DP = 78f
private const val TREE_CONNECTOR_PORT_OVERLAP_DP = 1.2f
private const val TREE_ROOT_CONNECTOR_PORT_INSET_DP = 10f
private const val TREE_ROOT_LEFT_DP = TREE_NODE_PADDING_DP + TREE_ROOT_SHIFT_RIGHT_DP

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFollowupTreeConnector(
  start: Offset,
  end: Offset,
  color: Color,
  strokeWidth: Float
) {
  val overlapPx = TREE_CONNECTOR_PORT_OVERLAP_DP.dp.toPx()
  val adjustedStart = Offset(x = start.x - overlapPx, y = start.y)
  val adjustedEnd = Offset(x = end.x + overlapPx, y = end.y)

  val deltaX = adjustedEnd.x - adjustedStart.x
  if (deltaX <= 1f) {
    drawLine(
      color = color,
      start = adjustedStart,
      end = adjustedEnd,
      strokeWidth = strokeWidth,
      cap = StrokeCap.Round
    )
    return
  }

  val bend = (deltaX * 0.42f).coerceIn(
    TREE_CONNECTOR_MIN_BEND_DP.dp.toPx(),
    TREE_CONNECTOR_MAX_BEND_DP.dp.toPx()
  )

  val path = Path().apply {
    moveTo(adjustedStart.x, adjustedStart.y)
    cubicTo(
      x1 = adjustedStart.x + bend,
      y1 = adjustedStart.y,
      x2 = adjustedEnd.x - bend,
      y2 = adjustedEnd.y,
      x3 = adjustedEnd.x,
      y3 = adjustedEnd.y
    )
  }

  drawPath(
    path = path,
    color = color,
    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
  )
}

private fun calculateFollowupTreeViewportTransform(
  boardWidthPx: Float,
  boardHeightPx: Float,
  viewportWidthPx: Float,
  viewportHeightPx: Float,
  minScale: Float,
  maxScale: Float,
  panGuardPx: Float
): FollowupTreeViewportTransform {
  if (boardWidthPx <= 0f || boardHeightPx <= 0f || viewportWidthPx <= 0f || viewportHeightPx <= 0f) {
    return FollowupTreeViewportTransform(scale = 1f, panOffset = Offset.Zero)
  }

  val fitScale = minOf(
    viewportWidthPx / boardWidthPx,
    viewportHeightPx / boardHeightPx
  ).coerceIn(minScale, maxScale)

  val centeredPan = Offset(
    x = (viewportWidthPx - boardWidthPx * fitScale) / 2f,
    y = (viewportHeightPx - boardHeightPx * fitScale) / 2f
  )

  return FollowupTreeViewportTransform(
    scale = fitScale,
    panOffset = clampFollowupTreePan(
      panOffset = centeredPan,
      scale = fitScale,
      boardWidthPx = boardWidthPx,
      boardHeightPx = boardHeightPx,
      viewportWidthPx = viewportWidthPx,
      viewportHeightPx = viewportHeightPx,
      panGuardPx = panGuardPx
    )
  )
}

private fun calculateFollowupTreeFocusTransform(
  targetCenterPx: Offset,
  currentScale: Float,
  minScale: Float,
  maxScale: Float,
  boardWidthPx: Float,
  boardHeightPx: Float,
  viewportWidthPx: Float,
  viewportHeightPx: Float,
  panGuardPx: Float
): FollowupTreeViewportTransform {
  if (boardWidthPx <= 0f || boardHeightPx <= 0f || viewportWidthPx <= 0f || viewportHeightPx <= 0f) {
    return FollowupTreeViewportTransform(scale = currentScale.coerceIn(minScale, maxScale), panOffset = Offset.Zero)
  }

  val baselineScale = 1f.coerceIn(minScale, maxScale)
  val focusScale = maxOf(currentScale, baselineScale).coerceIn(minScale, maxScale)
  val centeredPan = Offset(
    x = viewportWidthPx / 2f - targetCenterPx.x * focusScale,
    y = viewportHeightPx / 2f - targetCenterPx.y * focusScale
  )

  return FollowupTreeViewportTransform(
    scale = focusScale,
    panOffset = clampFollowupTreePan(
      panOffset = centeredPan,
      scale = focusScale,
      boardWidthPx = boardWidthPx,
      boardHeightPx = boardHeightPx,
      viewportWidthPx = viewportWidthPx,
      viewportHeightPx = viewportHeightPx,
      panGuardPx = panGuardPx
    )
  )
}

private fun findFollowupTreeFocusPoint(
  boardLayout: FollowupTreeBoardLayout,
  activeSpanId: String?,
  activeDetailId: String?
): Offset? {
  if (boardLayout.scopes.isEmpty()) {
    return null
  }

  val activeScope = activeSpanId?.let { spanId ->
    boardLayout.scopes.firstOrNull { scopeLayout -> scopeLayout.scope.spanId == spanId }
  }
  val activeNode = if (activeScope != null && !activeDetailId.isNullOrBlank()) {
    activeScope.treeLayout.positions.firstOrNull { position -> position.detail.id == activeDetailId }
  } else {
    null
  }

  if (activeScope != null && activeNode != null) {
    return Offset(
      x = activeNode.x + activeNode.width / 2f,
      y = activeScope.yOffset + activeNode.centerY
    )
  }

  val focusScope = activeScope ?: boardLayout.scopes.first()
  return Offset(
    x = TREE_ROOT_LEFT_DP + focusScope.rootWidth / 2f,
    y = focusScope.yOffset + focusScope.rootCenterY
  )
}

private fun clampFollowupTreePan(
  panOffset: Offset,
  scale: Float,
  boardWidthPx: Float,
  boardHeightPx: Float,
  viewportWidthPx: Float,
  viewportHeightPx: Float,
  panGuardPx: Float
): Offset {
  if (boardWidthPx <= 0f || boardHeightPx <= 0f || viewportWidthPx <= 0f || viewportHeightPx <= 0f) {
    return panOffset
  }

  val scaledWidth = boardWidthPx * scale
  val scaledHeight = boardHeightPx * scale

  val (minX, maxX) = if (scaledWidth <= viewportWidthPx) {
    val centered = (viewportWidthPx - scaledWidth) / 2f
    centered - panGuardPx to centered + panGuardPx
  } else {
    viewportWidthPx - scaledWidth - panGuardPx to panGuardPx
  }

  val (minY, maxY) = if (scaledHeight <= viewportHeightPx) {
    val centered = (viewportHeightPx - scaledHeight) / 2f
    centered - panGuardPx to centered + panGuardPx
  } else {
    viewportHeightPx - scaledHeight - panGuardPx to panGuardPx
  }

  return Offset(
    x = panOffset.x.coerceIn(minX, maxX),
    y = panOffset.y.coerceIn(minY, maxY)
  )
}

private fun measureFollowupTreeRootCard(scope: FollowupTreeScope): FollowupTreeCardSize {
  val width = estimateFollowupTreeCardWidth(
    primary = scope.spanContent,
    secondary = scope.sourceQuestion,
    tertiary = "根节点"
  )
  val charsPerLine = estimateCardCharsPerLine(width)
  val contentLines = estimateTextLines(scope.spanContent, charsPerLine, minLines = 1, maxLines = 6)
  val sourceLines = estimateTextLines(scope.sourceQuestion, charsPerLine, minLines = 1, maxLines = 3)
  val height = (TREE_ROOT_BASE_HEIGHT_DP +
    contentLines * TREE_BODY_LINE_HEIGHT_DP +
    sourceLines * TREE_LABEL_LINE_HEIGHT_DP).coerceIn(
    TREE_NODE_MIN_HEIGHT_DP,
    TREE_NODE_MAX_HEIGHT_DP
  )
  return FollowupTreeCardSize(width = width, height = height)
}

private fun measureFollowupTreeDetailCard(detail: SpanDetail): FollowupTreeCardSize {
  val label = buildFollowupTreeNodeLabel(detail)
  val summary = resolveFollowupTreeNodeSummary(detail)
  val width = estimateFollowupTreeCardWidth(
    primary = label,
    secondary = summary,
    tertiary = detail.mode
  )
  val charsPerLine = estimateCardCharsPerLine(width)
  val labelLines = estimateTextLines(label, charsPerLine, minLines = 1, maxLines = 5)
  val summaryLines = estimateTextLines(summary, charsPerLine, minLines = 1, maxLines = 4)
  val height = (TREE_NODE_BASE_HEIGHT_DP +
    labelLines * TREE_BODY_LINE_HEIGHT_DP +
    summaryLines * TREE_LABEL_LINE_HEIGHT_DP).coerceIn(
    TREE_NODE_MIN_HEIGHT_DP,
    TREE_NODE_MAX_HEIGHT_DP
  )
  return FollowupTreeCardSize(width = width, height = height)
}

private fun estimateFollowupTreeCardWidth(primary: String, secondary: String, tertiary: String): Float {
  val longestUnits = maxOf(
    estimateDisplayUnits(primary),
    estimateDisplayUnits(secondary),
    estimateDisplayUnits(tertiary)
  )
  val extra = longestUnits.coerceAtMost(100f) * 3.4f
  return (TREE_NODE_MIN_WIDTH_DP + extra).coerceIn(TREE_NODE_MIN_WIDTH_DP, TREE_NODE_MAX_WIDTH_DP)
}

private fun estimateCardCharsPerLine(width: Float): Int {
  return ((width - 26f) / TREE_ESTIMATED_TEXT_UNIT_WIDTH_DP).roundToInt().coerceAtLeast(10)
}

private fun estimateTextLines(text: String, charsPerLine: Int, minLines: Int, maxLines: Int): Int {
  val normalized = text.trim()
  if (normalized.isBlank()) {
    return minLines
  }
  val estimated = ceil(estimateDisplayUnits(normalized) / charsPerLine.toFloat()).toInt()
  return estimated.coerceIn(minLines, maxLines)
}

private fun estimateDisplayUnits(text: String): Float {
  return text.fold(0f) { units, char ->
    units + when {
      char.isWhitespace() -> 0.35f
      char.code <= 0x7F -> 0.58f
      else -> 1f
    }
  }
}

private fun resolveFollowupTreeNodeSummary(detail: SpanDetail): String {
  return detail.summary
    ?.trim()
    ?.takeIf { summary -> summary.isNotEmpty() }
    ?: buildDetailCardSummary(question = detail.question, answer = detail.answer)
}

private fun buildFollowupTreeNodes(details: List<SpanDetail>): List<FollowupTreeNode> {
  if (details.isEmpty()) {
    return emptyList()
  }

  val chronological = details.asReversed()
  val allIds = chronological.map { detail -> detail.id }.toSet()
  val childrenByParent = LinkedHashMap<String?, MutableList<SpanDetail>>()

  chronological.forEach { detail ->
    val normalizedParent = detail.parentDetailId?.takeIf { parentId -> allIds.contains(parentId) }
    childrenByParent.getOrPut(normalizedParent) { mutableListOf() }.add(detail)
  }

  fun buildNode(detail: SpanDetail, lineage: Set<String>): FollowupTreeNode {
    if (lineage.contains(detail.id)) {
      return FollowupTreeNode(detail = detail, children = emptyList())
    }

    val nextLineage = lineage + detail.id
    val children = childrenByParent[detail.id].orEmpty().map { child ->
      buildNode(child, nextLineage)
    }
    return FollowupTreeNode(detail = detail, children = children)
  }

  return childrenByParent[null].orEmpty().map { root ->
    buildNode(root, emptySet())
  }
}

private fun layoutFollowupTreeNodes(
  roots: List<FollowupTreeNode>,
  rootWidth: Float,
  rootHeight: Float
): FollowupTreeLayout {
  if (roots.isEmpty()) {
    return FollowupTreeLayout(
      positions = emptyList(),
      width = TREE_NODE_PADDING_DP * 2 + rootWidth,
      height = TREE_NODE_PADDING_DP * 2 + rootHeight
    )
  }

  var maxDepth = 1
  val sizeById = mutableMapOf<String, FollowupTreeCardSize>()
  val widthByDepth = mutableMapOf<Int, Float>()

  fun collectSizes(node: FollowupTreeNode, depth: Int) {
    maxDepth = maxOf(maxDepth, depth)
    val size = measureFollowupTreeDetailCard(node.detail)
    sizeById[node.detail.id] = size
    widthByDepth[depth] = maxOf(widthByDepth[depth] ?: 0f, size.width)
    node.children.forEach { child ->
      collectSizes(child, depth + 1)
    }
  }

  roots.forEach { root ->
    collectSizes(root, depth = 1)
  }

  val xByDepth = mutableMapOf<Int, Float>()
  var xCursor = TREE_NODE_PADDING_DP + rootWidth + TREE_NODE_HORIZONTAL_GAP_DP
  for (depth in 1..maxDepth) {
    xByDepth[depth] = xCursor
    xCursor += (widthByDepth[depth] ?: TREE_NODE_MIN_WIDTH_DP) + TREE_NODE_HORIZONTAL_GAP_DP
  }

  var nextLeafTop = TREE_NODE_PADDING_DP
  var maxRight = TREE_NODE_PADDING_DP + rootWidth
  val positions = mutableListOf<FollowupTreePosition>()

  fun place(node: FollowupTreeNode, depth: Int, parentId: String?): Float {
    val size = sizeById[node.detail.id] ?: measureFollowupTreeDetailCard(node.detail)
    val childCenters = node.children.map { child ->
      place(child, depth + 1, node.detail.id)
    }

    val centerY = if (childCenters.isEmpty()) {
      val center = nextLeafTop + size.height / 2f
      nextLeafTop += size.height + TREE_NODE_VERTICAL_GAP_DP
      center
    } else {
      (childCenters.first() + childCenters.last()) / 2f
    }
    val top = centerY - size.height / 2f

    val occupiedBottom = top + size.height + TREE_NODE_VERTICAL_GAP_DP
    if (occupiedBottom > nextLeafTop) {
      nextLeafTop = occupiedBottom
    }

    val x = xByDepth[depth] ?: (TREE_NODE_PADDING_DP + rootWidth + TREE_NODE_HORIZONTAL_GAP_DP)
    maxRight = maxOf(maxRight, x + size.width)

    positions += FollowupTreePosition(
      detail = node.detail,
      parentDetailId = parentId,
      x = x,
      y = top,
      width = size.width,
      height = size.height,
      centerY = centerY
    )

    return centerY
  }

  roots.forEach { root ->
    place(root, depth = 1, parentId = null)
  }

  val maxBottom = positions.maxOfOrNull { position -> position.y + position.height }
    ?: (TREE_NODE_PADDING_DP + rootHeight)

  return FollowupTreeLayout(
    positions = positions,
    width = maxOf(maxRight + TREE_NODE_PADDING_DP, TREE_NODE_PADDING_DP * 2 + rootWidth),
    height = maxOf(maxBottom + TREE_NODE_PADDING_DP, TREE_NODE_PADDING_DP * 2 + rootHeight)
  )
}

private fun buildFollowupTreeBoardLayout(scopes: List<FollowupTreeScope>): FollowupTreeBoardLayout {
  if (scopes.isEmpty()) {
    return FollowupTreeBoardLayout(
      scopes = emptyList(),
      width = TREE_NODE_MIN_WIDTH_DP + TREE_NODE_PADDING_DP * 2,
      height = TREE_NODE_MIN_HEIGHT_DP + TREE_NODE_PADDING_DP * 2
    )
  }

  var yCursor = TREE_SCOPE_PADDING_DP
  var maxWidth = TREE_NODE_MIN_WIDTH_DP + TREE_NODE_PADDING_DP * 2
  val layouts = mutableListOf<FollowupTreeScopeLayout>()

  scopes.forEach { scope ->
    val rootSize = measureFollowupTreeRootCard(scope)
    val roots = buildFollowupTreeNodes(scope.details)
    val treeLayout = layoutFollowupTreeNodes(
      roots = roots,
      rootWidth = rootSize.width,
      rootHeight = rootSize.height
    )
    val targetCenterY = if (treeLayout.positions.isEmpty()) {
      treeLayout.height / 2f
    } else {
      (treeLayout.positions.minOf { position -> position.centerY } +
        treeLayout.positions.maxOf { position -> position.centerY }) / 2f
    }
    val minRootCenterY = TREE_NODE_PADDING_DP + rootSize.height / 2f
    val maxRootCenterY = treeLayout.height - TREE_NODE_PADDING_DP - rootSize.height / 2f
    val rootCenterY = targetCenterY.coerceIn(minRootCenterY, maxRootCenterY)

    layouts += FollowupTreeScopeLayout(
      scope = scope,
      treeLayout = treeLayout,
      rootWidth = rootSize.width,
      rootHeight = rootSize.height,
      yOffset = yCursor,
      rootCenterY = rootCenterY
    )

    yCursor += treeLayout.height + TREE_SCOPE_GAP_DP
    maxWidth = maxOf(maxWidth, treeLayout.width)
  }

  val totalHeight = maxOf(
    yCursor - TREE_SCOPE_GAP_DP + TREE_SCOPE_PADDING_DP,
    TREE_NODE_MIN_HEIGHT_DP + TREE_SCOPE_PADDING_DP * 2
  )

  return FollowupTreeBoardLayout(
    scopes = layouts,
    width = maxWidth + TREE_SCOPE_PADDING_DP * 2,
    height = totalHeight
  )
}

private fun buildFollowupTreeNodeLabel(detail: SpanDetail): String {
  val question = detail.question?.trim().orEmpty()
  return if (question.isNotEmpty()) {
    question
  } else {
    detail.answer.trim().ifBlank { detail.mode }
  }
}

internal fun formatSessionTime(updatedAt: Long): String {
  val formatter = SimpleDateFormat("MM-dd HH:mm", Locale.SIMPLIFIED_CHINESE)
  return formatter.format(Date(updatedAt))
}
