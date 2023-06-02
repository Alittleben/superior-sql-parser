package io.github.melin.superior.common.relational.alter

import io.github.melin.superior.common.*
import io.github.melin.superior.common.relational.TableId
import io.github.melin.superior.common.relational.abs.AbsTableStatement
import kotlin.collections.ArrayList

data class AlterTable(
    val alterType: AlterType,
    override val tableId: TableId,
    private val action: AlterAction?,
    val tableType: TableType = TableType.TABLE
): AbsTableStatement() {
    override val statementType = StatementType.ALTER_TABLE
    override val privilegeType = PrivilegeType.ALTER
    override val sqlType = SqlType.DDL

    val actions: ArrayList<AlterAction> = ArrayList()
    var ifExists: Boolean = false

    init {
        if (action != null) {
            actions.add(action)
        }
    }

    constructor(alterType: AlterType, tableId: TableId): this(alterType, tableId, null)

    constructor(alterType: AlterType): this(alterType, TableId("__UNKOWN__"), null)

    fun addActions(list: List<AlterAction>) {
        actions.addAll(list)
    }

    fun firstAction(): AlterAction {
        return actions.first()
    }
}