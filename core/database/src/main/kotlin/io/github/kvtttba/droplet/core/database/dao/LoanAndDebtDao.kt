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

    @Query(
        """
        SELECT * FROM loan_plans
        WHERE (:includeArchived = 1 OR is_archived = 0)
        ORDER BY first_due_at ASC, id ASC
        """,
    )
    fun observePlans(includeArchived: Boolean = false): Flow<List<LoanPlanEntity>>

    @Query("SELECT * FROM loan_installments WHERE loan_plan_id = :planId ORDER BY period_number ASC")
    fun observeInstallmentsForPlan(planId: String): Flow<List<LoanInstallmentEntity>>

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

    @Query("DELETE FROM loan_plans WHERE id = :planId")
    suspend fun deletePlanById(planId: String)

    @Query("SELECT COUNT(*) FROM loan_installments WHERE loan_plan_id = :planId")
    suspend fun countInstallmentsForPlan(planId: String): Int
}

@Dao
interface DebtProfileDao {

    @Upsert
    suspend fun upsert(profile: DebtProfileEntity)

    @Query("SELECT * FROM debt_profiles WHERE id = :profileId LIMIT 1")
    suspend fun getById(profileId: String): DebtProfileEntity?

    @Query("SELECT * FROM debt_profiles ORDER BY name ASC, id ASC")
    fun observeAll(): Flow<List<DebtProfileEntity>>

    @Query("DELETE FROM debt_profiles WHERE id = :profileId")
    suspend fun deleteById(profileId: String)
}
