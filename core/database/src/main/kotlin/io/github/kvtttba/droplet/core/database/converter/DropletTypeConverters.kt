package io.github.kvtttba.droplet.core.database.converter

import androidx.room.TypeConverter
import io.github.kvtttba.droplet.core.model.AccountSubType
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.AiDraftInputType
import io.github.kvtttba.droplet.core.model.AiDraftStatus
import io.github.kvtttba.droplet.core.model.AssetClass
import io.github.kvtttba.droplet.core.model.BudgetPeriod
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.DepositInterestMode
import io.github.kvtttba.droplet.core.model.ExchangeRateSource
import io.github.kvtttba.droplet.core.model.InstallmentStatus
import io.github.kvtttba.droplet.core.model.LoanMethod
import io.github.kvtttba.droplet.core.model.Quantity
import io.github.kvtttba.droplet.core.model.RecurringFrequency
import io.github.kvtttba.droplet.core.model.ReimburseState
import io.github.kvtttba.droplet.core.model.TransactionDisplayType
import io.github.kvtttba.droplet.core.model.TransactionSource
import io.github.kvtttba.droplet.core.model.TransactionStatus
import io.github.kvtttba.droplet.core.model.UnitPrice

/**
 * Room type converters mapping strongly typed domain enums and canonical decimal value objects
 * to stable SQLite TEXT representations.
 */
class DropletTypeConverters {

    @TypeConverter
    fun fromCurrency(value: Currency): String = value.code

    @TypeConverter
    fun toCurrency(raw: String): Currency = Currency.fromCode(raw)

    @TypeConverter
    fun fromAccountType(value: AccountType): String = value.name

    @TypeConverter
    fun toAccountType(raw: String): AccountType = AccountType.valueOf(raw)

    @TypeConverter
    fun fromAccountSubType(value: AccountSubType): String = value.name

    @TypeConverter
    fun toAccountSubType(raw: String): AccountSubType = AccountSubType.valueOf(raw)

    @TypeConverter
    fun fromTransactionDisplayType(value: TransactionDisplayType): String = value.name

    @TypeConverter
    fun toTransactionDisplayType(raw: String): TransactionDisplayType = TransactionDisplayType.valueOf(raw)

    @TypeConverter
    fun fromTransactionStatus(value: TransactionStatus): String = value.name

    @TypeConverter
    fun toTransactionStatus(raw: String): TransactionStatus = TransactionStatus.valueOf(raw)

    @TypeConverter
    fun fromTransactionSource(value: TransactionSource): String = value.name

    @TypeConverter
    fun toTransactionSource(raw: String): TransactionSource = TransactionSource.valueOf(raw)

    @TypeConverter
    fun fromReimburseState(value: ReimburseState?): String? = value?.name

    @TypeConverter
    fun toReimburseState(raw: String?): ReimburseState? = raw?.let(ReimburseState::valueOf)

    @TypeConverter
    fun fromExchangeRateSource(value: ExchangeRateSource): String = value.name

    @TypeConverter
    fun toExchangeRateSource(raw: String): ExchangeRateSource = ExchangeRateSource.valueOf(raw)

    @TypeConverter
    fun fromQuantity(value: Quantity?): String? = value?.canonical

    @TypeConverter
    fun toQuantity(raw: String?): Quantity? = raw?.let(Quantity::of)

    @TypeConverter
    fun fromUnitPrice(value: UnitPrice?): String? = value?.canonical

    @TypeConverter
    fun toUnitPrice(raw: String?): UnitPrice? = raw?.let(UnitPrice::of)

    @TypeConverter
    fun fromAssetClass(value: AssetClass): String = value.name

    @TypeConverter
    fun toAssetClass(raw: String): AssetClass = AssetClass.valueOf(raw)

    @TypeConverter
    fun fromDepositInterestMode(value: DepositInterestMode?): String? = value?.name

    @TypeConverter
    fun toDepositInterestMode(raw: String?): DepositInterestMode? = raw?.let(DepositInterestMode::valueOf)

    @TypeConverter
    fun fromLoanMethod(value: LoanMethod): String = value.name

    @TypeConverter
    fun toLoanMethod(raw: String): LoanMethod = LoanMethod.valueOf(raw)

    @TypeConverter
    fun fromInstallmentStatus(value: InstallmentStatus): String = value.name

    @TypeConverter
    fun toInstallmentStatus(raw: String): InstallmentStatus = InstallmentStatus.valueOf(raw)

    @TypeConverter
    fun fromBudgetPeriod(value: BudgetPeriod): String = value.name

    @TypeConverter
    fun toBudgetPeriod(raw: String): BudgetPeriod = BudgetPeriod.valueOf(raw)

    @TypeConverter
    fun fromRecurringFrequency(value: RecurringFrequency): String = value.name

    @TypeConverter
    fun toRecurringFrequency(raw: String): RecurringFrequency = RecurringFrequency.valueOf(raw)

    @TypeConverter
    fun fromAiDraftInputType(value: AiDraftInputType): String = value.name

    @TypeConverter
    fun toAiDraftInputType(raw: String): AiDraftInputType = AiDraftInputType.valueOf(raw)

    @TypeConverter
    fun fromAiDraftStatus(value: AiDraftStatus): String = value.name

    @TypeConverter
    fun toAiDraftStatus(raw: String): AiDraftStatus = AiDraftStatus.valueOf(raw)
}
