package io.github.kvtttba.droplet.feature.ledger.manage

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.Tag
import io.github.kvtttba.droplet.core.ui.component.DropletCard
import io.github.kvtttba.droplet.core.ui.component.DropletPillBadge
import io.github.kvtttba.droplet.core.ui.icon.DropletIconRegistry
import io.github.kvtttba.droplet.core.ui.icon.DropletIcons
import io.github.kvtttba.droplet.feature.ledger.CategoryGroupOption

@Composable
fun CategoryTagManagementRoute(
    onNavigateBack: () -> Unit,
    onShowMessage: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: CategoryTagManagementViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.userMessage) {
        uiState.userMessage?.let { msg ->
            onShowMessage(msg)
            viewModel.clearUserMessage()
        }
    }

    CategoryTagManagementScreen(
        uiState = uiState,
        onNavigateBack = onNavigateBack,
        onSelectTab = viewModel::selectTab,
        onToggleShowArchived = viewModel::toggleShowArchived,
        onSaveCategory = viewModel::saveCategory,
        onSetCategoryArchived = viewModel::setCategoryArchived,
        onDeleteCategory = viewModel::deleteCategoryIfUnused,
        onSaveTag = viewModel::saveTag,
        onSetTagArchived = viewModel::setTagArchived,
        onDeleteTag = viewModel::deleteTag,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CategoryTagManagementScreen(
    uiState: CategoryTagManagementUiState,
    onNavigateBack: () -> Unit = {},
    onSelectTab: (ManageCatalogTab) -> Unit = {},
    onToggleShowArchived: () -> Unit = {},
    onSaveCategory: (CategorySaveRequest, (Boolean) -> Unit) -> Unit = { _, cb -> cb(true) },
    onSetCategoryArchived: (String, Boolean) -> Unit = { _, _ -> },
    onDeleteCategory: (String) -> Unit = {},
    onSaveTag: (TagSaveRequest, (Boolean) -> Unit) -> Unit = { _, cb -> cb(true) },
    onSetTagArchived: (String, Boolean) -> Unit = { _, _ -> },
    onDeleteTag: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var categoryDialogTarget by remember { mutableStateOf<CategoryDialogState?>(null) }
    var tagDialogTarget by remember { mutableStateOf<TagDialogState?>(null) }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("screen_category_tag_management"),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                title = {
                    Column {
                        Text("分类与标签管理", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = "自定义二级收支分类与多标签配色",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    FilterChip(
                        selected = uiState.showArchived,
                        onClick = onToggleShowArchived,
                        label = { Text("含已归档", style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.padding(end = 12.dp),
                    )
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    when (uiState.activeTab) {
                        ManageCatalogTab.EXPENSE_CATEGORIES -> {
                            categoryDialogTarget = CategoryDialogState(
                                existing = null,
                                type = AccountType.EXPENSE,
                                parentId = null,
                            )
                        }
                        ManageCatalogTab.INCOME_CATEGORIES -> {
                            categoryDialogTarget = CategoryDialogState(
                                existing = null,
                                type = AccountType.INCOME,
                                parentId = null,
                            )
                        }
                        ManageCatalogTab.TAGS -> {
                            tagDialogTarget = TagDialogState(existing = null)
                        }
                    }
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("catalog_add_button"),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = "新建")
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "catalog_tabs") {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    ManageCatalogTab.entries.forEachIndexed { index, tab ->
                        SegmentedButton(
                            selected = uiState.activeTab == tab,
                            onClick = { onSelectTab(tab) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = ManageCatalogTab.entries.size,
                            ),
                            modifier = Modifier.testTag("catalog_tab_${tab.name.lowercase()}"),
                        ) {
                            Text(tab.label, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            when (uiState.activeTab) {
                ManageCatalogTab.EXPENSE_CATEGORIES, ManageCatalogTab.INCOME_CATEGORIES -> {
                    val groups = if (uiState.activeTab == ManageCatalogTab.EXPENSE_CATEGORIES) {
                        uiState.expenseGroups
                    } else {
                        uiState.incomeGroups
                    }
                    val type = if (uiState.activeTab == ManageCatalogTab.EXPENSE_CATEGORIES) {
                        AccountType.EXPENSE
                    } else {
                        AccountType.INCOME
                    }

                    items(groups, key = { it.parent.id }) { group ->
                        CategoryGroupManageCard(
                            group = group,
                            onEditParent = {
                                categoryDialogTarget = CategoryDialogState(
                                    existing = group.parent,
                                    type = type,
                                    parentId = null,
                                )
                            },
                            onAddChild = {
                                categoryDialogTarget = CategoryDialogState(
                                    existing = null,
                                    type = type,
                                    parentId = group.parent.id,
                                    defaultIcon = group.parent.icon ?: "category",
                                    defaultColor = group.parent.color ?: "#006970",
                                )
                            },
                            onEditChild = { child ->
                                categoryDialogTarget = CategoryDialogState(
                                    existing = child,
                                    type = type,
                                    parentId = group.parent.id,
                                )
                            },
                        )
                    }
                }

                ManageCatalogTab.TAGS -> {
                    item(key = "tags_card") {
                        DropletCard(contentPadding = PaddingValues(14.dp)) {
                            Text(
                                text = "全部标签（${uiState.tags.size} 个）",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "一笔交易可关联多个标签，支持在首页按任一/全部标签筛选。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            uiState.tags.forEachIndexed { index, tag ->
                                val tagColor = DropletIconRegistry.parseColor(
                                    hex = tag.color,
                                    fallback = MaterialTheme.colorScheme.primary,
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { tagDialogTarget = TagDialogState(existing = tag) }
                                        .padding(vertical = 10.dp)
                                        .testTag("tag_manage_row_${tag.name}"),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(tagColor.copy(alpha = 0.16f)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            imageVector = DropletIconRegistry.resolve(tag.icon ?: "label"),
                                            contentDescription = null,
                                            tint = tagColor,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text(
                                            text = "#${tag.name}",
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        if (tag.isArchived) {
                                            DropletPillBadge(text = "已归档")
                                        }
                                    }
                                    Icon(
                                        imageVector = Icons.Rounded.Edit,
                                        contentDescription = "编辑标签",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                if (index != uiState.tags.lastIndex) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 48.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    categoryDialogTarget?.let { target ->
        val parentCandidates = when (target.type) {
            AccountType.EXPENSE -> uiState.expenseGroups.map { it.parent }
            AccountType.INCOME -> uiState.incomeGroups.map { it.parent }
            else -> emptyList()
        }
        CategoryEditorBottomSheet(
            state = target,
            parentCandidates = parentCandidates,
            onDismiss = { categoryDialogTarget = null },
            onSave = { req ->
                onSaveCategory(req) { ok -> if (ok) categoryDialogTarget = null }
            },
            onSetArchived = { id, archived ->
                onSetCategoryArchived(id, archived)
                categoryDialogTarget = null
            },
            onDelete = { id ->
                onDeleteCategory(id)
                categoryDialogTarget = null
            },
        )
    }

    tagDialogTarget?.let { target ->
        TagEditorBottomSheet(
            existing = target.existing,
            onDismiss = { tagDialogTarget = null },
            onSave = { req ->
                onSaveTag(req) { ok -> if (ok) tagDialogTarget = null }
            },
            onSetArchived = { id, archived ->
                onSetTagArchived(id, archived)
                tagDialogTarget = null
            },
            onDelete = { id ->
                onDeleteTag(id)
                tagDialogTarget = null
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryGroupManageCard(
    group: CategoryGroupOption,
    onEditParent: () -> Unit,
    onAddChild: () -> Unit,
    onEditChild: (Account) -> Unit,
) {
    val parentColor = DropletIconRegistry.parseColor(group.parent.color, MaterialTheme.colorScheme.primary)
    DropletCard(contentPadding = PaddingValues(14.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onEditParent)
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(parentColor.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = DropletIconRegistry.resolve(group.parent.icon),
                    contentDescription = null,
                    tint = parentColor,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = group.parent.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (group.parent.isArchived) {
                        DropletPillBadge(text = "已归档")
                    }
                }
                Text(
                    text = "${group.children.size} 个子分类",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = onAddChild,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(2.dp))
                Text("添加小类", style = MaterialTheme.typography.labelMedium)
            }
        }

        if (group.children.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            Spacer(modifier = Modifier.height(10.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                group.children.forEach { child ->
                    val childColor = DropletIconRegistry.parseColor(child.color, parentColor)
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = childColor.copy(alpha = if (child.isArchived) 0.08f else 0.12f),
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .clickable { onEditChild(child) },
                    ) {
                        Text(
                            text = if (child.isArchived) "${child.name} (归档)" else child.name,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (child.isArchived) MaterialTheme.colorScheme.onSurfaceVariant else childColor,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

private data class CategoryDialogState(
    val existing: Account?,
    val type: AccountType,
    val parentId: String?,
    val defaultIcon: String = "restaurant",
    val defaultColor: String = "#006970",
)

private data class TagDialogState(
    val existing: Tag?,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CategoryEditorBottomSheet(
    state: CategoryDialogState,
    parentCandidates: List<Account>,
    onDismiss: () -> Unit,
    onSave: (CategorySaveRequest) -> Unit,
    onSetArchived: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val existing = state.existing
    var name by remember(state) { mutableStateOf(existing?.name.orEmpty()) }
    var parentId by remember(state) { mutableStateOf(existing?.parentId ?: state.parentId) }
    var iconKey by remember(state) { mutableStateOf(existing?.icon ?: state.defaultIcon) }
    var colorHex by remember(state) { mutableStateOf(existing?.color ?: state.defaultColor) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .padding(bottom = 28.dp)
                .testTag("category_editor_sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val typeLabel = if (state.type == AccountType.EXPENSE) "支出" else "收入"
            Text(
                text = when {
                    existing != null -> "编辑${typeLabel}分类"
                    parentId != null -> "新建${typeLabel}二级小类"
                    else -> "新建${typeLabel}一级大类"
                },
                style = MaterialTheme.typography.titleLarge,
            )

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("分类名称") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("category_name_input"),
            )

            if (existing == null || existing.parentId != null) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("所属大类", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        FilterChip(
                            selected = parentId == null,
                            onClick = { parentId = null },
                            label = { Text("作为一级大类") },
                        )
                        parentCandidates
                            .filter { it.id != existing?.id }
                            .forEach { parent ->
                                FilterChip(
                                    selected = parentId == parent.id,
                                    onClick = { parentId = parent.id },
                                    label = { Text(parent.name) },
                                )
                            }
                    }
                }
            }

            ColorAndIconPicker(
                selectedColorHex = colorHex,
                selectedIconKey = iconKey,
                onColorSelect = { colorHex = it },
                onIconSelect = { iconKey = it },
            )

            Button(
                onClick = {
                    onSave(
                        CategorySaveRequest(
                            existing = existing,
                            type = state.type,
                            parentId = parentId,
                            name = name,
                            icon = iconKey,
                            color = colorHex,
                        ),
                    )
                },
                enabled = name.isNotBlank(),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("category_save_button"),
            ) {
                Text(if (existing == null) "创建分类" else "保存修改")
            }

            if (existing != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    FilledTonalButton(
                        onClick = { onSetArchived(existing.id, !existing.isArchived) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(DropletIcons.Archive, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (existing.isArchived) "恢复分类" else "归档隐藏")
                    }
                    OutlinedButton(
                        onClick = { onDelete(existing.id) },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Icon(Icons.Rounded.Delete, contentDescription = "删除", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TagEditorBottomSheet(
    existing: Tag?,
    onDismiss: () -> Unit,
    onSave: (TagSaveRequest) -> Unit,
    onSetArchived: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember(existing) { mutableStateOf(existing?.name.orEmpty()) }
    var iconKey by remember(existing) { mutableStateOf(existing?.icon ?: "label") }
    var colorHex by remember(existing) { mutableStateOf(existing?.color ?: "#006970") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .padding(bottom = 28.dp)
                .testTag("tag_editor_sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (existing == null) "新建标签" else "编辑标签",
                style = MaterialTheme.typography.titleLarge,
            )

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                prefix = { Text("# ") },
                label = { Text("标签名称（如 装修 / 旅游 / 宠物）") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("tag_name_input"),
            )

            ColorAndIconPicker(
                selectedColorHex = colorHex,
                selectedIconKey = iconKey,
                onColorSelect = { colorHex = it },
                onIconSelect = { iconKey = it },
            )

            Button(
                onClick = {
                    onSave(
                        TagSaveRequest(
                            existing = existing,
                            name = name,
                            icon = iconKey,
                            color = colorHex,
                        ),
                    )
                },
                enabled = name.isNotBlank(),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("tag_save_button"),
            ) {
                Text(if (existing == null) "创建标签" else "保存修改")
            }

            if (existing != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    FilledTonalButton(
                        onClick = { onSetArchived(existing.id, !existing.isArchived) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(DropletIcons.Archive, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (existing.isArchived) "恢复标签" else "归档隐藏")
                    }
                    OutlinedButton(
                        onClick = { onDelete(existing.id) },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Icon(Icons.Rounded.Delete, contentDescription = "删除", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}
