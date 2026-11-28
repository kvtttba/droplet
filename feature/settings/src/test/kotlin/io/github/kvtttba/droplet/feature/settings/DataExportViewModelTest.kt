package io.github.kvtttba.droplet.feature.settings

import io.github.kvtttba.droplet.core.database.repository.ExportedFileResult
import io.github.kvtttba.droplet.core.database.repository.LedgerExportRepository
import io.github.kvtttba.droplet.core.domain.export.ExportFormat
import io.github.kvtttba.droplet.core.domain.export.ExportScope
import io.github.kvtttba.droplet.core.domain.export.LedgerExportArtifact
import io.github.kvtttba.droplet.core.domain.export.LedgerExportPreview
import io.github.kvtttba.droplet.core.domain.export.LedgerExportRequest
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.Tag
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DataExportViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private class FakeExportRepository : LedgerExportRepository {
        val requests = mutableListOf<LedgerExportRequest>()

        override fun observeAvailableTags(): Flow<List<Tag>> =
            flowOf(listOf(Tag("t1", "工作日"), Tag("t2", "旅行")))

        override fun observeExportPreview(request: LedgerExportRequest): Flow<LedgerExportPreview> {
            requests += request
            val count = if (request.includeVoided) 12 else 10
            return flowOf(
                LedgerExportPreview(
                    transactionCount = count,
                    postingCount = count * 2,
                    accountCount = 5,
                    totalIncomeBase = Money.fromDecimalString("12000.00", Currency.CNY),
                    totalExpenseBase = Money.fromDecimalString("3500.00", Currency.CNY),
                    suggestedFileName = "droplet-ledger-all.${request.format.extension}",
                ),
            )
        }

        override suspend fun exportBytes(request: LedgerExportRequest): LedgerExportArtifact =
            LedgerExportArtifact(
                format = request.format,
                fileName = "droplet-ledger-all.${request.format.extension}",
                mimeType = request.format.mimeType,
                bytes = "sample-bytes".toByteArray(),
                transactionCount = 10,
                postingCount = 20,
                generatedAtEpochMs = 1_700_000_000_000L,
            )

        override suspend fun exportToLocalFile(
            request: LedgerExportRequest,
            outputDirectory: File,
        ): ExportedFileResult {
            val file = File(outputDirectory, "droplet-ledger-all.${request.format.extension}")
            return ExportedFileResult(
                format = request.format,
                file = file,
                fileName = file.name,
                mimeType = request.format.mimeType,
                sizeBytes = 2048L,
                transactionCount = 10,
                postingCount = 20,
                generatedAtEpochMs = 1_700_000_000_000L,
            )
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun formatScopeValidationAndLocalExportFlow_workDeterministically() = runTest {
        val repo = FakeExportRepository()
        val vm = DataExportViewModel(repo)
        val collectJob = backgroundScope.launch { vm.uiState.collect {} }

        advanceUntilIdle()
        assertEquals(ExportFormat.XLSX, vm.uiState.value.format)
        assertEquals(10, vm.uiState.value.preview?.transactionCount)

        // Toggle includeVoided -> preview updates immediately
        vm.setIncludeVoided(true)
        advanceUntilIdle()
        assertEquals(12, vm.uiState.value.preview?.transactionCount)

        // Custom range validation rejects end < start
        vm.selectScope(ExportScope.CUSTOM_RANGE)
        vm.updateCustomStartDate("2026-11-20")
        vm.updateCustomEndDate("2026-11-01")
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.customDateError)
        assertFalse(vm.uiState.value.canExport)

        // Fix custom range -> export succeeds
        vm.updateCustomEndDate("2026-11-28")
        vm.selectFormat(ExportFormat.BEANCOUNT)
        advanceUntilIdle()
        assertNull(vm.uiState.value.customDateError)
        assertTrue(vm.uiState.value.canExport)

        var completedName: String? = null
        vm.exportToLocalDirectory(
            outputDirectory = File("/tmp"),
            onCompleted = { completedName = it.fileName },
        )
        advanceUntilIdle()

        assertEquals("droplet-ledger-all.beancount", completedName)
        assertEquals("droplet-ledger-all.beancount", vm.uiState.value.lastExportedFile?.fileName)
        assertTrue(vm.uiState.value.lastExportStatusMessage.orEmpty().contains("droplet-ledger-all.beancount"))

        collectJob.cancel()
    }
}
