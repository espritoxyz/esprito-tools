package org.usvm.machine.interpreter

import org.ton.bytecode.ACTIONS_PARAMETER_IDX
import org.ton.bytecode.ADDRESS_PARAMETER_IDX
import org.ton.bytecode.BALANCE_PARAMETER_IDX
import org.ton.bytecode.BLOCK_TIME_PARAMETER_IDX
import org.ton.bytecode.CODE_PARAMETER_IDX
import org.ton.bytecode.CONFIG_PARAMETER_IDX
import org.ton.bytecode.MSGS_SENT_PARAMETER_IDX
import org.ton.bytecode.SEED_PARAMETER_IDX
import org.ton.bytecode.TAG_PARAMETER_IDX
import org.ton.bytecode.TIME_PARAMETER_IDX
import org.ton.bytecode.TRANSACTION_TIME_PARAMETER_IDX
import org.ton.bytecode.TvmAppConfigConfigoptparamInst
import org.ton.bytecode.TvmAppConfigGetparamInst
import org.ton.bytecode.TvmAppConfigInst
import org.usvm.machine.TvmContext
import org.usvm.machine.TvmStepScopeManager
import org.usvm.machine.state.addInt
import org.usvm.machine.state.addOnStack
import org.usvm.machine.state.addTuple
import org.usvm.machine.state.allocSliceFromCell
import org.usvm.machine.state.configContainsParam
import org.usvm.machine.state.consumeDefaultGas
import org.usvm.machine.state.doWithStateCtx
import org.usvm.machine.state.getCellContractInfoParam
import org.usvm.machine.state.getConfigParam
import org.usvm.machine.state.getContractInfoParam
import org.usvm.machine.state.getIntContractInfoParam
import org.usvm.machine.state.newStmt
import org.usvm.machine.state.nextStmt
import org.usvm.machine.state.takeLastIntOrThrowTypeError
import org.usvm.machine.types.TvmCellType
import org.usvm.machine.types.TvmSliceType

class TvmConfigInterpreter(private val ctx: TvmContext) {
    fun visitConfigInst(scope: TvmStepScopeManager, stmt: TvmAppConfigInst) {
        scope.consumeDefaultGas(stmt)

        when (stmt) {
            is TvmAppConfigGetparamInst -> visitGetParamInst(scope, stmt)
            is TvmAppConfigConfigoptparamInst -> visitConfigParamInst(scope, stmt)
            else -> TODO("$stmt")
        }
    }

    private fun visitGetParamInst(scope: TvmStepScopeManager, stmt: TvmAppConfigGetparamInst) {
        scope.doWithStateCtx {
            val i = stmt.i

            when (i) {
                TAG_PARAMETER_IDX -> { // TAG
                    val tag = scope.getIntContractInfoParam(i)
                        ?: return@doWithStateCtx

                    stack.addInt(tag)
                }
                ACTIONS_PARAMETER_IDX -> { // ACTIONS
                    val actionNum = scope.getIntContractInfoParam(i)
                        ?: return@doWithStateCtx

                    stack.addInt(actionNum)
                }
                MSGS_SENT_PARAMETER_IDX -> { // MSGS_SENT
                    val messagesSent = scope.getIntContractInfoParam(i)
                        ?: return@doWithStateCtx

                    stack.addInt(messagesSent)
                }
                TIME_PARAMETER_IDX -> { // NOW
                    val now = scope.getIntContractInfoParam(i)
                        ?: return@doWithStateCtx

                    stack.addInt(now)
                }
                BLOCK_TIME_PARAMETER_IDX -> { // BLOCK_LTIME
                    val blockLogicalTime = scope.getIntContractInfoParam(i)
                        ?: return@doWithStateCtx

                    stack.addInt(blockLogicalTime)
                }
                TRANSACTION_TIME_PARAMETER_IDX -> { // LTIME
                    val logicalTime = scope.getIntContractInfoParam(i)
                        ?: return@doWithStateCtx

                    stack.addInt(logicalTime)
                }
                SEED_PARAMETER_IDX -> { // RAND_SEED
                    val randomSeed = scope.getIntContractInfoParam(i)
                        ?: return@doWithStateCtx

                    stack.addInt(randomSeed)
                }
                BALANCE_PARAMETER_IDX -> { // BALANCE
                    val balanceValue = getContractInfoParam(i).tupleValue
                        ?: return@doWithStateCtx ctx.throwTypeCheckError(this)

                    stack.addTuple(balanceValue)
                }
                ADDRESS_PARAMETER_IDX -> { // MYADDR
                    val cell = scope.getCellContractInfoParam(i)
                        ?: return@doWithStateCtx

                    val slice = scope.calcOnState { allocSliceFromCell(cell) }
                    addOnStack(slice, TvmSliceType)
                }
                CONFIG_PARAMETER_IDX -> { // GLOBAL_CONFIG
                    val cell = scope.getCellContractInfoParam(i)
                        ?: return@doWithStateCtx

                    addOnStack(cell, TvmCellType)
                }
                CODE_PARAMETER_IDX -> { // MYCODE
                    val cell = getContractInfoParam(i).cellValue
                        ?: return@doWithStateCtx ctx.throwTypeCheckError(this)

                    addOnStack(cell, TvmCellType)
                }
                else -> TODO("$i GETPARAM")
            }

            newStmt(stmt.nextStmt())
        }
    }

    private fun visitConfigParamInst(scope: TvmStepScopeManager, stmt: TvmAppConfigConfigoptparamInst) = with(ctx) {
        val idx = scope.takeLastIntOrThrowTypeError() ?: return@with

        val absIdx = mkIte(mkBvSignedGreaterOrEqualExpr(idx, zeroValue), idx, mkBvNegationExpr(idx))

        val configContainsIdx = scope.calcOnState { configContainsParam(absIdx) }
        scope.assert(
            configContainsIdx,
            unsatBlock = { error("Config doesn't contain idx: $absIdx") },
        ) ?: return@with

        val result = scope.getConfigParam(absIdx)
            ?: return@with

        scope.doWithState {
            scope.addOnStack(result, TvmCellType)
            newStmt(stmt.nextStmt())
        }
    }
}