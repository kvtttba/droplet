package io.github.kvtttba.droplet.feature.aicapture

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.kvtttba.droplet.core.ui.component.DropletPillBadge
import io.github.kvtttba.droplet.core.ui.icon.DropletIcons

enum class QuickCaptureMode(
    val title: String,
    val subtitle: String,
    val milestoneTag: String,
    val icon: ImageVector,
) {
    MANUAL(
        title = "手动记账",
        subtitle = "直观表单与可展开专业复式分录",
        milestoneTag = "已可用",
        icon = DropletIcons.EditNote,
    ),
    IMAGE(
        title = "拍照 / 选图识别",
        subtitle = "消费小票、支付截图、多笔账单长截图",
        milestoneTag = "M4.2",
        icon = DropletIcons.PhotoCamera,
    ),
    VOICE(
        title = "语音记账",
        subtitle = "AI 接口或系统语音，一句话拆多笔",
        milestoneTag = "M4.2",
        icon = DropletIcons.Mic,
    ),
    NATURAL_TEXT(
        title = "一句话 AI 记账",
        subtitle = "输入自然语言，预览草稿确认入账",
        milestoneTag = "M4.2",
        icon = DropletIcons.AutoAwesome,
    ),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickCaptureModalSheet(
    onSelectMode: (QuickCaptureMode) -> Unit,
    onDismissRequest: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        QuickCaptureSheetContent(
            onSelectMode = onSelectMode,
            modifier = Modifier.padding(bottom = 16.dp),
        )
    }
}

@Composable
fun QuickCaptureSheetContent(
    onSelectMode: (QuickCaptureMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .testTag("quick_capture_sheet"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "快捷记账",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "所有智能识别结果均先生成预览草稿，经你核对后再复式入账。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        QuickCaptureMode.entries.forEach { mode ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .clickable { onSelectMode(mode) }
                    .testTag("quick_capture_${mode.name.lowercase()}"),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = mode.icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = mode.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = mode.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    DropletPillBadge(text = mode.milestoneTag)
                }
            }
        }
    }
}
