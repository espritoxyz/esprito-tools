package org.usvm.test.resolver

import org.ton.TlbResolvedBuiltinLabel
import org.ton.bytecode.MethodId
import org.ton.bytecode.TvmAppGasAcceptInst
import org.ton.bytecode.TvmInst
import org.ton.bytecode.TvmMethod
import org.usvm.machine.interpreter.TvmInterpreter.Companion.logger
import org.usvm.machine.state.ContractId
import org.usvm.machine.tryCatchIf
import org.usvm.machine.state.TvmMethodResult.TvmFailure
import org.usvm.machine.state.TvmState
import org.usvm.machine.types.TvmStructuralExit

data object TvmTestResolver {
    fun resolve(method: TvmMethod, state: TvmState): TvmSymbolicTest =
        resolve(method.id, state)

    fun resolve(methodId: MethodId, state: TvmState): TvmSymbolicTest {
        val model = state.models.first()
        val ctx = state.ctx
        val stateResolver = TvmTestStateResolver(ctx, model, state, ctx.tvmOptions.performAdditionalChecksWhileResolving)

        val usedParameters = stateResolver.resolveParameters()
        val contractAddress = stateResolver.resolveContractAddress()
        val contractBalance = stateResolver.resolveContractBalance()
        val time = stateResolver.resolveTime()
        val initialData = stateResolver.resolveInitialData()
        val result = stateResolver.resolveResultStack()
        val gasUsage = stateResolver.resolveGasUsage()
        val externalMessageWasAccepted = state.pathNode.allStatements.any { it is TvmAppGasAcceptInst }

        return TvmSymbolicTest(
            methodId = methodId,
            contractAddress = contractAddress,
            initialData = initialData,
            contractBalance = contractBalance,
            time = time,
            usedParameters = usedParameters,
            result = result,
            stackTrace = state.continuationStack,
            gasUsage = gasUsage,
            additionalFlags = state.additionalFlags,
            numberOfAddressesWithAssertedDataConstraints = state.cellDataFieldManager.getCellsWithAssertedCellData().size,
            intercontractPath = state.intercontractPath,
            externalMessageWasAccepted = externalMessageWasAccepted,
        )
    }

    fun groupTestSuites(
        testSuites: List<TvmSymbolicTestSuite>,
        takeEmptyTests: Boolean = false,
    ): TvmContractSymbolicTestResult = TvmContractSymbolicTestResult(
        testSuites.mapNotNull {
            it.takeIf { takeEmptyTests || it.tests.isNotEmpty() }
        }
    )

    fun resolveSingleMethod(
        methodId: MethodId,
        states: List<TvmState>,
        coverage: TvmMethodCoverage,
    ): TvmSymbolicTestSuite {
        val tests = states.mapNotNull { state ->
            tryCatchIf(
                condition = state.ctx.tvmOptions.quietMode,
                body = { resolve(methodId, state) },
                exceptionHandler = { exception ->
                    logger.debug(exception) { "Exception is thrown during the resolve of state $state" }
                    null
                }
            )
        }

        return TvmSymbolicTestSuite(
            methodId,
            coverage,
            tests,
        )
    }
}

data class TvmContractSymbolicTestResult(val testSuites: List<TvmSymbolicTestSuite>) : List<TvmSymbolicTestSuite> by testSuites

data class TvmSymbolicTestSuite(
    val methodId: MethodId,
    val methodCoverage: TvmMethodCoverage,
    val tests: List<TvmSymbolicTest>,
) : List<TvmSymbolicTest> by tests

data class TvmMethodCoverage(
    val coverage: Float?,
    val transitiveCoverage: Float?,
    val coverageOfMainMethod: Float,
)

data class TvmSymbolicTest(
    val methodId: MethodId,
    val contractAddress: TvmTestDataCellValue,
    val contractBalance: TvmTestIntegerValue,
    val time: TvmTestIntegerValue,
    val initialData: TvmTestCellValue,
    val usedParameters: List<TvmTestValue>,
    val result: TvmMethodSymbolicResult,
    val externalMessageWasAccepted: Boolean,
    val stackTrace: List<TvmInst>,
    val gasUsage: Int,
    val additionalFlags: Set<String>,
    val intercontractPath: List<ContractId>,
    val numberOfAddressesWithAssertedDataConstraints: Int,  // for testing
)

sealed interface TvmMethodSymbolicResult {
    val stack: List<TvmTestValue>
}

sealed interface TvmTerminalMethodSymbolicResult : TvmMethodSymbolicResult {
    val exitCode: UInt
}

data class TvmMethodFailure(
    val failure: TvmFailure,
    val lastStmt: TvmInst,
    override val exitCode: UInt,
    override val stack: List<TvmTestValue>
) : TvmTerminalMethodSymbolicResult

data class TvmSuccessfulExecution(
    override val exitCode: UInt,
    override val stack: List<TvmTestValue>,
) : TvmTerminalMethodSymbolicResult

data class TvmExecutionWithStructuralError(
    val lastStmt: TvmInst,
    override val stack: List<TvmTestValue>,
    val exit: TvmStructuralExit<TvmTestCellDataTypeRead, TlbResolvedBuiltinLabel>,
) : TvmMethodSymbolicResult