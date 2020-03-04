package personal.wuqing.mxcompiler.llvm

import personal.wuqing.mxcompiler.ast.ASTNode
import personal.wuqing.mxcompiler.ast.ReferenceType
import personal.wuqing.mxcompiler.grammar.Function
import personal.wuqing.mxcompiler.grammar.Operation
import personal.wuqing.mxcompiler.grammar.PrefixOperator
import personal.wuqing.mxcompiler.grammar.SuffixOperator
import personal.wuqing.mxcompiler.grammar.Type
import personal.wuqing.mxcompiler.grammar.Variable

object Translator {
    private val type = mutableMapOf<Type, LLVMType>()
    private val function = mutableMapOf<Function, LLVMFunction>()
    private val global = mutableMapOf<Variable, LLVMGlobal>()
    private val literal = mutableMapOf<String, LLVMGlobal>()

    operator fun get(t: Type): LLVMType = type[t] ?: when (t) {
        Type.Primitive.Int -> LLVMType.I32.also { type[t] = it }
        Type.Primitive.Bool -> LLVMType.I1.also { type[t] = it }
        Type.Primitive.String -> LLVMType.string.also { type[t] = it }
        Type.Null -> LLVMType.I32.also { type[t] = it }
        Type.Void -> LLVMType.Void.also { type[t] = it }
        Type.Unknown -> throw Exception("type <unknown> found after semantic")
        is Type.Class -> LLVMType.Class(t.name)
            .also { type[t] = LLVMType.Pointer(it) }
            .apply { init(MemberArrangement(t)) }
        is Type.Array -> LLVMType.Pointer(this[t.base]).also { type[t] = it }
    }

    private fun Function.llvmName() = when (this) {
        is Function.Top -> if (name == "main") "main" else "__toplevel__.$name"
        is Function.Member -> "$base.$name"
        Function.Builtin.Print -> "__print__"
        Function.Builtin.Println -> "__println__"
        Function.Builtin.PrintInt -> "__printInt__"
        Function.Builtin.PrintlnInt -> "__printlnInt__"
        Function.Builtin.GetString -> "__getString__"
        Function.Builtin.GetInt -> "__getInt__"
        Function.Builtin.ToString -> "__toString__"
        Function.Builtin.StringLength -> "__string__length__"
        Function.Builtin.StringParseInt -> "__string__parseInt__"
        is Function.Builtin.ArraySize -> "__array__size__"
        is Function.Builtin.DefaultConstructor -> "__empty__"
        Function.Builtin.StringOrd -> "__string__ord__"
        Function.Builtin.StringSubstring -> "__string__substring__"
        is Function.Builtin -> name // string binary operators
    }

    private operator fun get(f: Function) = function[f] ?: if (f is Function.Builtin)
        (when (f) {
            Function.Builtin.Print -> LLVMFunction.External.Print
            Function.Builtin.Println -> LLVMFunction.External.Println
            Function.Builtin.PrintInt -> LLVMFunction.External.PrintInt
            Function.Builtin.PrintlnInt -> LLVMFunction.External.PrintlnInt
            Function.Builtin.GetString -> LLVMFunction.External.GetString
            Function.Builtin.GetInt -> LLVMFunction.External.GetInt
            Function.Builtin.ToString -> LLVMFunction.External.ToString
            Function.Builtin.StringLength -> LLVMFunction.External.StringLength
            Function.Builtin.StringParseInt -> LLVMFunction.External.StringParseInt
            is Function.Builtin.ArraySize -> LLVMFunction.External.ArraySize
            is Function.Builtin.DefaultConstructor -> LLVMFunction.External.Empty
            Function.Builtin.StringOrd -> LLVMFunction.External.StringOrd
            Function.Builtin.StringSubstring -> LLVMFunction.External.StringSubstring
            Function.Builtin.StringConcatenate -> LLVMFunction.External.StringConcatenate
            Function.Builtin.StringEqual -> LLVMFunction.External.StringEqual
            Function.Builtin.StringNeq -> LLVMFunction.External.StringNeq
            Function.Builtin.StringLess -> LLVMFunction.External.StringLess
            Function.Builtin.StringLeq -> LLVMFunction.External.StringLeq
            Function.Builtin.StringGreater -> LLVMFunction.External.StringGreater
            Function.Builtin.StringGeq -> LLVMFunction.External.StringGeq
        }).also { function[f] = it }
    else
        when (f) {
            is Function.Top -> LLVMFunction.Declared(
                this[f.result], f.llvmName(), f.parameters.map { this[it] }, f.def
            ).also { function[f] = it }.also { this(f.def) }
            is Function.Member -> LLVMFunction.Declared(
                this[f.result], f.llvmName(),
                (f.parameters + f.base).map { this[it] }, f.def
            ).also { function[f] = it }.also { this(f.def) }
            is Function.Builtin -> throw Exception("declared function resolved as builtin")
        }

    operator fun get(g: Variable) = global[g] ?: LLVMGlobal(g.name, this[g.type], LLVMName.Const(0)).also {
        global[g] = it
    }

    private var literalCount = 0
    private operator fun get(s: String) = literal[s] ?: LLVMGlobal(
        "__literal__.${literalCount++}",
        LLVMType.Vector(s.length + 5, LLVMType.I8),
        LLVMName.Literal(s.length, "$s\u0000")
    ).also { literal[s] = it }

    operator fun invoke(ast: ASTNode.Program, main: Function): LLVMProgram {
        this[main]
        return LLVMProgram(
            struct = type.values.filterIsInstance<LLVMType.Class>(),
            global = global.values + literal.values,
            function = function.values.filterIsInstance<LLVMFunction.Declared>(),
            external = function.values.filterIsInstance<LLVMFunction.External>()
        )
    }

    private var localCount = 0
    private fun nextName() = LLVMName.Local(".${localCount++}")
    private val local = mutableMapOf<Variable, Pair<LLVMType, LLVMName.Local>>()
    private val localBlocks = mutableListOf<LLVMBlock>()
    private val currentBlock get() = localBlocks.last()
    private val loopTarget = mutableMapOf<ASTNode.Statement.Loop, Pair<LLVMBlock, LLVMBlock>>()
    private var thisReference: Pair<LLVMType.Class, LLVMName>? = null

    private operator fun plusAssign(statement: LLVMStatement) {
        if (currentBlock.statements.lastOrNull()?.let { it is LLVMStatement.Terminating } != true)
            currentBlock.statements += statement
    }

    private operator fun plusAssign(block: LLVMBlock) {
        localBlocks += block
    }

    private data class Value(val type: LLVMType, val lvalue: Boolean, val name: LLVMName) {
        fun rvalue() = if (lvalue) nextName().also {
            Translator += LLVMStatement.Load(it, type, LLVMType.Pointer(type), name)
        } else name
    }

    private operator fun invoke(ast: ASTNode.Expression): Value = when (ast) {
        is ASTNode.Expression.NewObject -> this(ast)
        is ASTNode.Expression.NewArray -> this(ast)
        is ASTNode.Expression.MemberAccess -> this(ast)
        is ASTNode.Expression.MemberFunction -> this(ast)
        is ASTNode.Expression.Function -> this(ast)
        is ASTNode.Expression.Index -> this(ast)
        is ASTNode.Expression.Suffix -> this(ast)
        is ASTNode.Expression.Prefix -> this(ast)
        is ASTNode.Expression.Binary -> this(ast)
        is ASTNode.Expression.Ternary -> this(ast)
        is ASTNode.Expression.Identifier -> this(ast)
        is ASTNode.Expression.This -> thisReference?.let { Value(it.first, false, it.second) }
            ?: throw Exception("unresolved this after semantic")
        is ASTNode.Expression.Constant.Int -> Value(LLVMType.I32, false, LLVMName.Const(ast.value))
        is ASTNode.Expression.Constant.String -> this(ast)
        is ASTNode.Expression.Constant.True -> Value(LLVMType.I1, false, LLVMName.Const(1))
        is ASTNode.Expression.Constant.False -> Value(LLVMType.I1, false, LLVMName.Const(0))
        is ASTNode.Expression.Constant.Null -> Value(LLVMType.I32, false, LLVMName.Const(0))
    }

    private operator fun invoke(ast: ASTNode.Expression.NewObject): Value {
        val type = ast.baseType.type as? Type.Class ?: throw Exception("new non-class type found after semantic")
        val llvmType = this[type] as? LLVMType.Class ?: throw Exception("class type mapped to non-class LLVM type")
        val size = llvmType.members.size
        val name = nextName()
        this += LLVMStatement.Call(
            result = name,
            type = LLVMType.string,
            name = LLVMFunction.External.Malloc.name,
            args = listOf(LLVMType.I32 to LLVMName.Const(size))
        )
        // TODO : convert void ptr to class ptr
        val constructor =
            ast.baseType.type.functions["__constructor__"] ?: throw Exception("constructor not found after semantic")
        if (constructor !is Function.Builtin.DefaultConstructor) {
            this += LLVMStatement.Call(
                result = null,
                type = LLVMType.Void,
                name = this[constructor].name,
                args = listOf(LLVMType.Pointer(llvmType) to name)
            )
        }
        return Value(llvmType, true, name)
    }

    private operator fun invoke(ast: ASTNode.Expression.NewArray): Value = TODO("ast")

    private operator fun invoke(ast: ASTNode.Expression.MemberAccess): Value {
        val parent = this(ast.parent).rvalue()
        val parentType = this[ast.parent.type] as? LLVMType.Class
            ?: throw Exception("access member of non-class type found after semantic")
        val variable = ast.reference
        val resultType = this[variable.type]
        val index = parentType.members.delta[variable] ?: throw Exception("member not arranged")
        val name = nextName()
        this += LLVMStatement.Element(
            name, resultType, parentType, parent, listOf(
                LLVMType.I32 to LLVMName.Const(0),
                LLVMType.I32 to LLVMName.Const(index)
            )
        )
        return Value(resultType, true, name)
    }

    private operator fun invoke(ast: ASTNode.Expression.MemberFunction): Value {
        val parent = this(ast.base).rvalue()
        val args = ast.parameters.map { this(it) }.map { it.type to it.rvalue() }
        val name = nextName()
        val baseType = ast.base.type
        val resultType = this[ast.type]
        this += LLVMStatement.Call(
            if (resultType != LLVMType.Void) name else null, resultType, this[ast.reference].name,
            args + (this[baseType] to parent)
        )
        return Value(resultType, false, name)
    }

    private operator fun invoke(ast: ASTNode.Expression.Index): Value {
        val parent = this(ast.parent).rvalue()
        val index = this(ast.child).rvalue()
        val llvmType = this[ast.type]
        val name = nextName()
        this += LLVMStatement.Element(
            name, llvmType, LLVMType.Pointer(llvmType), parent, listOf(LLVMType.I32 to index)
        )
        return Value(llvmType, true, name)
    }

    private operator fun invoke(ast: ASTNode.Expression.Function): Value {
        val args = ast.parameters.map { this(it) }.map { it.type to it.rvalue() }.let {
            if (ast.reference.base != null)
                it + (thisReference ?: throw Exception("unresolved this after semantic"))
            else
                it
        }
        val name = nextName()
        val llvmType = this[ast.type]
        this += LLVMStatement.Call(
            if (llvmType != LLVMType.Void) name else null, llvmType, this[ast.reference].name, args
        )
        return Value(llvmType, false, name)
    }

    private operator fun invoke(ast: ASTNode.Expression.Suffix): Value {
        val operand = this(ast.operand)
        val name = operand.rvalue()
        val after = nextName()
        this += LLVMStatement.ICalc(
            after, when (ast.operator) {
                SuffixOperator.INC -> ICalcOperator.ADD
                SuffixOperator.DEC -> ICalcOperator.SUB
            }, LLVMType.I32, operand.rvalue(), LLVMName.Const(1)
        )
        this += LLVMStatement.Store(after, LLVMType.I32, operand.name, LLVMType.Pointer(LLVMType.I32))
        return Value(LLVMType.I32, false, name)
    }

    private operator fun invoke(ast: ASTNode.Expression.Prefix): Value {
        val operand = this(ast.operand)
        val lvalue = operand.name
        val rvalue = operand.rvalue()
        val name = nextName()
        return when (ast.operator) {
            PrefixOperator.INC -> {
                this += LLVMStatement.ICalc(name, ICalcOperator.ADD, LLVMType.I32, rvalue, LLVMName.Const(1))
                this += LLVMStatement.Store(name, LLVMType.I32, lvalue, LLVMType.Pointer(LLVMType.I32))
                Value(LLVMType.I32, true, lvalue)
            }
            PrefixOperator.DEC -> {
                this += LLVMStatement.ICalc(name, ICalcOperator.SUB, LLVMType.I32, rvalue, LLVMName.Const(1))
                this += LLVMStatement.Store(name, LLVMType.I32, lvalue, LLVMType.Pointer(LLVMType.I32))
                Value(LLVMType.I32, true, lvalue)
            }
            PrefixOperator.L_NEG -> {
                this += LLVMStatement.ICmp(name, IComOperator.NE, LLVMType.I1, rvalue, LLVMName.Const(0))
                Value(LLVMType.I1, false, name)
            }
            PrefixOperator.INV -> {
                this += LLVMStatement.ICalc(name, ICalcOperator.XOR, LLVMType.I32, rvalue, LLVMName.Const(-1))
                Value(LLVMType.I32, false, name)
            }
            PrefixOperator.POS -> {
                this += LLVMStatement.Store(name, LLVMType.I32, rvalue, LLVMType.Pointer(LLVMType.I32))
                Value(LLVMType.I32, false, name)
            }
            PrefixOperator.NEG -> {
                this += LLVMStatement.ICalc(name, ICalcOperator.SUB, LLVMType.I32, LLVMName.Const(0), rvalue)
                Value(LLVMType.I32, false, name)
            }
        }
    }

    private operator fun invoke(ast: ASTNode.Expression.Binary): Value {
        val operation = ast.operator.operation(ast.lhs.type, ast.rhs.type)
            ?: throw Exception("unknown operation of binary operator after semantic")
        val lhs = this(ast.lhs)
        val ll = lhs.name
        val lr = lhs.rvalue()
        when (operation) {
            Operation.BAnd -> TODO()
            Operation.BOr -> TODO()
        }
        val rhs = this(ast.rhs)
        val rr = rhs.rvalue()
        val name = nextName()
        return when (operation) {
            Operation.Plus -> {
                this += LLVMStatement.ICalc(name, ICalcOperator.ADD, LLVMType.I32, lr, rr)
                Value(LLVMType.I32, false, name)
            }
            Operation.SPlus -> {
                this += LLVMStatement.Call(
                    name, LLVMType.string, this[Function.Builtin.StringConcatenate].name, listOf(
                        LLVMType.string to lr, LLVMType.string to rr
                    )
                )
                Value(LLVMType.string, false, name)
            }
            Operation.Minus -> {
                this += LLVMStatement.ICalc(name, ICalcOperator.SUB, LLVMType.I32, lr, rr)
                Value(LLVMType.I32, false, name)
            }
            Operation.Times -> {
                this += LLVMStatement.ICalc(name, ICalcOperator.MUL, LLVMType.I32, lr, rr)
                Value(LLVMType.I32, false, name)
            }
            Operation.Div -> {
                this += LLVMStatement.ICalc(name, ICalcOperator.SDIV, LLVMType.I32, lr, rr)
                Value(LLVMType.I32, false, name)
            }
            Operation.Rem -> {
                this += LLVMStatement.ICalc(name, ICalcOperator.SREM, LLVMType.I32, lr, rr)
                Value(LLVMType.I32, false, name)
            }
            Operation.BAnd, Operation.BOr -> throw Exception("should already handled")
            Operation.IAnd -> {
                this += LLVMStatement.ICalc(name, ICalcOperator.AND, LLVMType.I32, lr, rr)
                Value(LLVMType.I32, false, name)
            }
            Operation.IOr -> {
                this += LLVMStatement.ICalc(name, ICalcOperator.OR, LLVMType.I32, lr, rr)
                Value(LLVMType.I32, false, name)
            }
            Operation.Xor -> {
                this += LLVMStatement.ICalc(name, ICalcOperator.XOR, LLVMType.I32, lr, rr)
                Value(LLVMType.I32, false, name)
            }
            Operation.Shl -> {
                this += LLVMStatement.ICalc(name, ICalcOperator.SHL, LLVMType.I32, lr, rr)
                Value(LLVMType.I32, false, name)
            }
            Operation.UShr -> {
                this += LLVMStatement.ICalc(name, ICalcOperator.LSHR, LLVMType.I32, lr, rr)
                Value(LLVMType.I32, false, name)
            }
            Operation.Shr -> {
                this += LLVMStatement.ICalc(name, ICalcOperator.ASHR, LLVMType.I32, lr, rr)
                Value(LLVMType.I32, false, name)
            }
            Operation.Less -> {
                this += LLVMStatement.ICmp(name, IComOperator.SLT, LLVMType.I32, lr, rr)
                Value(LLVMType.I1, false, name)
            }
            Operation.SLess -> {
                val result = nextName()
                this += LLVMStatement.Call(
                    result, LLVMType.I8, this[Function.Builtin.StringLess].name, listOf(
                        LLVMType.string to lr, LLVMType.string to rr
                    )
                )
                this += LLVMStatement.ICmp(name, IComOperator.NE, LLVMType.I8, result, LLVMName.Const(0))
                Value(LLVMType.I1, false, name)
            }
            Operation.Leq -> {
                this += LLVMStatement.ICmp(name, IComOperator.SLE, LLVMType.I32, lr, rr)
                Value(LLVMType.I1, false, name)
            }
            Operation.SLeq -> {
                val result = nextName()
                this += LLVMStatement.Call(
                    result, LLVMType.I8, this[Function.Builtin.StringLeq].name, listOf(
                        LLVMType.string to lr, LLVMType.string to rr
                    )
                )
                this += LLVMStatement.ICmp(name, IComOperator.NE, LLVMType.I8, result, LLVMName.Const(0))
                Value(LLVMType.I1, false, name)
            }
            Operation.Greater -> {
                this += LLVMStatement.ICmp(name, IComOperator.SGT, LLVMType.I32, lr, rr)
                Value(LLVMType.I1, false, name)
            }
            Operation.SGreater -> {
                val result = nextName()
                this += LLVMStatement.Call(
                    result, LLVMType.I8, this[Function.Builtin.StringGreater].name, listOf(
                        LLVMType.string to lr, LLVMType.string to rr
                    )
                )
                this += LLVMStatement.ICmp(name, IComOperator.NE, LLVMType.I8, result, LLVMName.Const(0))
                Value(LLVMType.I1, false, name)
            }
            Operation.Geq -> {
                this += LLVMStatement.ICmp(name, IComOperator.SGE, LLVMType.I32, lr, rr)
                Value(LLVMType.I1, false, name)
            }
            Operation.SGeq -> {
                val result = nextName()
                this += LLVMStatement.Call(
                    result, LLVMType.I8, this[Function.Builtin.StringGeq].name, listOf(
                        LLVMType.string to lr, LLVMType.string to rr
                    )
                )
                this += LLVMStatement.ICmp(name, IComOperator.NE, LLVMType.I8, result, LLVMName.Const(0))
                Value(LLVMType.I1, false, name)
            }
            Operation.BEqual -> {
                this += LLVMStatement.ICmp(name, IComOperator.EQ, LLVMType.I1, lr, rr)
                Value(LLVMType.I1, false, name)
            }
            Operation.IEqual -> {
                this += LLVMStatement.ICmp(name, IComOperator.EQ, LLVMType.I32, lr, rr)
                Value(LLVMType.I1, false, name)
            }
            Operation.SEqual -> {
                val result = nextName()
                this += LLVMStatement.Call(
                    result, LLVMType.I8, this[Function.Builtin.StringEqual].name, listOf(
                        LLVMType.string to lr, LLVMType.string to rr
                    )
                )
                this += LLVMStatement.ICmp(name, IComOperator.NE, LLVMType.I8, result, LLVMName.Const(0))
                Value(LLVMType.I1, false, name)
            }
            Operation.BNeq -> {
                this += LLVMStatement.ICmp(name, IComOperator.NE, LLVMType.I1, lr, rr)
                Value(LLVMType.I1, false, name)
            }
            Operation.INeq -> {
                this += LLVMStatement.ICmp(name, IComOperator.NE, LLVMType.I32, lr, rr)
                Value(LLVMType.I1, false, name)
            }
            Operation.SNeq -> {
                val result = nextName()
                this += LLVMStatement.Call(
                    result, LLVMType.I8, this[Function.Builtin.StringNeq].name, listOf(
                        LLVMType.string to lr, LLVMType.string to rr
                    )
                )
                this += LLVMStatement.ICmp(name, IComOperator.NE, LLVMType.I8, result, LLVMName.Const(0))
                Value(LLVMType.I1, false, name)
            }
            Operation.BAssign -> {
                this += LLVMStatement.Store(rr, LLVMType.I1, ll, LLVMType.string)
                Value(LLVMType.Void, false, LLVMName.Void)
            }
            Operation.IAssign -> {
                this += LLVMStatement.Store(rr, LLVMType.I32, ll, LLVMType.Pointer(LLVMType.I32))
                Value(LLVMType.Void, false, LLVMName.Void)
            }
            Operation.SAssign -> {
                this += LLVMStatement.Store(rr, LLVMType.string, ll, LLVMType.Pointer(LLVMType.string))
                Value(LLVMType.Void, false, LLVMName.Void)
            }
            Operation.PlusI -> {
                this += LLVMStatement.ICalc(name, ICalcOperator.ADD, LLVMType.I32, lr, rr)
                this += LLVMStatement.Store(name, LLVMType.I32, ll, LLVMType.Pointer(LLVMType.I32))
                Value(LLVMType.Void, false, LLVMName.Void)
            }
            Operation.SPlusI -> {
                this += LLVMStatement.Call(
                    name, LLVMType.string, LLVMFunction.External.StringConcatenate.name, listOf(
                        LLVMType.string to lr, LLVMType.string to rr
                    )
                )
                this += LLVMStatement.Store(name, LLVMType.string, ll, LLVMType.Pointer(LLVMType.string))
                Value(LLVMType.Void, false, LLVMName.Void)
            }
            Operation.MinusI -> {
                this += LLVMStatement.ICalc(name, ICalcOperator.SUB, LLVMType.I32, lr, rr)
                this += LLVMStatement.Store(name, LLVMType.I32, ll, LLVMType.Pointer(LLVMType.I32))
                Value(LLVMType.Void, false, LLVMName.Void)
            }
            Operation.TimesI -> {
                this += LLVMStatement.ICalc(name, ICalcOperator.MUL, LLVMType.I32, lr, rr)
                this += LLVMStatement.Store(name, LLVMType.I32, ll, LLVMType.Pointer(LLVMType.I32))
                Value(LLVMType.Void, false, LLVMName.Void)
            }
            Operation.DivI -> {
                this += LLVMStatement.ICalc(name, ICalcOperator.SDIV, LLVMType.I32, lr, rr)
                this += LLVMStatement.Store(name, LLVMType.I32, ll, LLVMType.Pointer(LLVMType.I32))
                Value(LLVMType.Void, false, LLVMName.Void)
            }
            Operation.RemI -> {
                this += LLVMStatement.ICalc(name, ICalcOperator.SREM, LLVMType.I32, lr, rr)
                this += LLVMStatement.Store(name, LLVMType.I32, ll, LLVMType.Pointer(LLVMType.I32))
                Value(LLVMType.Void, false, LLVMName.Void)
            }
            Operation.AndI -> {
                this += LLVMStatement.ICalc(name, ICalcOperator.AND, LLVMType.I32, lr, rr)
                this += LLVMStatement.Store(name, LLVMType.I32, ll, LLVMType.Pointer(LLVMType.I32))
                Value(LLVMType.Void, false, LLVMName.Void)
            }
            Operation.OrI -> {
                this += LLVMStatement.ICalc(name, ICalcOperator.OR, LLVMType.I32, lr, rr)
                this += LLVMStatement.Store(name, LLVMType.I32, ll, LLVMType.Pointer(LLVMType.I32))
                Value(LLVMType.Void, false, LLVMName.Void)
            }
            Operation.XorI -> {
                this += LLVMStatement.ICalc(name, ICalcOperator.XOR, LLVMType.I32, lr, rr)
                this += LLVMStatement.Store(name, LLVMType.I32, ll, LLVMType.Pointer(LLVMType.I32))
                Value(LLVMType.Void, false, LLVMName.Void)
            }
            Operation.ShlI -> {
                this += LLVMStatement.ICalc(name, ICalcOperator.SHL, LLVMType.I32, lr, rr)
                this += LLVMStatement.Store(name, LLVMType.I32, ll, LLVMType.Pointer(LLVMType.I32))
                Value(LLVMType.Void, false, LLVMName.Void)
            }
            Operation.UShrI -> {
                this += LLVMStatement.ICalc(name, ICalcOperator.LSHR, LLVMType.I32, lr, rr)
                this += LLVMStatement.Store(name, LLVMType.I32, ll, LLVMType.Pointer(LLVMType.I32))
                Value(LLVMType.Void, false, LLVMName.Void)
            }
            Operation.ShrI -> {
                this += LLVMStatement.ICalc(name, ICalcOperator.ASHR, LLVMType.I32, lr, rr)
                this += LLVMStatement.Store(name, LLVMType.I32, ll, LLVMType.Pointer(LLVMType.I32))
                Value(LLVMType.Void, false, LLVMName.Void)
            }
            is Operation.PEqual -> {
                val llvmType = LLVMType.Pointer(this[ast.lhs.type])
                this += LLVMStatement.ICmp(name, IComOperator.EQ, llvmType, lr, rr)
                Value(LLVMType.I1, false, name)
            }
            is Operation.PNeq -> {
                val llvmType = LLVMType.Pointer(this[ast.lhs.type])
                this += LLVMStatement.ICmp(name, IComOperator.NE, llvmType, ll, rr)
                Value(LLVMType.I1, false, name)
            }
            is Operation.PAssign -> TODO()
        }
    }

    private operator fun invoke(ast: ASTNode.Expression.Ternary): Value {
        val cond = this(ast.condition).rvalue()
        val id = localCount++
        val then = LLVMBlock("__then__.$id")
        val els = LLVMBlock("__else__.$id")
        val end = LLVMBlock("__end__.$id")
        this += LLVMStatement.Branch(LLVMType.I1, cond, then.name, els.name)
        this += then
        val thenValue = this(ast.then).rvalue()
        this += LLVMStatement.Jump(end.name)
        this += els
        val elsValue = this(ast.els).rvalue()
        this += LLVMStatement.Jump(end.name)
        this += end
        val name = nextName()
        this += LLVMStatement.Phi(name, this[ast.type], listOf(thenValue to then.name, elsValue to els.name))
        return Value(this[ast.type], false, name)
    }

    private operator fun invoke(ast: ASTNode.Expression.Identifier): Value {
        val (type, variable) = ast.reference
        return when (type) {
            ReferenceType.Variable ->
                local[variable]?.let {
                    Value(it.first, true, it.second)
                } ?: this[variable].let {
                    Value(it.type, true, it.name)
                }
            ReferenceType.Member -> {
                val (thisType, thisName) = thisReference ?: throw Exception("unresolved identifier after semantic")
                val index = thisType.members.delta[variable] ?: throw Exception("member not arranged")
                val resultType = this[variable.type]
                val name = nextName()
                this += LLVMStatement.Element(
                    name, resultType, thisType, thisName, listOf(
                        LLVMType.I32 to LLVMName.Const(0),
                        LLVMType.I32 to LLVMName.Const(index)
                    )
                )
                Value(resultType, true, name)
            }
        }
    }

    private operator fun invoke(ast: ASTNode.Expression.Constant.String): Value {
        val global = this[ast.value]
        val name = nextName()
        this += LLVMStatement.Element(
            name, global.type, LLVMType.Pointer(global.type), global.name, listOf(
                LLVMType.I32 to LLVMName.Const(0),
                LLVMType.I32 to LLVMName.Const(4)
            )
        )
        return Value(LLVMType.string, false, name)
    }

    private operator fun invoke(ast: ASTNode.Statement) {
        when (ast) {
            is ASTNode.Statement.Empty -> Unit
            is ASTNode.Statement.Block -> ast.statements.forEach { this(it) }
            is ASTNode.Statement.Expression -> this(ast.expression)
            is ASTNode.Statement.Variable -> this(ast)
            is ASTNode.Statement.If -> this(ast)
            is ASTNode.Statement.Loop.While -> this(ast)
            is ASTNode.Statement.Loop.For -> this(ast)
            is ASTNode.Statement.Continue -> this += LLVMStatement.Jump(
                loopTarget[ast.loop]?.first?.name ?: throw Exception("loop target is uninitialized unexpectedly")
            )
            is ASTNode.Statement.Break -> this += LLVMStatement.Jump(
                loopTarget[ast.loop]?.second?.name ?: throw Exception("loop target is uninitialized unexpectedly")
            )
            is ASTNode.Statement.Return -> this += if (ast.expression == null)
                LLVMStatement.Ret(LLVMType.Void, null)
            else
                LLVMStatement.Ret(this[ast.expression.type], this(ast.expression).rvalue())
        }
    }

    private operator fun invoke(ast: ASTNode.Statement.Variable) {
        ast.variables.forEach {
            val name = LLVMName.Local(it.name)
            val type = this[it.type.type]
            local[it.actual] = type to name
            this += LLVMStatement.Alloca(name, type)
            if (it.init != null) {
                val initName = this(it.init).rvalue()
                this += LLVMStatement.Store(initName, type, name, LLVMType.Pointer(type))
            }
        }
    }

    private operator fun invoke(ast: ASTNode.Statement.If) {
        val id = localCount++
        val then = LLVMBlock("__then__.$id")
        val els = LLVMBlock("__else__.$id")
        val end = LLVMBlock("__end__.$id")
        val condition = this(ast.condition).rvalue()
        this += LLVMStatement.Branch(LLVMType.I1, condition, then.name, if (ast.els == null) els.name else end.name)
        this += then
        this(ast.then)
        this += LLVMStatement.Jump(end.name)
        if (ast.els != null) {
            this += els
            this(ast.then)
            this += LLVMStatement.Jump(end.name)
        }
        this += end
    }

    private operator fun invoke(ast: ASTNode.Statement.Loop.While) {
        val id = localCount++
        val cond = LLVMBlock("__condition__.$id")
        val body = LLVMBlock("__body__.$id")
        val end = LLVMBlock("__end__.$id")
        this += LLVMStatement.Jump(cond.name)
        this += cond
        val condition = this(ast.condition).rvalue()
        this += LLVMStatement.Branch(LLVMType.I1, condition, body.name, end.name)
        this += body
        this(ast.statement)
        this += LLVMStatement.Jump(cond.name)
        this += end
        loopTarget[ast] = cond to end
    }

    private operator fun invoke(ast: ASTNode.Statement.Loop.For) {
        if (ast.init != null) this(ast.init)
        val id = localCount++
        val cond = LLVMBlock("__condition__.$id")
        val body = LLVMBlock("__body__.$id")
        val end = LLVMBlock("__end__.$id")
        val step = LLVMBlock("__step__.$id")
        this += LLVMStatement.Jump(cond.name)
        this += cond
        val condition = this(ast.condition).rvalue()
        this += LLVMStatement.Branch(LLVMType.I1, condition, body.name, end.name)
        this += body
        this(ast.statement)
        this += LLVMStatement.Jump(cond.name)
        this += step
        ast.step?.let { this(it) }
        this += end
        loopTarget[ast] = step to end
    }

    operator fun invoke(ast: ASTNode.Declaration.Function): List<LLVMBlock> {
        localCount = ast.parameterList.size
        localBlocks.clear()
        this += LLVMBlock("__entry__")
        ast.parameterList.forEach {
            val variable = it.actual
            val type = this[variable.type]
            val parameter = LLVMName.Local("__p__.${variable.name}")
            val name = nextName()
            this += LLVMStatement.Alloca(name, type)
            this += LLVMStatement.Store(parameter, type, name, LLVMType.Pointer(type))
            local[variable] = type to name
        }
        for (statement in ast.body.statements) this(statement)
        this += LLVMStatement.Ret(LLVMType.Void, null)
        return localBlocks.toList().apply {
            forEach {
                if (it.statements.last() !is LLVMStatement.Terminating)
                    throw Exception("unterminated block found unexpectedly")
            }
        }
    }
}
