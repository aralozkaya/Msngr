package org.aralozkaya.msngr

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.aralozkaya.msngr.data.MsnpClientConfigService
import org.aralozkaya.msngr.data.SoapExchangeException
import org.aralozkaya.msngr.data.canRepeatSoapAt
import org.aralozkaya.msngr.data.postSoapEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SoapTransportTest {
    @Test fun envelopeRedirectsRespectTheConfiguredServiceOrigin() {
        val origin = URL("https://example.test/service")
        assertTrue(origin.canRepeatSoapAt(URL("https://EXAMPLE.test:443/new")))
        assertTrue(URL("http://example.test/service").canRepeatSoapAt(origin))
        assertFalse(origin.canRepeatSoapAt(URL("http://example.test/service")))
        assertFalse(origin.canRepeatSoapAt(URL("https://other.test/service")))
        assertFalse(origin.canRepeatSoapAt(URL("https://example.test:8443/service")))
        assertFalse(origin.canRepeatSoapAt(URL("https://user@example.test/service")))
    }

    @Test fun redirectsPreserveSoapFaultsAndResponseFailuresDoNotRepeatTheRequest() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val finishRequest = CountDownLatch(1)
        server.createContext("/slow") { exchange ->
            exchange.use { finishRequest.await(5, TimeUnit.SECONDS) }
        }
        server.createContext("/redirect") { exchange ->
            exchange.use {
                it.responseHeaders.add("Location", "/service")
                it.sendResponseHeaders(307, -1)
            }
        }
        server.createContext("/service") { exchange ->
            exchange.use {
                val request = it.requestBody.bufferedReader(Charsets.UTF_8).readText()
                val response = "${it.requestMethod} ${it.requestHeaders.getFirst("SOAPAction")} $request".toByteArray()
                it.sendResponseHeaders(500, response.size.toLong())
                it.responseBody.write(response)
            }
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val result = postSoapEnvelope("$base/redirect", "ping", "café", 3_000)
            assertEquals(500, result.status)
            assertEquals("POST ping café", result.body)
            assertEquals(307, postSoapEnvelope("$base/redirect", "ping", "", 3_000, followRedirects = false).status)

            val attempts = mutableListOf<String>()
            val failure = runCatching {
                runBlocking {
                    MsnpClientConfigService.withServiceEndpointFallback(listOf("$base/slow", "$base/fallback")) {
                        attempts += it
                        postSoapEnvelope(it, "ping", "", 500)
                    }
                }
            }.exceptionOrNull()
            assertTrue(failure.toString(), failure is SoapExchangeException)
            assertEquals(listOf("$base/slow"), attempts)
        } finally {
            finishRequest.countDown()
            server.stop(0)
        }
    }
}
