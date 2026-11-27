package io.github.kvtttba.droplet.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.ExchangeRateSource
import io.github.kvtttba.droplet.core.model.Quantity
import io.github.kvtttba.droplet.core.model.UnitPrice

@Entity(
    tableName = "postings",
    foreignKeys = [
        ForeignKey(
            entity = TransactionEntity::class,
            parentColumns = ["id"],
            childColumns = ["transaction_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = InvestmentAssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["investment_asset_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["transaction_id"]),
        Index(value = ["account_id", "currency"]),
        Index(value = ["account_id", "base_currency"]),
        Index(value = ["investment_asset_id"]),
    ],
)
data class PostingEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "transaction_id")
    val transactionId: String,
    @ColumnInfo(name = "account_id")
    val accountId: String,
    @ColumnInfo(name = "currency")
    val currency: Currency,
    @ColumnInfo(name = "amount_minor")
    val amountMinor: Long,
    @ColumnInfo(name = "base_currency")
    val baseCurrency: Currency,
    @ColumnInfo(name = "base_amount_minor")
    val baseAmountMinor: Long,
    @ColumnInfo(name = "exchange_rate_snapshot")
    val exchangeRateSnapshot: String,
    @ColumnInfo(name = "exchange_rate_source", defaultValue = "'MARKET_CACHE'")
    val exchangeRateSource: ExchangeRateSource = ExchangeRateSource.MARKET_CACHE,
    @ColumnInfo(name = "investment_asset_id")
    val investmentAssetId: String? = null,
    @ColumnInfo(name = "quantity_decimal")
    val quantity: Quantity? = null,
    @ColumnInfo(name = "unit_price_decimal")
    val unitPrice: UnitPrice? = null,
    @ColumnInfo(name = "is_system_balancing")
    val isSystemBalancing: Boolean = false,
)
