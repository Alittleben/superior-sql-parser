package io.github.melin.superior.common.relational.create

import io.github.melin.superior.common.PrivilegeType
import io.github.melin.superior.common.SqlType
import io.github.melin.superior.common.StatementType
import io.github.melin.superior.common.relational.AbsTableStatement
import io.github.melin.superior.common.relational.TableId
import io.github.melin.superior.common.relational.table.ColumnRel

data class CreateMaterializedView(
    override val tableId: TableId,
    var querySql: String? = null,
    val comment: String? = null,
    var ifNotExists: Boolean = false, //是否存在 if not exists 关键字
    var columnRels: List<ColumnRel>? = null
) : AbsTableStatement() {
    override val statementType = StatementType.CREATE_MATERIALIZED_VIEW
    override val privilegeType = PrivilegeType.CREATE
    override val sqlType = SqlType.DDL

    var properties: Map<String, String> = mapOf()
    var inputTables: ArrayList<TableId> = arrayListOf()
}
