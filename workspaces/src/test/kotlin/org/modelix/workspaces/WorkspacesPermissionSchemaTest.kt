package org.modelix.workspaces

import com.auth0.jwt.JWT
import org.modelix.authorization.ModelixJWTUtil
import org.modelix.authorization.permissions.PermissionParts
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkspacesPermissionSchemaTest {

    @Test
    fun `viewer can read config`() = runTest(
        listOf(WorkspacesPermissionSchema.workspaces.workspace("123").viewer),
        WorkspacesPermissionSchema.workspaces.workspace("123").config.read,
        true
    )

    @Test
    fun `viewer cannot write config`() = runTest(
        listOf(WorkspacesPermissionSchema.workspaces.workspace("123").viewer),
        WorkspacesPermissionSchema.workspaces.workspace("123").config.write,
        false
    )

    @Test
    fun `workspace owner can read config`() = runTest(
        listOf(WorkspacesPermissionSchema.workspaces.workspace("123").owner),
        WorkspacesPermissionSchema.workspaces.workspace("123").config.read,
        true
    )

    @Test
    fun `modelix-admin can list workspaces`() = runTest(
        listOf(WorkspacesPermissionSchema.workspaces.admin),
        WorkspacesPermissionSchema.workspaces.workspace("123").list,
        true
    )

    private fun runTest(grantedPermissions: List<PermissionParts>, permissionToCheck: PermissionParts, shouldHavePermission: Boolean) {
        val util = ModelixJWTUtil()
        util.setHmac512Key("abc")
        val token = util.createAccessToken("unit-test@example.com", grantedPermissions.map { it.fullId  }).let { JWT.decode(it)  }
        val evaluator = util.createPermissionEvaluator(token, WorkspacesPermissionSchema.SCHEMA)

        assertEquals(shouldHavePermission, evaluator.hasPermission(permissionToCheck))
    }
}