package org.modelix.services.gitconnector

import io.ktor.util.encodeBase64
import org.eclipse.jgit.api.GitCommand
import org.eclipse.jgit.api.TransportCommand
import org.eclipse.jgit.transport.Transport
import org.eclipse.jgit.transport.TransportHttp
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.transport.http.HttpConnection
import org.eclipse.jgit.transport.http.HttpConnectionFactory
import org.eclipse.jgit.transport.http.JDKHttpConnection
import org.eclipse.jgit.transport.http.JDKHttpConnectionFactory
import java.net.Authenticator
import java.net.HttpURLConnection
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.URL

fun <C : GitCommand<T>, T, E : TransportCommand<C, T>> E.applyCredentials(username: String, password: String): E {
    val cmd = this
    cmd.setCredentialsProvider(UsernamePasswordCredentialsProvider(username, password))
    cmd.setTransportConfigCallback { transport ->
        if (transport is TransportHttp) {
            // https://learn.microsoft.com/en-us/azure/devops/organizations/accounts/use-personal-access-tokens-to-authenticate?view=azure-devops&tabs=Linux#use-a-pat
            transport.setAdditionalHeaders(
                mapOf(
                    "Authorization" to "Basic ${(username.orEmpty() + ":" + password).encodeBase64()}",
                ),
            )
        }
        transport?.setAuthenticator(object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication {
                return PasswordAuthentication(username, password.toCharArray())
            }
        })
    }
    return cmd
}

/**
 * The credentialsProvider only works with WWW-Authenticate: Basic, but not with WWW-Authenticate: Negotiate.
 * This is handled by the JDK.
 */
private fun Transport.setAuthenticator(authenticator: Authenticator) {
    val transport = this as TransportHttp
    val originalFactory = transport.httpConnectionFactory as JDKHttpConnectionFactory
    transport.httpConnectionFactory = object : HttpConnectionFactory {
        override fun create(url: URL?): HttpConnection {
            return modify(originalFactory.create(url))
        }

        override fun create(url: URL?, proxy: Proxy?): HttpConnection {
            return modify(originalFactory.create(url, proxy))
        }

        fun modify(conn: HttpConnection): HttpConnection {
            val jdkConn = conn as JDKHttpConnection
            val field = jdkConn.javaClass.getDeclaredField("wrappedUrlConnection")
            field.isAccessible = true
            val wrapped = field.get(jdkConn) as HttpURLConnection
            wrapped.setAuthenticator(authenticator)
            return conn
        }
    }
}
