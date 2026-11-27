package io.github.kvtttba.droplet.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class TransactionDisplayType {
    EXPENSE,
    INCOME,
    TRANSFER,
    LOAN_REPAY,
    DEBT,
    REFUND,
    REIMBURSE,
    INVEST,
    SPLIT,
}

@Serializable
enum class TransactionStatus {
    POSTED,
    DRAFT,
    VOID,
}

@Serializable
enum class TransactionSource {
    MANUAL,
    AI_IMAGE,
    AI_VOICE,
    AI_TEXT,
    RECURRING,
    LOAN_AUTO,
}

@Serializable
enum class ReimburseState {
    PENDING,
    PARTIAL,
    SETTLED,
}
