package personal.wuqing.rogue.riscv.translator

import personal.wuqing.rogue.riscv.grammar.RVBlock
import personal.wuqing.rogue.riscv.grammar.RVFunction
import personal.wuqing.rogue.riscv.grammar.RVInstruction
import personal.wuqing.rogue.riscv.grammar.RVRegister

class Liveness private constructor(builder: Builder) {
    constructor(function: RVFunction) : this(Builder(function))

    val conflict: Set<RegisterEdge>
    val coalesce: Set<RVInstruction.Move>

    init {
        val conflict = mutableSetOf<RegisterEdge>()
        val coalesce = mutableSetOf<RVInstruction.Move>()
        for ((block, live) in builder.liveOut) {
            block.instructions.asReversed().forEach { inst ->
                if (inst is RVInstruction.Move) {
                    live -= inst.use
                    coalesce += inst
                }
                live += inst.def
                for (d in inst.def) for (l in live) conflict += RegisterEdge(d, l)
                live -= inst.def
                live += inst.use
                if (inst is RVInstruction.SG) conflict += RegisterEdge(inst.reg, inst.assist)
            }
        }
        conflict.removeIf { RVRegister.ZERO in it || RVRegister.SP in it }
        coalesce.removeIf { (it.def + it.use).any { p -> p == RVRegister.SP || p == RVRegister.ZERO } }
        this.conflict = conflict
        this.coalesce = coalesce
    }

    private class Builder(function: RVFunction) {
        val liveIn = mutableMapOf<RVBlock, MutableSet<RVRegister>>()
        val liveOut = mutableMapOf<RVBlock, MutableSet<RVRegister>>()

        init {
            try {
                val queue = function.body.toMutableSet()
                liveIn += function.body.associateWith { mutableSetOf<RVRegister>() }
                liveOut += function.body.associateWith { mutableSetOf<RVRegister>() }
                val blockInOut = mutableMapOf<RVBlock, Pair<Set<RVRegister>, Set<RVRegister>>>()
                while (queue.isNotEmpty()) {
                    val cur = queue.first()
                    queue.remove(cur)
                    val oldIn = liveIn[cur]!!
                    val newIn = mutableSetOf<RVRegister>()
                    val newOut = cur.next.map { liveIn[it]!! }.flatten().toMutableSet()
                    val (use, def) = blockInOut.computeIfAbsent(cur) {
                        val use = mutableSetOf<RVRegister>()
                        val def = mutableSetOf<RVRegister>()
                        cur.instructions.forEach {
                            use += it.use - def
                            def += it.def
                        }
                        use to def
                    }
                    newIn += use
                    newIn += newOut - def
                    newIn -= setOf(RVRegister.ZERO, RVRegister.SP)
                    liveIn[cur] = newIn
                    liveOut[cur] = newOut
                    if (newIn != oldIn) queue += cur.prev
                }
            } catch (e: NullPointerException) {
                error("null pointer when building block liveness")
            }
        }
    }
}
