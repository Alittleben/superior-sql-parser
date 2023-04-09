package io.github.melin.superior.common.relational

import io.github.melin.superior.common.PrivilegeType

data class AnalyzeTable (
    val tableId: TableId
) : Statement() {
    override val privilegeType: PrivilegeType = PrivilegeType.READ
}

data class AnalyzeSchema (
    val namespaceId: NamespaceId
) : Statement() {
    override val privilegeType: PrivilegeType = PrivilegeType.READ
}