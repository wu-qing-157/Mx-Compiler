package personal.wuqing.mxcompiler.utils

open class ErrorRecorderException : Exception()

object OptionErrorRecorder {
    fun fatalError(msg: String) = LogPrinter.println("${ErrorType.Fatal} $msg")
    fun unsupported(msg: String) = LogPrinter.println("${ErrorType.Unsupported} $msg")
    fun warning(msg: String) = LogPrinter.println("${ErrorType.Warning} $msg")
    fun info(msg: String) = LogPrinter.println("${ErrorType.Info} $msg")
}

object ParserErrorRecorder {
    object Exception : ErrorRecorderException()

    private var error = false
    fun report() = if (error) throw Exception else Unit
    fun exception(location: Location, msg: String) = LogPrinter.println("$location ${ErrorType.Error} $msg").also {
        error = true
    }

    fun fatalException(msg: String): Nothing {
        LogPrinter.println("${ErrorType.Fatal} $msg")
        throw Exception
    }
}

object ASTErrorRecorder {
    object Exception : ErrorRecorderException()

    private var error = false
    fun report() = if (error) throw Exception else Unit
    // fun warning(location: Location, msg: String) = LogPrinter.println("$location ${ErrorType.Warning} $msg")
    fun error(location: Location, msg: String) = LogPrinter.println("$location ${ErrorType.Error} $msg").also {
        error = true
    }
}

object SemanticErrorRecorder {
    object Exception : ErrorRecorderException()

    private var error = false
    fun report() = if (error) throw Exception else Unit
    fun info(msg: String) = LogPrinter.println("${ErrorType.Info} $msg")
    // fun warning(location: Location, msg: String) = LogPrinter.println("$location ${ErrorType.Warning} $msg")
    fun error(location: Location, msg: String) = LogPrinter.println("$location ${ErrorType.Error} $msg").also {
        error = true
    }
}