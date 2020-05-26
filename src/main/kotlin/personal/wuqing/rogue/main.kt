package personal.wuqing.rogue

import personal.wuqing.rogue.ast.ASTBuilder
import personal.wuqing.rogue.ast.ASTMain
import personal.wuqing.rogue.io.OutputMethod
import personal.wuqing.rogue.ir.IRPrinter
import personal.wuqing.rogue.ir.grammar.IRProgram
import personal.wuqing.rogue.ir.translator.TopLevelTranslator
import personal.wuqing.rogue.optimize.ArithmeticOptimization
import personal.wuqing.rogue.optimize.CommonSubexpressionElimination
import personal.wuqing.rogue.optimize.ConstantBranchElimination
import personal.wuqing.rogue.optimize.ConstantPropagation
import personal.wuqing.rogue.optimize.DeadCodeElimination
import personal.wuqing.rogue.optimize.FunctionInline
import personal.wuqing.rogue.optimize.GlobalLocalization
import personal.wuqing.rogue.optimize.LoopOptimization
import personal.wuqing.rogue.optimize.Mem2Reg
import personal.wuqing.rogue.option.OptionMain
import personal.wuqing.rogue.option.Target
import personal.wuqing.rogue.parser.ParserMain
import personal.wuqing.rogue.riscv.RVPrinter
import personal.wuqing.rogue.riscv.translator.RVTranslator
import personal.wuqing.rogue.semantic.SemanticMain
import personal.wuqing.rogue.utils.ANSI
import personal.wuqing.rogue.utils.ASTErrorRecorder
import personal.wuqing.rogue.utils.ErrorRecorderException
import personal.wuqing.rogue.utils.InternalExceptionRecorder
import personal.wuqing.rogue.utils.LogRecorder
import personal.wuqing.rogue.utils.ParserErrorRecorder
import personal.wuqing.rogue.utils.SemanticErrorRecorder
import java.io.FileWriter
import java.io.IOException
import java.io.InputStream
import kotlin.system.exitProcess

const val PROJECT_NAME = "Rogue-Mx"
val USAGE = ANSI.bold("mxc <sourcefiles> [options]")
const val VERSION = "0.9"
var DEBUG = false; private set

fun main(arguments: Array<String>) {
    when (val result = OptionMain(arguments)) {
        is OptionMain.Result.Exit -> exitProcess(result.exit)
        is OptionMain.Result.FromSource -> {
            val (input, output, source, target) = result.apply { DEBUG = debug }
            fromSource(input, output, source, target)
        }
    }
}

var debugIRCount = 0
fun debugIR(ir: IRProgram, description: String) {
    if (DEBUG) FileWriter("debug/IR${debugIRCount++}.rogue").use {
        it.write("; Current Step: $description\n")
        it.write(IRPrinter(ir))
    }
}

fun fromSource(input: InputStream, output: OutputMethod, source: String, target: Target) {
    try {
        val parser = ParserMain(input, source)
        val root = ASTMain(parser, source)
        ParserErrorRecorder.report()
        SemanticMain(root)
        ASTErrorRecorder.report()
        SemanticErrorRecorder.report()
        if (DEBUG) LogRecorder("semantic passed successfully")

        if (target == Target.SEMANTIC) return

        val ir = TopLevelTranslator(root, SemanticMain.getMain())
        debugIR(ir, "IR Gen")

        Mem2Reg(ir)
        debugIR(ir, "SSA")

        DeadCodeElimination(ir)
        debugIR(ir, "Dead Code Elimination")

        FunctionInline(ir)
        debugIR(ir, "Function Inline")

        ConstantPropagation(ir)
        ArithmeticOptimization(ir)
        ConstantBranchElimination(ir)
        DeadCodeElimination(ir)
        debugIR(ir, "Constant Propagation")

        FunctionInline(ir)
        debugIR(ir, "Function Inline (again)")

        GlobalLocalization(ir)
        Mem2Reg(ir)
        debugIR(ir, "Global Localization")

        ConstantPropagation(ir)
        ArithmeticOptimization(ir)
        ConstantBranchElimination(ir)
        DeadCodeElimination(ir)
        debugIR(ir, "Constant Propagation (again)")

        LoopOptimization(ir)
        ConstantPropagation(ir)
        debugIR(ir, "Loop Optimization")

        CommonSubexpressionElimination(ir)
        debugIR(ir, "Common Subexpression Elimination")

        val rv = RVTranslator(ir)

        if (DEBUG) FileWriter("debug/result.s").use {
            it.write(RVPrinter(rv))
        }

        output(RVPrinter(rv))
    } catch (ast: ASTBuilder.Exception) {
        try {
            ParserErrorRecorder.report()
            ast.printStackTrace()
            exitProcess(3)
        } catch (parse: ErrorRecorderException) {
            exitProcess(parse.exit)
        }
    } catch (e: ErrorRecorderException) {
        exitProcess(e.exit)
    } catch (e: NotImplementedError) {
        InternalExceptionRecorder(e)
        exitProcess(InternalExceptionRecorder.exit)
    } catch (e: IllegalStateException) {
        InternalExceptionRecorder(e)
        exitProcess(InternalExceptionRecorder.exit)
    } catch (e: IOException) {
        exitProcess(2)
    }
}
