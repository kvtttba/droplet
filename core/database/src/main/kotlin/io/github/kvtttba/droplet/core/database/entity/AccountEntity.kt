package io.github.kvtttba.droplet.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.kvtttba.droplet.core.model.AccountSubType
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.Currency

@Entity(
    tableName = "accounts",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["parent_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["parent_id"]),
        Index(value = ["type", "sub_type", "is_archived", "sort_order"]),
        Index(value = ["is_system"]),
    ],
)
data class AccountEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "type")
    val type: AccountType,
    @ColumnInfo(name = "sub_type")
    val subType: AccountSubType,
    @ColumnInfo(name = "parent_id")
    val parentId: String? = null,
    @ColumnInfo(name = "currency")
    val currency: Currency = Currency.CNY,
    @ColumnInfo(name = "icon")
    val icon: String? = null,
    @ColumnInfo(name = "color")
    val color: String? = null,
    @ColumnInfo(name = "include_in_net_worth")
    val includeInNetWorth: Boolean = true,
    @ColumnInfo(name = "is_archived")
    val isArchived: Boolean = false,
    @ColumnInfo(name = "is_system")
    val isSystem: Boolean = false,
    @ColumnInfo(name = "credit_limit_minor")
    val creditLimitMinor: Long? = null,
    @ColumnInfo(name = "billing_day")
    val billingDay: Int? = null,
    @ColumnInfo(name = "due_day")
    val dueDay: Int? = null,
    @ColumnInfo(name = "remind_days_before")
    val remindDaysBefore: Int? = null,
    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = 0L,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = 0L,
)
