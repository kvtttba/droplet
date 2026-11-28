package io.github.kvtttba.droplet.feature.assets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.kvtttba.droplet.core.model.Currency

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun CreateDebtProfileSheet(
    isSubmitting: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (CreateDebtProfileFormInput, (String?) -> Unit) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember { mutableStateOf("") }
    var currency by remember { mutableStateOf(Currency.CNY) }
    var dueDateIso by remember { mutableStateOf("") }
    var annualRatePercent by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var includeInNetWorth by remember { mutableStateOf(true) }
    var errorText by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                .testTag("create_debt_profile_sheet"),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "新建往来人",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "系统将自动为其建立「应收」与「应付」往来科目，支持借出、借入、分次收还含息与坏账核销。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    errorText = null
                },
                label = { Text("往来人名称") },
                placeholder = { Text("例如：张三、室友小李、公司垫付对象") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("create_debt_name_input"),
            )

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "往来币种",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Currency.entries.forEach { ccy ->
                        FilterChip(
                            selected = currency == ccy,
                            onClick = { currency = ccy },
                            label = { Text("${ccy.symbol} ${ccy.code}") },
                            modifier = Modifier.testTag("create_debt_currency_${ccy.code}"),
                        )
                    }
                }
            }

            OutlinedTextField(
                value = dueDateIso,
                onValueChange = {
                    dueDateIso = it
                    errorText = null
                },
                label = { Text("约定还款日（选填，YYYY-MM-DD）") },
                placeholder = { Text("例如：2026-12-31，留空不设提醒") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("create_debt_due_date_input"),
            )

            OutlinedTextField(
                value = annualRatePercent,
                onValueChange = {
                    annualRatePercent = it
                    errorText = null
                },
                label = { Text("约定年利率 %（选填）") },
                placeholder = { Text("例如：3.5，无息可留空") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("create_debt_rate_input"),
            )

            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("备注（选填）") },
                placeholder = { Text("例如：购房周转、合租押金") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("create_debt_note_input"),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "计入总净资产",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "开启后应收计入总资产，应付计入总负债",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = includeInNetWorth,
                    onCheckedChange = { includeInNetWorth = it },
                )
            }

            errorText?.let { err ->
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Button(
                onClick = {
                    onSubmit(
                        CreateDebtProfileFormInput(
                            name = name,
                            currency = currency,
                            dueDateIso = dueDateIso,
                            annualInterestRatePercentText = annualRatePercent,
                            includeInNetWorth = includeInNetWorth,
                            note = note,
                        ),
                    ) { err ->
                        if (err == null) {
                            onDismiss()
                        } else {
                            errorText = err
                        }
                    }
                },
                enabled = !isSubmitting,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("create_debt_submit_button"),
            ) {
                Text(if (isSubmitting) "正在创建…" else "创建往来人")
            }
        }
    }
}
