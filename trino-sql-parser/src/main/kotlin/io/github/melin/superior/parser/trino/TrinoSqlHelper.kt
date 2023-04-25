package io.github.melin.superior.parser.trino

import io.github.melin.superior.common.relational.StatementData
import io.github.melin.superior.common.StatementType
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.atn.PredictionMode
import org.antlr.v4.runtime.misc.ParseCancellationException
import org.apache.commons.lang3.StringUtils
import io.github.melin.superior.common.antlr4.ParseErrorListener
import io.github.melin.superior.common.antlr4.ParseException
import io.github.melin.superior.parser.trino.antlr4.TrinoSqlBaseLexer
import io.github.melin.superior.parser.trino.antlr4.TrinoSqlBaseParser

/**
 *
 * Created by libinsong on 2018/1/10.
 */
object TrinoSqlHelper {

    @JvmStatic fun checkSupportedSQL(statementType: StatementType): Boolean {
        return when (statementType) {
            StatementType.SHOW -> true
            StatementType.DROP_TABLE -> true
            StatementType.EXPLAIN -> true
            StatementType.SELECT -> true
            StatementType.CREATE_TABLE_AS_SELECT -> true
            else -> false
        }
    }

    @JvmStatic fun getStatementData(command: String) : StatementData? {
        val trimCmd = StringUtils.trim(command)

        val charStream = CaseInsensitiveStream(
            CharStreams.fromString(trimCmd)
        )
        val lexer = TrinoSqlBaseLexer(charStream)
        lexer.removeErrorListeners()
        lexer.addErrorListener(ParseErrorListener())

        val tokenStream = CommonTokenStream(lexer)
        val parser = TrinoSqlBaseParser(tokenStream)
        parser.removeErrorListeners()
        parser.addErrorListener(ParseErrorListener())
        parser.interpreter.predictionMode = PredictionMode.SLL

        val sqlVisitor = TrinoSqlAntlr4Visitor()
        sqlVisitor.setCommand(trimCmd)

        try {
            try {
                // first, try parsing with potentially faster SLL mode
                return sqlVisitor.visit(parser.singleStatement())
            }
            catch (e: ParseCancellationException) {
                tokenStream.seek(0) // rewind input stream
                parser.reset()

                // Try Again.
                parser.interpreter.predictionMode = PredictionMode.LL
                return sqlVisitor.visit(parser.singleStatement())
            }
        } catch (e: ParseException) {
            if(StringUtils.isNotBlank(e.command)) {
                throw e;
            } else {
                throw e.withCommand(trimCmd)
            }
        }
    }
}
