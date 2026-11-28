package io.github.kvtttba.droplet.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import io.github.kvtttba.droplet.core.database.entity.DebtProfileEntity
import io.github.kvtttba.droplet.core.database.entity.LoanInstallmentEntity
import io.github.kvtttba.droplet.core.database.entity.LoanPlanEntity
import io.github.kvtttba.droplet.core.model.InstallmentStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface LoanDao {

    @Upsert
    suspend fun upsertPlan(plan: LoanPlanEntity)

    @Upsert
    suspend fun upsertInstallments(installments: List<LoanInstallmentEntity>)

    @Transaction
    suspend fun upsertPlanWithInstallments(
        plan: LoanPlanEntity,
        installments: List<LoanInstallmentEntity>,
    ) {
        upsertPlan(plan)
        upsertInstallments(installments)
    }

    @Query("SELECT * FROM loan_plans WHERE id = :planId LIMIT 1")
    suspend fun getPlanById(planId: String): LoanPlanEntity?

    @Query("SELECT * FROM loan_plans WHERE id = :planId LIMIT 1")
    fun observePlanById(planId: String): Flow<LoanPlanEntity?>

    @Query(
        """
        SELECT * FROM loan_plans
        WHERE (:includeArchived = 1 OR is_archived = 0)
        ORDER BY first_due_at ASC, id ASC
        """,
    )
    fun observePlans(includeArchived: Boolean = false): Flow<List<LoanPlanEntity>>

    @Query(
        """
        SELECT * FROM loan_plans
        WHERE (:includeArchived = 1 OR is_archived = 0)
        ORDER BY first_due_at ASC, id ASC
        """,
    )
    suspend fun getPlans(includeArchived: Boolean = false): List<LoanPlanEntity>

    @Query(
        """
        SELECT * FROM loan_plans
        WHERE auto_post = 1 AND is_archived = 0
        ORDER BY first_due_at ASC, id ASC
        """,
    )
    suspend fun getActiveAutoPostPlans(): List<LoanPlanEntity>

    @Query("SELECT * FROM loan_installments WHERE loan_plan_id = :planId ORDER BY period_number ASC")
    fun observeInstallmentsForPlan(planId: String): Flow<List<LoanInstallmentEntity>>

    @Query("SELECT * FROM loan_installments WHERE loan_plan_id = :planId ORDER BY period_number ASC")
    suspend fun getInstallmentsForPlan(planId: String): List<LoanInstallmentEntity>

    @Query("SELECT * FROM loan_installments ORDER BY loan_plan_id ASC, period_number ASC")
    fun observeAllInstallments(): Flow<List<LoanInstallmentEntity>>

    @Query("SELECT * FROM loan_installments ORDER BY loan_plan_id ASC, period_number ASC")
    suspend fun getAllInstallments(): List<LoanInstallmentEntity>

    @Query("SELECT * FROM loan_installments WHERE id = :installmentId LIMIT 1")
    suspend fun getInstallmentById(installmentId: String): LoanInstallmentEntity?

    @Query(
        """
        SELECT * FROM loan_installments
        WHERE status = :status AND due_at <= :dueUpToInclusive
        ORDER BY due_at ASC, period_number ASC
        """,
    )
    fun observeInstallmentsByStatusUpTo(
        dueUpToInclusive: Long,
        status: InstallmentStatus = InstallmentStatus.PENDING,
    ): Flow<List<LoanInstallmentEntity>>

    @Query(
        """
        SELECT * FROM loan_installments
        WHERE status = :status AND transaction_id IS NULL AND due_at <= :dueUpToInclusive
        ORDER BY due_at ASC, period_number ASC
        """,
    )
    suspend fun getPendingUnpostedInstallmentsUpTo(
        dueUpToInclusive: Long,
        status: InstallmentStatus = InstallmentStatus.PENDING,
    ): List<LoanInstallmentEntity>

    @Query(
        """
        UPDATE loan_installments
        SET status = :status, transaction_id = :transactionId, paid_at = :paidAt
        WHERE id = :installmentId
        """,
    )
    suspend fun markInstallmentStatus(
        installmentId: String,
        status: InstallmentStatus,
        transactionId: String?,
        paidAt: Long?,
    )

    @Query(
        """
        UPDATE loan_installments
        SET status = :paidStatus, transaction_id = :transactionId, paid_at = :paidAt
        WHERE id = :installmentId
          AND status = :expectedStatus
          AND transaction_id IS NULL
        """,
    )
    suspend fun markInstallmentPaidIfPending(
        installmentId: String,
        transactionId: String,
        paidAt: Long,
        expectedStatus: InstallmentStatus = InstallmentStatus.PENDING,
        paidStatus: InstallmentStatus = InstallmentStatus.PAID,
    ): Int

    @Query("UPDATE loan_plans SET is_archived = :archived, updated_at = :updatedAt WHERE id = :planId")
    suspend fun setPlanArchived(planId: String, archived: Boolean, updatedAt: Long)

    @Query("DELETE FROM loan_plans WHERE id = :planId")
    suspend fun deletePlanById(planId: String)

    @Query("SELECT COUNT(*) FROM loan_installments WHERE loan_plan_id = :planId")
    suspend fun countInstallmentsForPlan(planId: String): Int

    @Query(
        """
        SELECT COUNT(*) FROM loan_installments
        WHERE loan_plan_id = :planId
          AND (status = :paidStatus OR transaction_id IS NOT NULL)
        """,
    )
    suspend fun countPaidOrLinkedInstallmentsForPlan(
        planId: String,
        paidStatus: InstallmentStatus = InstallmentStatus.PAID,
    ): Int

    @Query("SELECT COUNT(*) FROM loan_plans WHERE loan_account_id = :loanAccountId")
    suspend fun countPlansForLoanAccount(loanAccountId: String): Int
}

@Dao
interface DebtProfileDao {

    @Upsert
    suspend fun upsert(profile: DebtProfileEntity)

    @Query("SELECT * FROM debt_profiles WHERE id = :profileId LIMIT 1")
    suspend fun getById(profileId: String): DebtProfileEntity?

    @Query("SELECT * FROM debt_profiles WHERE id = :profileId LIMIT 1")
    fun observeById(profileId: String): Flow<DebtProfileEntity?>

    @Query("SELECT * FROM debt_profiles WHERE LOWER(name) = LOWER(:name) LIMIT 1")
    suspend fun getByName(name: String): DebtProfileEntity?

    @Query("SELECT * FROM debt_profiles ORDER BY name ASC, id ASC")
    fun observeAll(): Flow<List<DebtProfileEntity>>

    @Query("SELECT * FROM debt_profiles ORDER BY name ASC, id ASC")
    suspend fun getAll(): List<DebtProfileEntity>

    @Query(
        """
        SELECT COUNT(*) FROM debt_profiles
        WHERE receivable_account_id = :accountId OR payable_account_id = :accountId
        """,
    )
    suspend fun countProfilesReferencingAccount(accountId: String): Int

    @Query("DELETE FROM debt_profiles WHERE id = :profileId")
    suspend fun deleteById(profileId: String)
}
