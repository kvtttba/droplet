package io.github.kvtttba.droplet.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.kvtttba.droplet.core.model.ReimburseState
import io.github.kvtttba.droplet.core.model.TransactionDisplayType
import io.github.kvtttba.droplet.core.model.TransactionSource
import io.github.kvtttba.droplet.core.model.TransactionStatus

@Entity(
    tableName = "transactions",
    foreignKeys = [
        ForeignKey(
            entity = TransactionEntity::class,
            parentColumns = ["id"],
            childColumns = ["ref_transaction_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["occurred_at"]),
        Index(value = ["status", "occurred_at"]),
        Index(value = ["display_type", "occurred_at"]),
        Index(value = ["ref_transaction_id"]),
        Index(value = ["reimburse_state", "occurred_at"]),
    ],
)
data class TransactionEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "occurred_at")
    val occurredAt: Long,
    @ColumnInfo(name = "display_type")
    val displayType: TransactionDisplayType,
    @ColumnInfo(name = "status")
    val status: TransactionStatus = TransactionStatus.POSTED,
    @ColumnInfo(name = "payee")
    val payee: String? = null,
    @ColumnInfo(name = "note")
    val note: String? = null,
    @ColumnInfo(name = "source")
    val source: TransactionSource = TransactionSource.MANUAL,
    @ColumnInfo(name = "ref_transaction_id")
    val refTransactionId: String? = null,
    @ColumnInfo(name = "reimburse_state")
    val reimburseState: ReimburseState? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = occurredAt,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = occurredAt,
)
