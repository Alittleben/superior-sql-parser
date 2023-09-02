package io.github.melin.superior.parser.mysql

import com.github.melin.superior.sql.parser.util.CommonUtils
import io.github.melin.superior.common.*
import io.github.melin.superior.common.relational.*
import io.github.melin.superior.common.relational.alter.*
import io.github.melin.superior.common.relational.common.ShowStatement
import io.github.melin.superior.common.relational.common.UseDatabase
import io.github.melin.superior.common.relational.create.*
import io.github.melin.superior.common.relational.table.ColumnRel
import io.github.melin.superior.common.relational.dml.*
import io.github.melin.superior.common.relational.drop.DropDatabase
import io.github.melin.superior.common.relational.drop.DropTable
import io.github.melin.superior.common.relational.table.TruncateTable
import io.github.melin.superior.parser.mysql.antlr4.MySqlParser
import io.github.melin.superior.parser.mysql.antlr4.MySqlParser.AlterByTruncatePartitionContext
import io.github.melin.superior.parser.mysql.antlr4.MySqlParserBaseVisitor
import org.antlr.v4.runtime.tree.TerminalNodeImpl
import org.apache.commons.lang3.StringUtils

import io.github.melin.superior.common.AlterType.*

/**
 *
 * Created by libinsong on 2018/2/8.
 */
class MySqlAntlr4Visitor(val splitSql: Boolean = false, val command: String?):
    MySqlParserBaseVisitor<Statement>() {

    private var currentOptType: StatementType = StatementType.UNKOWN
    private var limit: Int? = null
    private var offset: Int? = null
    private val primaryKeys = ArrayList<String>()

    private var inputTables: ArrayList<TableId> = arrayListOf()
    private var cteTempTables: ArrayList<TableId> = arrayListOf()

    private var statements: ArrayList<Statement> = arrayListOf()
    private val sqls: ArrayList<String> = arrayListOf()

    fun getSqlStatements(): List<Statement> {
        return statements
    }

    fun getSplitSqls(): List<String> {
        return sqls
    }

    override fun visitSqlStatements(ctx: MySqlParser.SqlStatementsContext): Statement? {
        ctx.sqlStatement().forEach {
            var sql = CommonUtils.subsql(command, it)
            sql = CommonUtils.cleanLastSemi(sql)
            if (splitSql) {
                sqls.add(sql)
            } else {
                val startNode = it.start.text
                val statement = if (StringUtils.equalsIgnoreCase("show", startNode)) {
                    val keyWords: ArrayList<String> = arrayListOf()
                    CommonUtils.findShowStatementKeyWord(keyWords, it)
                    ShowStatement(*keyWords.toTypedArray())
                } else {
                    var statement = this.visitSqlStatement(it)
                    if (statement == null) {
                        statement = DefaultStatement(StatementType.UNKOWN)
                    }
                    statement
                }

                statement.setSql(sql)
                statements.add(statement)

                clean()
            }
        }
        return null
    }

    private fun clean() {
        currentOptType = StatementType.UNKOWN

        limit = null
        offset = null
        inputTables = arrayListOf()
        cteTempTables = arrayListOf()
    }

    //-----------------------------------database-------------------------------------------------

    override fun visitCreateDatabase(ctx: MySqlParser.CreateDatabaseContext): Statement {
        val databaseName = CommonUtils.cleanQuote(ctx.uid().text)
        return CreateDatabase(databaseName)
    }

    override fun visitDropDatabase(ctx: MySqlParser.DropDatabaseContext): Statement {
        val databaseName = CommonUtils.cleanQuote(ctx.uid().text)
        return DropDatabase(databaseName)
    }

    //-----------------------------------table-------------------------------------------------

    override fun visitColumnCreateTable(ctx: MySqlParser.ColumnCreateTableContext): Statement {
        val tableId = parseFullId(ctx.tableName().fullId())
        var comment: String? = null
        ctx.tableOption().forEach {
            when(it) {
                is MySqlParser.TableOptionCommentContext -> {
                    comment = CommonUtils.cleanQuote(it.STRING_LITERAL().text)
                }
            }
        }
        val columnRels = ArrayList<ColumnRel>()

        ctx.createDefinitions().children.forEach { column ->
            if(column is MySqlParser.ColumnDeclarationContext ) {
                val name = CommonUtils.cleanQuote(column.fullColumnName().text)

                var dataType = column.columnDefinition().dataType().getChild(0).text.lowercase()
                val count = column.columnDefinition().dataType().childCount
                if(count > 1) {
                    val item = column.columnDefinition().dataType().getChild(1)
                    if(item is MySqlParser.LengthOneDimensionContext ||
                            item is MySqlParser.LengthTwoDimensionContext ||
                            item is MySqlParser.LengthTwoOptionalDimensionContext) {
                        dataType = dataType + item.text
                    }
                }

                var colComment:String? = null
                column.columnDefinition().columnConstraint().forEach {
                    if(it is MySqlParser.CommentColumnConstraintContext) {
                        colComment = CommonUtils.cleanQuote(it.STRING_LITERAL().text)
                    }
                }
                columnRels.add(ColumnRel(name, dataType, colComment))
            }
        }

        super.visitColumnCreateTable(ctx)

        val ifNotExists: Boolean = if (ctx.ifNotExists() != null) true else false
        columnRels.forEach { columnRel: ColumnRel -> if (primaryKeys.contains(columnRel.columnName)) { columnRel.primaryKey = true } }
        val createTable = CreateTable(tableId, TableType.MYSQL, comment,
                null, null, columnRels, null, null, ifNotExists)

        if (ctx.partitionDefinitions() != null) {
            createTable.partitionType = "PARTITION"
        }

        return createTable
    }

    override fun visitQueryCreateTable(ctx: MySqlParser.QueryCreateTableContext): Statement {
        currentOptType = StatementType.CREATE_TABLE_AS_SELECT
        val tableId = parseFullId(ctx.tableName().fullId())
        var comment: String? = null
        ctx.tableOption().forEach {
            when(it) {
                is MySqlParser.TableOptionCommentContext -> {
                    comment = CommonUtils.cleanQuote(it.STRING_LITERAL().text)
                }
            }
        }

        val ifNotExists: Boolean = if (ctx.ifNotExists() != null) true else false
        val createTable = CreateTableAsSelect(tableId, comment, null, null, null, null, null, ifNotExists)
        super.visitSelectStatement(ctx.selectStatement())
        createTable.inputTables.addAll(inputTables)
        return createTable
    }

    override fun visitPrimaryKeyTableConstraint(ctx: MySqlParser.PrimaryKeyTableConstraintContext): Statement? {
        val count = ctx.indexColumnNames().childCount

        for (i in 1..(count-2)) {
            var column = ctx.indexColumnNames().getChild(i).text
            column = CommonUtils.cleanQuote(column)
            primaryKeys.add(column)
        }

        return null
    }

    override fun visitDropTable(ctx: MySqlParser.DropTableContext): Statement {
        if(ctx.tables().tableName().size > 1) {
            throw SQLParserException("不支持drop多个表")
        }
        val tableId = parseFullId(ctx.tables().tableName(0).fullId())

        val dropTable = DropTable(tableId)
        dropTable.ifExists = if (ctx.ifExists() != null) true else false
        return dropTable
    }

    override fun visitTruncateTable(ctx: MySqlParser.TruncateTableContext): Statement {
        val tableId = parseFullId(ctx.tableName().fullId())
        return TruncateTable(tableId)
    }

    override fun visitRenameTable(ctx: MySqlParser.RenameTableContext): Statement {
        val tableId = parseFullId(ctx.renameTableClause().get(0).tableName(0).fullId())
        val newTableId = parseFullId(ctx.renameTableClause().get(0).tableName(1).fullId())

        val action = RenameTableAction(newTableId)
        return AlterTable(tableId, action)
    }

    override fun visitUseStatement(ctx: MySqlParser.UseStatementContext): Statement {
        val databaseName = ctx.uid().text
        return UseDatabase(databaseName)
    }

    //-----------------------------------Alter-----------------------------------------------

    override fun visitAlterTable(ctx: MySqlParser.AlterTableContext): Statement? {
        if(ctx.childCount > 4) {
            throw SQLParserException("不允许同时执行多个alter")
        }
        val statement = ctx.getChild(3)
        val tableId = parseFullId(ctx.tableName().fullId())
        if (statement is MySqlParser.AlterByChangeColumnContext) {
            val columnName = CommonUtils.cleanQuote(statement.oldColumn.text)
            val newColumnName = CommonUtils.cleanQuote(statement.newColumn.text)
            val dataType = CommonUtils.subsql(command, statement.columnDefinition().dataType())
            var comment:String? = null

            statement.columnDefinition().children.forEach {
                if(it is MySqlParser.CommentColumnConstraintContext) {
                    comment = CommonUtils.cleanQuote(it.STRING_LITERAL().text)
                }
            }

            val action = AlterColumnAction(ALTER_COLUMN, columnName, dataType, comment)
            action.newColumName = newColumnName

            return AlterTable(tableId, action)
        } else if(statement is MySqlParser.AlterByAddColumnContext) {
            val columnName = CommonUtils.cleanQuote(statement.uid().get(0).text)
            val dataType = statement.columnDefinition().dataType().text
            var comment:String? = null
            statement.columnDefinition().children.forEach {
                if(it is MySqlParser.CommentColumnConstraintContext) {
                    comment = CommonUtils.cleanQuote(it.STRING_LITERAL().text)
                }
            }

            val action = AlterColumnAction(ADD_COLUMN, columnName, dataType, comment)
            return AlterTable(tableId, action)
        } else if(statement is MySqlParser.AlterByDropColumnContext) {
            val columnName = CommonUtils.cleanQuote(statement.uid().text)
            val action = DropColumnAction(columnName)
            return AlterTable(tableId, action)
        } else if(statement is MySqlParser.AlterByModifyColumnContext) {
            val columnName = CommonUtils.cleanQuote(statement.uid().get(0).text)
            val dataType = statement.columnDefinition().dataType().text

            val action = AlterColumnAction(ALTER_COLUMN, columnName, dataType)
            return AlterTable(tableId, action)
        } else if(statement is MySqlParser.AlterByAddIndexContext) {
            val createIndex = CreateIndex(statement.uid().text)
            return AlterTable(tableId, createIndex)
        } else if(statement is MySqlParser.AlterByDropIndexContext) {
            val dropIndex = DropIndex(statement.uid().text)
            return AlterTable(tableId, dropIndex)
        } else if(statement is MySqlParser.AlterByAddPrimaryKeyContext) {
            val action = AlterTableAction(ADD_PRIMARY_KEY)
            return AlterTable(tableId, action)
        } else if(statement is MySqlParser.AlterByAddUniqueKeyContext) {
            val action = AlterTableAction(ADD_UNIQUE_KEY)
            return AlterTable(tableId, action)
        } else if(statement is MySqlParser.AlterPartitionContext) {
            val alterPartition = statement.alterPartitionSpecification()
            if (alterPartition is AlterByTruncatePartitionContext) {
                val action = AlterTableAction(TRUNCATE_PARTITION)
                return AlterTable(tableId, action)
            } else if(alterPartition is MySqlParser.AlterByDropPartitionContext) {
                val action = AlterTableAction(DROP_PARTITION)
                return AlterTable(tableId, action)
            } else if(alterPartition is MySqlParser.AlterByAddPartitionContext) {
                val action = AlterTableAction(ADD_PARTITION)
                return AlterTable(tableId, action)
            }
        }

        return super.visitAlterTable(ctx)
    }

    override fun visitAnalyzeTable(ctx: MySqlParser.AnalyzeTableContext): Statement {
        val tables = ArrayList<TableId>()
        ctx.tables().tableName().forEach { context ->
            val tableId = parseFullId(context.fullId())
            tables.add(tableId)
        }

        return AnalyzeTable(tables)
    }

    //-----------------------------------DML-------------------------------------------------

    override fun visitDmlStatement(ctx: MySqlParser.DmlStatementContext): Statement {
        if (ctx.withStatement() != null) {
            super.visitWithStatement(ctx.withStatement())
            if (ctx.withStatement().selectStatement() != null) {
                return this.visitSelectStatement(ctx.withStatement().selectStatement())
            }
        }

        return super.visitDmlStatement(ctx);
    }

    override fun visitSelectStatement(ctx: MySqlParser.SelectStatementContext): Statement {
        if (currentOptType == StatementType.UNKOWN) {
            currentOptType = StatementType.SELECT
        }
        super.visitSelectStatement(ctx)
        return QueryStmt(inputTables, limit, offset)
    }

    override fun visitInsertStatement(ctx: MySqlParser.InsertStatementContext): Statement {
        val tableId = parseFullId(ctx.tableName().fullId())

        currentOptType = StatementType.INSERT
        val insertTable = InsertTable(InsertMode.INTO, tableId)
        if (ctx.insertStatementValue().selectStatement() != null) {
            super.visit(ctx.insertStatementValue().selectStatement())
        }
        insertTable.inputTables.addAll(inputTables)
        return insertTable
    }

    override fun visitReplaceStatement(ctx: MySqlParser.ReplaceStatementContext): Statement {
        val tableId = parseFullId(ctx.tableName().fullId())

        currentOptType = StatementType.INSERT
        val insertTable = InsertTable(InsertMode.INTO, tableId)
        if (ctx.insertStatementValue().selectStatement() != null) {
            super.visit(ctx.insertStatementValue().selectStatement())
            insertTable.mysqlReplace = true
        }
        insertTable.inputTables.addAll(inputTables)
        return insertTable
    }

    override fun visitDeleteStatement(ctx: MySqlParser.DeleteStatementContext): Statement {
        currentOptType = StatementType.DELETE

        val deleteTable = if (ctx.multipleDeleteStatement() != null) {
            this.visit(ctx.multipleDeleteStatement().expression())

            val outputTables = ctx.multipleDeleteStatement().tableName().map { parseFullId(it.fullId()) }
            val deleteTable = DeleteTable(outputTables.first(), inputTables)
            super.visitTableSources(ctx.multipleDeleteStatement().tableSources())
            deleteTable.outputTables.addAll(outputTables.subList(1, outputTables.size))
            deleteTable
        } else {
            if (ctx.singleDeleteStatement().expression() != null) {
                this.visit(ctx.singleDeleteStatement().expression())
            }

            val tableId = parseFullId(ctx.singleDeleteStatement().tableName().fullId())
            DeleteTable(tableId, inputTables)
        }

        return deleteTable
    }

    override fun visitUpdateStatement(ctx: MySqlParser.UpdateStatementContext): Statement {
        currentOptType = StatementType.UPDATE
        val updateTable = if (ctx.multipleUpdateStatement() != null) {
            val intputTableIds = inputTables.toMutableList()
            this.visit(ctx.multipleUpdateStatement().expression())
            inputTables.clear()
            super.visitTableSources(ctx.multipleUpdateStatement().tableSources())
            UpdateTable(inputTables.first(), inputTables.toMutableList())
            val updateTable = UpdateTable(inputTables.first(), intputTableIds)
            updateTable.outputTables.addAll(inputTables.subList(1, inputTables.size))
            updateTable
        } else {
            this.visit(ctx.singleUpdateStatement().expression())
            val tableId = parseFullId(ctx.singleUpdateStatement().tableName().fullId())
            UpdateTable(tableId, inputTables)
        }

        return updateTable
    }

    override fun visitCreateIndex(ctx: MySqlParser.CreateIndexContext): Statement {
        val tableId = parseFullId(ctx.tableName().fullId())
        val createIndex = CreateIndex(ctx.uid().text)
        return AlterTable(tableId, createIndex)
    }

    override fun visitDropIndex(ctx: MySqlParser.DropIndexContext): Statement {
        val tableId = parseFullId(ctx.tableName().fullId())
        val dropIndex = DropIndex(ctx.uid().text)
        return AlterTable(tableId, dropIndex)
    }

    //-----------------------------------private method-------------------------------------------------

    override fun visitCommonTableExpressions(ctx: MySqlParser.CommonTableExpressionsContext): Statement? {
        val tableId = TableId(ctx.cteName().text)
        cteTempTables.add(tableId)

        super.visitCommonTableExpressions(ctx)
        return null
    }

    override fun visitTableName(ctx: MySqlParser.TableNameContext): Statement? {
        if (StatementType.SELECT == currentOptType ||
            StatementType.INSERT == currentOptType ||
            StatementType.UPDATE == currentOptType ||
            StatementType.DELETE == currentOptType ||
            StatementType.CREATE_TABLE_AS_SELECT == currentOptType) {

            val tableId = parseFullId(ctx.fullId())

            if (!inputTables.contains(tableId) && !cteTempTables.contains(tableId)) {
                inputTables.add(tableId)
            }
        }
        return null
    }

    override fun visitLimitClause(ctx: MySqlParser.LimitClauseContext): Statement? {
        if (ctx.limit.decimalLiteral() != null) {
            limit = ctx.limit.text.toInt()
        }
        if (ctx.offset.decimalLiteral() != null) {
            offset = ctx.offset.text.toInt()
        }
        return super.visitLimitClause(ctx)
    }

    private fun parseFullId(fullId: MySqlParser.FullIdContext): TableId {
        var databaseName: String? = null
        var tableName = ""

        if (fullId.childCount == 2) {
            databaseName = fullId.uid().get(0).text
            tableName = (fullId.getChild(1) as TerminalNodeImpl).text.substring(1)
        } else if(fullId.childCount == 3) {
            databaseName = CommonUtils.cleanQuote(fullId.uid().get(0).text)
            tableName = CommonUtils.cleanQuote((fullId.getChild(2) as MySqlParser.UidContext).text)
        } else {
            tableName = fullId.uid().get(0).text
        }

        if (databaseName != null) {
            databaseName = CommonUtils.cleanQuote(databaseName)
        }
        tableName = CommonUtils.cleanQuote(tableName)

        return TableId(databaseName, tableName);
    }
}
