package com.groqandroid

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Tests for GroqApiClient error handling.
 * Uses MockWebServer to simulate various API responses.
 */
class ApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var tempFile: File

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()

        // Create a minimal valid WAV file for testing
        tempFile = File.createTempFile("test_audio", ".wav")
        tempFile.writeBytes(ByteArray(44 + 100))
    }

    @After
    fun teardown() {
        server.shutdown()
        tempFile.delete()
    }

    private fun createClient(): GroqApiClient {
        return GroqApiClient("test-api-key", server.url("/v1/audio/transcriptions").toString())
    }

    @Test
    fun `successful transcription returns text`() = runTest {
        server.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"text": "Hello world"}"""))

        val result = createClient().transcribe(tempFile)
        assertEquals("Hello world", result)
    }

    @Test
    fun `empty transcription returns empty string`() = runTest {
        server.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"text": ""}"""))

        val result = createClient().transcribe(tempFile)
        assertEquals("", result)
    }

    @Test(expected = AuthenticationException::class)
    fun `401 throws AuthenticationException`() = runTest {
        server.enqueue(MockResponse()
            .setResponseCode(401)
            .setBody("""{"error": {"message": "Invalid API key"}}"""))

        createClient().transcribe(tempFile)
    }

    @Test(expected = AuthenticationException::class)
    fun `403 throws AuthenticationException`() = runTest {
        server.enqueue(MockResponse()
            .setResponseCode(403)
            .setBody("""{"error": {"message": "Forbidden"}}"""))

        createClient().transcribe(tempFile)
    }

    @Test
    fun `429 retries with backoff then succeeds`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error": {"message": "Rate limited"}}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"text": "After retry"}"""))

        val result = createClient().transcribe(tempFile)
        assertEquals("After retry", result)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `500 retries with backoff then succeeds`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error": {"message": "Server error"}}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"text": "Recovered"}"""))

        val result = createClient().transcribe(tempFile)
        assertEquals("Recovered", result)
    }

    @Test
    fun `401 does not retry or try fallback models`() = runTest {
        server.enqueue(MockResponse()
            .setResponseCode(401)
            .setBody("""{"error": {"message": "Invalid key"}}"""))

        try {
            createClient().transcribe(tempFile)
            fail("Should have thrown AuthenticationException")
        } catch (_: AuthenticationException) {
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `response with missing text field returns empty`() = runTest {
        server.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"duration": 1.5}"""))

        val result = createClient().transcribe(tempFile)
        assertEquals("", result)
    }

    @Test
    fun `model fallback on non-auth error`() = runTest {
        // First model fails with 400 (non-retryable, goes to next model)
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error": {"message": "Bad model"}}"""))
        // Fallback model succeeds
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"text": "Fallback worked"}"""))

        val result = createClient().transcribe(tempFile)
        assertEquals("Fallback worked", result)
    }

    @Test
    fun `authorization header is sent`() = runTest {
        server.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"text": "test"}"""))

        createClient().transcribe(tempFile)

        val request = server.takeRequest()
        assertEquals("Bearer test-api-key", request.getHeader("Authorization"))
    }
}
