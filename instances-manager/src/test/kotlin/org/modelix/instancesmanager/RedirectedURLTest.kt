package org.modelix.instancesmanager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RedirectedURLTest {

    fun createRedirectUrlToPort(portString: String, pathAfterPort: String): RedirectedURL {
        return RedirectedURL(
            remainingPath = "/port/$portString$pathAfterPort",
            workspaceReference = "workspace",
            sharedInstanceName = "own",
            instanceName = null,
            userToken = null
        )
    }

    @Test
    fun `create redirect URL for valid port number with path after port`() {
        val redirectedURL =  createRedirectUrlToPort("65535", "/some_path")
        assertEquals("http://workspace:65535/some_path", redirectedURL.getURLToRedirectTo(false))
    }

    @Test
    fun `create redirect URL for valid port number without path after port`() {
        val redirectedURL =  createRedirectUrlToPort("65535", "")
        assertEquals("http://workspace:65535", redirectedURL.getURLToRedirectTo(false))
    }


    @Test
    fun `do no create redirect URL for port that is out of range`() {
        val redirectedURL =  createRedirectUrlToPort("65536", "")
        assertNull(redirectedURL.getURLToRedirectTo(false))
    }

    @Test
    fun `do no create redirect URL for port that is not a number`() {
        val redirectedURL =  createRedirectUrlToPort("not_a_number", "")
        assertNull(redirectedURL.getURLToRedirectTo(false))
    }
}