package io.github.melin.superior.common.relational.create

import io.github.melin.superior.common.PrivilegeType
import io.github.melin.superior.common.SqlType
import io.github.melin.superior.common.relational.Statement

class CreateCatalog(
    val catalogName: String,
    var properties: Map<String, String>? = null,
): Statement() {
    override val privilegeType: PrivilegeType = PrivilegeType.CREATE
    override val sqlType: SqlType = SqlType.DDL

    constructor(catalogName: String): this(catalogName, null)
}