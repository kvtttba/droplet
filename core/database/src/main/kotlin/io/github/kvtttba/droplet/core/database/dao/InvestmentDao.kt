package io.github.kvtttba.droplet.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import io.github.kvtttba.droplet.core.database.entity.ExchangeRateEntity
import io.github.kvtttba.droplet.core.database.entity.InvestmentAssetEntity
import io.github.kvtttba.droplet.core.database.entity.MarketQuoteEntity
import io.github.kvtttba.droplet.core.model.AssetClass
import io.github.kvtttba.droplet.core.model.Currency
import kotlinx.coroutines.flow.Flow

@Dao
interface InvestmentAssetDao {

    @Upsert
    suspend fun upsert(asset: InvestmentAssetEntity)

    @Upsert
    suspend fun upsertAll(assets: List<InvestmentAssetEntity>)

    @Query("SELECT * FROM investment_assets WHERE id = :assetId LIMIT 1")
    suspend fun getById(assetId: String): InvestmentAssetEntity?

    @Query("SELECT * FROM investment_assets ORDER BY asset_class ASC, symbol ASC, id ASC")
    fun observeAll(): Flow<List<InvestmentAssetEntity>>

    @Query("SELECT * FROM investment_assets ORDER BY asset_class ASC, symbol ASC, id ASC")
    suspend fun getAll(): List<InvestmentAssetEntity>

    @Query("SELECT * FROM investment_assets WHERE account_id = :accountId ORDER BY symbol ASC, id ASC")
    fun observeByAccountId(accountId: String): Flow<List<InvestmentAssetEntity>>

    @Query("SELECT * FROM investment_assets WHERE asset_class = :assetClass ORDER BY symbol ASC, id ASC")
    fun observeByAssetClass(assetClass: AssetClass): Flow<List<InvestmentAssetEntity>>

    @Query("DELETE FROM investment_assets WHERE id = :assetId")
    suspend fun deleteById(assetId: String)
}

@Dao
interface MarketQuoteDao {

    @Upsert
    suspend fun upsert(quote: MarketQuoteEntity)

    @Upsert
    suspend fun upsertAll(quotes: List<MarketQuoteEntity>)

    @Query("SELECT * FROM market_quotes WHERE asset_id = :assetId LIMIT 1")
    suspend fun getByAssetId(assetId: String): MarketQuoteEntity?

    @Query("SELECT * FROM market_quotes ORDER BY fetched_at DESC, asset_id ASC")
    fun observeAll(): Flow<List<MarketQuoteEntity>>

    @Query("SELECT * FROM market_quotes ORDER BY fetched_at DESC, asset_id ASC")
    suspend fun getAll(): List<MarketQuoteEntity>

    @Query("SELECT * FROM market_quotes WHERE asset_id = :assetId LIMIT 1")
    fun observeByAssetId(assetId: String): Flow<MarketQuoteEntity?>

    @Query("DELETE FROM market_quotes WHERE asset_id = :assetId")
    suspend fun deleteByAssetId(assetId: String)
}

@Dao
interface ExchangeRateDao {

    @Upsert
    suspend fun upsert(rate: ExchangeRateEntity)

    @Upsert
    suspend fun upsertAll(rates: List<ExchangeRateEntity>)

    @Query(
        """
        SELECT * FROM exchange_rates
        WHERE from_currency = :fromCurrency AND to_currency = :toCurrency
        LIMIT 1
        """,
    )
    suspend fun getRate(fromCurrency: Currency, toCurrency: Currency): ExchangeRateEntity?

    @Query("SELECT * FROM exchange_rates ORDER BY from_currency ASC, to_currency ASC")
    fun observeAll(): Flow<List<ExchangeRateEntity>>

    @Query("SELECT * FROM exchange_rates ORDER BY from_currency ASC, to_currency ASC")
    suspend fun getAll(): List<ExchangeRateEntity>

    @Query("DELETE FROM exchange_rates WHERE from_currency = :fromCurrency AND to_currency = :toCurrency")
    suspend fun deleteRate(fromCurrency: Currency, toCurrency: Currency)
}
