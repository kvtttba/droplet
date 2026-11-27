package io.github.kvtttba.droplet.core.database.entity

import io.github.kvtttba.droplet.core.database.dao.TransactionWithDetails
import io.github.kvtttba.droplet.core.domain.ledger.ExchangeRateTable
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AiDraft
import io.github.kvtttba.droplet.core.model.Attachment
import io.github.kvtttba.droplet.core.model.Budget
import io.github.kvtttba.droplet.core.model.CachedExchangeRate
import io.github.kvtttba.droplet.core.model.DebtProfile
import io.github.kvtttba.droplet.core.model.ExchangeRateSnapshot
import io.github.kvtttba.droplet.core.model.InvestmentAsset
import io.github.kvtttba.droplet.core.model.LoanInstallment
import io.github.kvtttba.droplet.core.model.LoanPlan
import io.github.kvtttba.droplet.core.model.MarketQuote
import io.github.kvtttba.droplet.core.model.Posting
import io.github.kvtttba.droplet.core.model.RecurringRule
import io.github.kvtttba.droplet.core.model.Tag
import io.github.kvtttba.droplet.core.model.Transaction
import io.github.kvtttba.droplet.core.model.TransactionTemplate

fun Account.toEntity(): AccountEntity = AccountEntity(
    id = id,
    name = name,
    type = type,
    subType = subType,
    parentId = parentId,
    currency = currency,
    icon = icon,
    color = color,
    includeInNetWorth = includeInNetWorth,
    isArchived = isArchived,
    isSystem = isSystem,
    creditLimitMinor = creditLimitMinor,
    billingDay = billingDay,
    dueDay = dueDay,
    remindDaysBefore = remindDaysBefore,
    sortOrder = sortOrder,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun AccountEntity.toDomain(): Account = Account(
    id = id,
    name = name,
    type = type,
    subType = subType,
    parentId = parentId,
    currency = currency,
    icon = icon,
    color = color,
    includeInNetWorth = includeInNetWorth,
    isArchived = isArchived,
    isSystem = isSystem,
    creditLimitMinor = creditLimitMinor,
    billingDay = billingDay,
    dueDay = dueDay,
    remindDaysBefore = remindDaysBefore,
    sortOrder = sortOrder,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun Transaction.toHeaderEntity(): TransactionEntity = TransactionEntity(
    id = id,
    occurredAt = occurredAt,
    displayType = displayType,
    status = status,
    payee = payee,
    note = note,
    source = source,
    refTransactionId = refTransactionId,
    reimburseState = reimburseState,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun Posting.toEntity(): PostingEntity = PostingEntity(
    id = id,
    transactionId = transactionId,
    accountId = accountId,
    currency = currency,
    amountMinor = amountMinor,
    baseCurrency = baseCurrency,
    baseAmountMinor = baseAmountMinor,
    exchangeRateSnapshot = exchangeRateSnapshot.rateDecimal,
    exchangeRateSource = exchangeRateSnapshot.source,
    investmentAssetId = investmentAssetId,
    quantity = quantity,
    unitPrice = unitPrice,
    isSystemBalancing = isSystemBalancing,
)

fun PostingEntity.toDomain(capturedAt: Long): Posting = Posting(
    id = id,
    transactionId = transactionId,
    accountId = accountId,
    currency = currency,
    amountMinor = amountMinor,
    baseCurrency = baseCurrency,
    baseAmountMinor = baseAmountMinor,
    exchangeRateSnapshot = ExchangeRateSnapshot(
        fromCurrency = currency,
        toCurrency = baseCurrency,
        rateDecimal = exchangeRateSnapshot,
        source = exchangeRateSource,
        capturedAt = capturedAt,
    ),
    investmentAssetId = investmentAssetId,
    quantity = quantity,
    unitPrice = unitPrice,
    isSystemBalancing = isSystemBalancing,
)

fun TransactionWithDetails.toDomain(): Transaction = Transaction(
    id = transaction.id,
    occurredAt = transaction.occurredAt,
    displayType = transaction.displayType,
    status = transaction.status,
    payee = transaction.payee,
    note = transaction.note,
    source = transaction.source,
    refTransactionId = transaction.refTransactionId,
    reimburseState = transaction.reimburseState,
    tagIds = tags.map { it.id },
    postings = postings.map { it.toDomain(capturedAt = transaction.occurredAt) },
    createdAt = transaction.createdAt,
    updatedAt = transaction.updatedAt,
)

fun Tag.toEntity(): TagEntity = TagEntity(
    id = id,
    name = name,
    color = color,
    icon = icon,
    sortOrder = sortOrder,
    isArchived = isArchived,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun TagEntity.toDomain(): Tag = Tag(
    id = id,
    name = name,
    color = color,
    icon = icon,
    sortOrder = sortOrder,
    isArchived = isArchived,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun Attachment.toEntity(): AttachmentEntity = AttachmentEntity(
    id = id,
    transactionId = transactionId,
    localRelPath = localRelPath,
    sha256 = sha256,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    syncToWebdav = syncToWebdav,
    createdAt = createdAt,
)

fun AttachmentEntity.toDomain(): Attachment = Attachment(
    id = id,
    transactionId = transactionId,
    localRelPath = localRelPath,
    sha256 = sha256,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    syncToWebdav = syncToWebdav,
    createdAt = createdAt,
)

fun InvestmentAsset.toEntity(): InvestmentAssetEntity = InvestmentAssetEntity(
    id = id,
    accountId = accountId,
    assetClass = assetClass,
    symbol = symbol,
    name = name,
    currency = currency,
    quoteProviderHint = quoteProviderHint,
    depositRateAnnual = depositRateAnnualDecimal,
    depositStartAt = depositStartAt,
    depositMaturityAt = depositMaturityAt,
    depositInterestMode = depositInterestMode,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun InvestmentAssetEntity.toDomain(): InvestmentAsset = InvestmentAsset(
    id = id,
    accountId = accountId,
    assetClass = assetClass,
    symbol = symbol,
    name = name,
    currency = currency,
    quoteProviderHint = quoteProviderHint,
    depositRateAnnualDecimal = depositRateAnnual,
    depositStartAt = depositStartAt,
    depositMaturityAt = depositMaturityAt,
    depositInterestMode = depositInterestMode,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun MarketQuote.toEntity(): MarketQuoteEntity = MarketQuoteEntity(
    assetId = assetId,
    latestPrice = latestPrice,
    prevClose = prevClose,
    dailyChangePct = dailyChangePctDecimal,
    priceCurrency = priceCurrency,
    sourceProvider = sourceProvider,
    isManualOverride = isManualOverride,
    fetchedAt = fetchedAt,
)

fun MarketQuoteEntity.toDomain(): MarketQuote = MarketQuote(
    assetId = assetId,
    latestPrice = latestPrice,
    prevClose = prevClose,
    dailyChangePctDecimal = dailyChangePct,
    priceCurrency = priceCurrency,
    sourceProvider = sourceProvider,
    isManualOverride = isManualOverride,
    fetchedAt = fetchedAt,
)

fun CachedExchangeRate.toEntity(): ExchangeRateEntity = ExchangeRateEntity(
    fromCurrency = fromCurrency,
    toCurrency = toCurrency,
    rateDecimal = rateDecimal,
    sourceProvider = sourceProvider,
    fetchedAt = fetchedAt,
)

fun ExchangeRateEntity.toDomain(): CachedExchangeRate = CachedExchangeRate(
    fromCurrency = fromCurrency,
    toCurrency = toCurrency,
    rateDecimal = rateDecimal,
    sourceProvider = sourceProvider,
    fetchedAt = fetchedAt,
)

fun Iterable<ExchangeRateEntity>.toExchangeRateTable(): ExchangeRateTable =
    ExchangeRateTable(map { it.toDomain().toSnapshot() })

fun LoanPlan.toEntity(): LoanPlanEntity = LoanPlanEntity(
    id = id,
    name = name,
    method = method,
    currency = currency,
    totalPrincipalMinor = totalPrincipalMinor,
    totalPeriods = totalPeriods,
    annualRateDecimal = annualRateDecimal,
    fixedFeePerPeriodMinor = fixedFeePerPeriodMinor,
    fixedFeeRateDecimal = fixedFeeRateDecimal,
    firstDueAt = firstDueAt,
    monthlyRepayDay = monthlyRepayDay,
    loanAccountId = loanAccountId,
    paymentAccountId = paymentAccountId,
    interestExpenseAccountId = interestExpenseAccountId,
    autoPost = autoPost,
    remindDaysBefore = remindDaysBefore,
    isArchived = isArchived,
    note = note,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun LoanPlanEntity.toDomain(): LoanPlan = LoanPlan(
    id = id,
    name = name,
    method = method,
    currency = currency,
    totalPrincipalMinor = totalPrincipalMinor,
    totalPeriods = totalPeriods,
    annualRateDecimal = annualRateDecimal,
    fixedFeePerPeriodMinor = fixedFeePerPeriodMinor,
    fixedFeeRateDecimal = fixedFeeRateDecimal,
    firstDueAt = firstDueAt,
    monthlyRepayDay = monthlyRepayDay,
    loanAccountId = loanAccountId,
    paymentAccountId = paymentAccountId,
    interestExpenseAccountId = interestExpenseAccountId,
    autoPost = autoPost,
    remindDaysBefore = remindDaysBefore,
    isArchived = isArchived,
    note = note,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun LoanInstallment.toEntity(): LoanInstallmentEntity = LoanInstallmentEntity(
    id = id,
    loanPlanId = loanPlanId,
    periodNumber = periodNumber,
    dueAt = dueAt,
    principalMinor = principalMinor,
    interestOrFeeMinor = interestOrFeeMinor,
    remainingPrincipalMinor = remainingPrincipalMinor,
    status = status,
    transactionId = transactionId,
    paidAt = paidAt,
)

fun LoanInstallmentEntity.toDomain(): LoanInstallment = LoanInstallment(
    id = id,
    loanPlanId = loanPlanId,
    periodNumber = periodNumber,
    dueAt = dueAt,
    principalMinor = principalMinor,
    interestOrFeeMinor = interestOrFeeMinor,
    remainingPrincipalMinor = remainingPrincipalMinor,
    status = status,
    transactionId = transactionId,
    paidAt = paidAt,
)

fun DebtProfile.toEntity(): DebtProfileEntity = DebtProfileEntity(
    id = id,
    name = name,
    receivableAccountId = receivableAccountId,
    payableAccountId = payableAccountId,
    defaultDueAt = defaultDueAt,
    annualInterestRate = annualInterestRateDecimal,
    note = note,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun DebtProfileEntity.toDomain(): DebtProfile = DebtProfile(
    id = id,
    name = name,
    receivableAccountId = receivableAccountId,
    payableAccountId = payableAccountId,
    defaultDueAt = defaultDueAt,
    annualInterestRateDecimal = annualInterestRate,
    note = note,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun Budget.toEntity(): BudgetEntity = BudgetEntity(
    id = id,
    period = period,
    categoryAccountId = categoryAccountId,
    currency = currency,
    limitAmountMinor = limitAmountMinor,
    warnThresholdPct = warnThresholdPct,
    isEnabled = isEnabled,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun BudgetEntity.toDomain(): Budget = Budget(
    id = id,
    period = period,
    categoryAccountId = categoryAccountId,
    currency = currency,
    limitAmountMinor = limitAmountMinor,
    warnThresholdPct = warnThresholdPct,
    isEnabled = isEnabled,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun RecurringRule.toEntity(): RecurringRuleEntity = RecurringRuleEntity(
    id = id,
    name = name,
    frequency = frequency,
    intervalCount = intervalCount,
    cronExpression = cronExpression,
    nextRunAt = nextRunAt,
    endAt = endAt,
    templatePayloadJson = templatePayloadJson,
    autoPost = autoPost,
    isPaused = isPaused,
    lastRunAt = lastRunAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun RecurringRuleEntity.toDomain(): RecurringRule = RecurringRule(
    id = id,
    name = name,
    frequency = frequency,
    intervalCount = intervalCount,
    cronExpression = cronExpression,
    nextRunAt = nextRunAt,
    endAt = endAt,
    templatePayloadJson = templatePayloadJson,
    autoPost = autoPost,
    isPaused = isPaused,
    lastRunAt = lastRunAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun TransactionTemplate.toEntity(): TemplateEntity = TemplateEntity(
    id = id,
    name = name,
    displayType = displayType,
    templatePayloadJson = templatePayloadJson,
    icon = icon,
    color = color,
    sortOrder = sortOrder,
    useCount = useCount,
    lastUsedAt = lastUsedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun TemplateEntity.toDomain(): TransactionTemplate = TransactionTemplate(
    id = id,
    name = name,
    displayType = displayType,
    templatePayloadJson = templatePayloadJson,
    icon = icon,
    color = color,
    sortOrder = sortOrder,
    useCount = useCount,
    lastUsedAt = lastUsedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun AiDraft.toEntity(): AiDraftEntity = AiDraftEntity(
    id = id,
    inputType = inputType,
    rawText = rawText,
    attachmentPathsJson = attachmentPathsJson,
    status = status,
    parsedItemsJson = parsedItemsJson,
    errorMessage = errorMessage,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun AiDraftEntity.toDomain(): AiDraft = AiDraft(
    id = id,
    inputType = inputType,
    rawText = rawText,
    attachmentPathsJson = attachmentPathsJson,
    status = status,
    parsedItemsJson = parsedItemsJson,
    errorMessage = errorMessage,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
