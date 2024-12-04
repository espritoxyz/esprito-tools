package org.usvm.machine.types.dp

import org.ton.TlbCompositeLabel
import org.ton.TlbStructure
import org.usvm.machine.TvmContext
import org.usvm.mkSizeAddExpr
import org.usvm.mkSizeExpr

fun calculateDataLengths(
    ctx: TvmContext,
    labelsWithoutUnknowns: Collection<TlbCompositeLabel>,
    individualMaxCellTlbDepth: Map<TlbCompositeLabel, Int>,
): List<Map<TlbCompositeLabel, AbstractSizeExpr>> =
    calculateMapsByTlbDepth(ctx.tvmOptions.tlbOptions.maxTlbDepth, labelsWithoutUnknowns) { label, curDepth, prevDepthValues ->
        val tlbDepthBound = individualMaxCellTlbDepth[label]
            ?: error("individualMaxCellTlbDepth must be calculated for all labels")

        if (tlbDepthBound >= curDepth) {
            getDataLength(ctx, label.internalStructure, prevDepthValues)
        } else {
            prevDepthValues[label]
        }
    }

/**
 * If returns null, construction of the given depth is impossible.
 * */
private fun getDataLength(
    ctx: TvmContext,
    struct: TlbStructure,
    lengthsFromPreviousDepth: Map<TlbCompositeLabel, AbstractSizeExpr>,
): AbstractSizeExpr? = with(ctx) {
    when (struct) {
        is TlbStructure.Unknown -> {
            error("Cannot calculate length for Unknown leaf")
        }

        is TlbStructure.Empty -> {
            AbstractSizeExpr { zeroSizeExpr }
        }

        is TlbStructure.LoadRef -> {
            getDataLength(ctx, struct.rest, lengthsFromPreviousDepth)
        }

        is TlbStructure.KnownTypePrefix -> {
            val furtherWithoutShift = getDataLength(ctx, struct.rest, lengthsFromPreviousDepth)
                ?: return null  // cannot construct with given depth

            val offset = getKnownTypePrefixDataLength(struct, lengthsFromPreviousDepth)
                ?: return null  // cannot construct with given depth

            furtherWithoutShift.shiftAndAdd(offset)
        }

        is TlbStructure.SwitchPrefix -> {
            val switchSize = struct.switchSize
            val childLengths = struct.variants.mapNotNull { (key, variant) ->

                val furtherWithoutShift = getDataLength(ctx, variant, lengthsFromPreviousDepth)
                    ?: return@mapNotNull null  // cannot construct this variant with given depth

                val condition = generateSwitchGuard(switchSize, key)

                condition to furtherWithoutShift.shift(switchSize)
            }

            if (childLengths.isEmpty()) {
                return null  // cannot construct with given depth
            }

            var childIte = childLengths.first().second  // arbitrary value
            childLengths.subList(1, childLengths.size).forEach { (condition, value) ->
                val prev = childIte
                childIte = AbstractSizeExpr { param ->
                    mkIte(condition.apply(param), value.apply(param), prev.apply(param))
                }
            }

            AbstractSizeExpr {
                mkSizeAddExpr(childIte.apply(it), mkSizeExpr(switchSize))
            }
        }
    }
}
