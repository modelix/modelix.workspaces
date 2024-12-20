package org.modelix.workspace.manager

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.modelix.workspaces.Credentials
import org.modelix.workspaces.GitRepository
import org.modelix.workspaces.Workspace

class WorkspaceManagerModuleTest {

    @Test
    fun `credential values are masked`() {
        val workspaceConfig = Workspace(
            id = "aId",
            gitRepositories = listOf(
                GitRepository(
                    url = "aUrl",
                    credentials = Credentials(
                        user = "aUser",
                        password = "aPassword",
                    ),
                ),
            ),
        )

        val maskedWorkspaceConfig = workspaceConfig.maskCredentials()
        val maskedCredentials = maskedWorkspaceConfig.gitRepositories.single().credentials!!

        assertEquals(MASKED_CREDENTIAL_VALUE, maskedCredentials.user)
        assertEquals(MASKED_CREDENTIAL_VALUE, maskedCredentials.password)
    }

    @Test
    fun `previous credentials are not used if credentials where removed`() {
        val existingWorkspaceConfig = Workspace(
            id = "aId",
            gitRepositories = listOf(
                GitRepository(
                    url = "aUrl",
                    credentials = Credentials(
                        user = "aUser",
                        password = "aPassword",
                    ),
                ),
            ),
        )
        val newWorkspaceConfig = Workspace(
            id = "aId",
            gitRepositories = listOf(
                GitRepository(
                    url = "aUrl",
                ),
            ),
        )

        val mergedWorkspaceConfig = mergeMaskedCredentialsWithPreviousCredentials(newWorkspaceConfig, existingWorkspaceConfig)

        assertEquals(newWorkspaceConfig, mergedWorkspaceConfig)
    }

    @Test
    fun `masked credentials are ignored if no previous repository exist`() {
        val existingWorkspaceConfig = Workspace(id = "aId")
        val newWorkspaceConfig = Workspace(
            id = "aId",
            gitRepositories = listOf(
                GitRepository(
                    url = "aUrl",
                    credentials = Credentials(
                        user = "aUser",
                        password = MASKED_CREDENTIAL_VALUE,
                    ),
                ),
            ),
        )

        val mergedWorkspaceConfig = mergeMaskedCredentialsWithPreviousCredentials(newWorkspaceConfig, existingWorkspaceConfig)

        assertEquals(
            Workspace(
                id = "aId",
                gitRepositories = listOf(
                    GitRepository(
                        url = "aUrl",
                    ),
                ),
            ),
            mergedWorkspaceConfig,
        )
    }

    @Test
    fun `new credentials are used if no previous repository exist`() {
        val existingWorkspaceConfig = Workspace(id = "aId")
        val newWorkspaceConfig = Workspace(
            id = "aId",
            gitRepositories = listOf(
                GitRepository(
                    url = "aUrl",
                    credentials = Credentials(
                        user = "aUser",
                        password = "aPassword",
                    ),
                ),
            ),
        )

        val mergedWorkspaceConfig = mergeMaskedCredentialsWithPreviousCredentials(newWorkspaceConfig, existingWorkspaceConfig)

        val expectedWorkspaceConfig = Workspace(
            id = "aId",
            gitRepositories = listOf(
                GitRepository(
                    url = "aUrl",
                    credentials = Credentials(
                        user = "aUser",
                        password = "aPassword",
                    ),
                ),
            ),
        )
        assertEquals(expectedWorkspaceConfig, mergedWorkspaceConfig)
    }

    @Test
    fun `previous credentials are removed when URL changes`() {
        val existingWorkspaceConfig = Workspace(
            id = "aId",
            gitRepositories = listOf(
                GitRepository(
                    url = "aUrl",
                    credentials = Credentials(
                        user = "aUser",
                        password = "aPassword",
                    ),
                ),
            ),
        )
        val newWorkspaceConfig = Workspace(
            id = "aId",
            gitRepositories = listOf(
                GitRepository(
                    url = "aUrl2",
                    credentials = Credentials(
                        user = MASKED_CREDENTIAL_VALUE,
                        password = "aPassword",
                    ),
                ),
            ),
        )

        val mergedWorkspaceConfig = mergeMaskedCredentialsWithPreviousCredentials(newWorkspaceConfig, existingWorkspaceConfig)
        val expectedWorkspaceConfig = Workspace(
            id = "aId",
            gitRepositories = listOf(
                GitRepository(
                    url = "aUrl2",
                ),
            ),
        )
        assertEquals(expectedWorkspaceConfig, mergedWorkspaceConfig)
    }

    @Test
    fun `masked credentials are replaced with previous credentials`() {
        val existingWorkspaceConfig = Workspace(
            id = "aId",
            gitRepositories = listOf(
                GitRepository(
                    url = "aUrl",
                    credentials = Credentials(
                        user = "aUser",
                        password = "aPassword",
                    ),
                ),
                GitRepository(
                    url = "aUrl2",
                    credentials = Credentials(
                        user = "aUser2",
                        password = "aPassword2",
                    ),
                ),
            ),
        )
        val maskedWorkspace = existingWorkspaceConfig.maskCredentials()

        val mergedWorkspaceConfig =
            mergeMaskedCredentialsWithPreviousCredentials(maskedWorkspace, existingWorkspaceConfig)

        assertEquals(existingWorkspaceConfig, mergedWorkspaceConfig)
    }

    @Test
    fun `new credentials are not replaced with previous credentials`() {
        val existingWorkspaceConfig = Workspace(
            id = "aId",
            gitRepositories = listOf(
                GitRepository(
                    url = "aUrl",
                    credentials = Credentials(
                        user = "aUser",
                        password = "aPassword",
                    ),
                ),
            ),
        )
        val newWorkspaceConfig = Workspace(
            id = "aId",
            gitRepositories = listOf(
                GitRepository(
                    url = "aUrl",
                    credentials = Credentials(
                        user = "aUser2",
                        password = "aPassword2",
                    ),
                ),
            ),
        )

        val mergedWorkspaceConfig =
            mergeMaskedCredentialsWithPreviousCredentials(newWorkspaceConfig, existingWorkspaceConfig)

        assertEquals(newWorkspaceConfig, mergedWorkspaceConfig)
    }
}
