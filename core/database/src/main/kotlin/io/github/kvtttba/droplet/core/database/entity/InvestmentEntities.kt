package io.github.kvtttba.droplet.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.kvtttba.droplet.core.model.AssetClass
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.DepositInterestMode
import io.github.kvtttba.droplet.core.model.UnitPrice

@Entity(
    tableName = "investment_assets",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["account_id"]),
        Index(value = ["asset_class", "symbol"]),
    ],
)
data class InvestmentAssetEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "account_id")
    val accountId: String,
    @ColumnInfo(name = "asset_class")
    val assetClass: AssetClass,
    @ColumnInfo(name = "symbol")
    val symbol: String,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "currency")
    val currency: Currency,
    @ColumnInfo(name = "quote_provider_hint")
    val quoteProviderHint: String? = null,
    @ColumnInfo(name = "deposit_rate_annual")
    val depositRateAnnual: String? = null,
    @ColumnInfo(name = "deposit_start_at")
    val depositStartAt: Long? = null,
    @ColumnInfo(name = "deposit_maturity_at")
    val depositMaturityAt: Long? = null,
    @ColumnInfo(name = "deposit_interest_mode")
    val depositInterestMode: DepositInterestMode? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = 0L,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = 0L,
)

@Entity(
    tableName = "market_quotes",
    foreignKeys = [
        ForeignKey(
            entity = InvestmentAssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["asset_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["fetched_at"]),
    ],
)
data class MarketQuoteEntity(
    @PrimaryKey
    @ColumnInfo(name = "asset_id")
    val assetId: String,
    @ColumnInfo(name = "latest_price_decimal")
    val latestPrice: UnitPrice,
    @ColumnInfo(name = "prev_close_decimal")
    val prevClose: UnitPrice? = null,
    @ColumnInfo(name = "daily_change_pct")
    val dailyChangePct: String? = null,
    @ColumnInfo(name = "price_currency")
    val priceCurrency: Currency,
    @ColumnInfo(name = "source_provider")
    val sourceProvider: String,
    @ColumnInfo(name = "is_manual_override")
    val isManualOverride: Boolean = false,
    @ColumnInfo(name = "fetched_at")
    val fetchedAt: Long,
)

@Entity(
    tableName = "exchange_rates",
    primaryKeys = ["from_currency", "to_currency"],
    indices = [
        Index(value = ["fetched_at"]),
    ],
)
data class ExchangeRateEntity(
    @ColumnInfo(name = "from_currency")
    val fromCurrency: Currency,
    @ColumnInfo(name = "to_currency")
    val toCurrency: Currency,
    @ColumnInfo(name = "rate_decimal")
    val rateDecimal: String,
    @ColumnInfo(name = "source_provider")
    val sourceProvider: String,
    @ColumnInfo(name = "fetched_at")
    val fetchedAt: Long,
)
