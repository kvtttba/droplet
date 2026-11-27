package io.github.kvtttba.droplet.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.InstallmentStatus
import io.github.kvtttba.droplet.core.model.LoanMethod

@Entity(
    tableName = "loan_plans",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["loan_account_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["payment_account_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["interest_expense_account_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["loan_account_id"]),
        Index(value = ["payment_account_id"]),
        Index(value = ["interest_expense_account_id"]),
        Index(value = ["is_archived"]),
    ],
)
data class LoanPlanEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "method")
    val method: LoanMethod,
    @ColumnInfo(name = "currency")
    val currency: Currency = Currency.CNY,
    @ColumnInfo(name = "total_principal_minor")
    val totalPrincipalMinor: Long,
    @ColumnInfo(name = "total_periods")
    val totalPeriods: Int,
    @ColumnInfo(name = "annual_rate_decimal")
    val annualRateDecimal: String? = null,
    @ColumnInfo(name = "fixed_fee_per_period_minor")
    val fixedFeePerPeriodMinor: Long? = null,
    @ColumnInfo(name = "fixed_fee_rate_decimal")
    val fixedFeeRateDecimal: String? = null,
    @ColumnInfo(name = "first_due_at")
    val firstDueAt: Long,
    @ColumnInfo(name = "monthly_repay_day")
    val monthlyRepayDay: Int,
    @ColumnInfo(name = "loan_account_id")
    val loanAccountId: String,
    @ColumnInfo(name = "payment_account_id")
    val paymentAccountId: String,
    @ColumnInfo(name = "interest_expense_account_id")
    val interestExpenseAccountId: String,
    @ColumnInfo(name = "auto_post")
    val autoPost: Boolean = false,
    @ColumnInfo(name = "remind_days_before")
    val remindDaysBefore: Int? = null,
    @ColumnInfo(name = "is_archived")
    val isArchived: Boolean = false,
    @ColumnInfo(name = "note")
    val note: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = 0L,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = 0L,
)

@Entity(
    tableName = "loan_installments",
    foreignKeys = [
        ForeignKey(
            entity = LoanPlanEntity::class,
            parentColumns = ["id"],
            childColumns = ["loan_plan_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TransactionEntity::class,
            parentColumns = ["id"],
            childColumns = ["transaction_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["loan_plan_id", "period_number"], unique = true),
        Index(value = ["status", "due_at"]),
        Index(value = ["transaction_id"]),
    ],
)
data class LoanInstallmentEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "loan_plan_id")
    val loanPlanId: String,
    @ColumnInfo(name = "period_number")
    val periodNumber: Int,
    @ColumnInfo(name = "due_at")
    val dueAt: Long,
    @ColumnInfo(name = "principal_minor")
    val principalMinor: Long,
    @ColumnInfo(name = "interest_or_fee_minor")
    val interestOrFeeMinor: Long,
    @ColumnInfo(name = "remaining_principal_minor")
    val remainingPrincipalMinor: Long,
    @ColumnInfo(name = "status")
    val status: InstallmentStatus = InstallmentStatus.PENDING,
    @ColumnInfo(name = "transaction_id")
    val transactionId: String? = null,
    @ColumnInfo(name = "paid_at")
    val paidAt: Long? = null,
)

@Entity(
    tableName = "debt_profiles",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["receivable_account_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["payable_account_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["receivable_account_id"]),
        Index(value = ["payable_account_id"]),
        Index(value = ["name"]),
    ],
)
data class DebtProfileEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "receivable_account_id")
    val receivableAccountId: String? = null,
    @ColumnInfo(name = "payable_account_id")
    val payableAccountId: String? = null,
    @ColumnInfo(name = "default_due_at")
    val defaultDueAt: Long? = null,
    @ColumnInfo(name = "annual_interest_rate")
    val annualInterestRate: String? = null,
    @ColumnInfo(name = "note")
    val note: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = 0L,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = 0L,
)
