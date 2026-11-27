package io.github.kvtttba.droplet.core.ui.component

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import io.github.kvtttba.droplet.core.domain.PrivacyAmountFormatter
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.ui.icon.DropletIcons
import io.github.kvtttba.droplet.core.ui.theme.tabularNums

val LocalAmountsHidden = compositionLocalOf { false }

@Composable
fun PrivacyAmountText(
    money: Money,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleMedium,
    color: Color = LocalContentColor.current,
    hidden: Boolean = LocalAmountsHidden.current,
    showPositiveSign: Boolean = false,
    showCurrencySymbol: Boolean = true,
) {
    val formatted = PrivacyAmountFormatter.format(
        money = money,
        hidden = hidden,
        showPositiveSign = showPositiveSign,
        showCurrencySymbol = showCurrencySymbol,
    )
    Text(
        text = formatted,
        modifier = modifier,
        style = style.tabularNums(),
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun PrivacyToggleIconButton(
    amountsHidden: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    IconButton(
        onClick = onToggle,
        modifier = modifier.testTag("privacy_toggle_button"),
    ) {
        Icon(
            imageVector = if (amountsHidden) {
                DropletIcons.VisibilityOff
            } else {
                DropletIcons.Visibility
            },
            contentDescription = if (amountsHidden) "显示金额" else "隐藏金额",
            tint = tint,
        )
    }
}
