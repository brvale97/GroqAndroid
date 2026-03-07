package com.groqandroid

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Client for the Groq Whisper transcription API.
 * Supports automatic model fallback when the primary model fails.
 */
class GroqApiClient(private val apiKey: String) {

    companion object {
        private const val API_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
        private val FALLBACK_MODELS = listOf("whisper-large-v3-turbo", "whisper-large-v3")
        private const val MAX_RETRIES = 3
        private val RETRY_DELAYS_MS = longArrayOf(1000, 2000, 4000) // exponential backoff
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Transcribes the given audio file using Groq Whisper.
     * If the primary model fails, automatically retries with a fallback model.
     * Retries with exponential backoff on 429/5xx errors.
     *
     * @return The transcribed text.
     * @throws TranscriptionException if all models and retries fail.
     */
    suspend fun transcribe(audioFile: File, language: String? = null, prompt: String? = null, model: String = "whisper-large-v3-turbo"): String {
        // Build ordered list: selected model first, then fallbacks (no duplicates)
        val modelsToTry = (listOf(model) + FALLBACK_MODELS).distinct()

        var lastException: Exception? = null
        for (currentModel in modelsToTry) {
            try {
                return transcribeWithModel(audioFile, language, prompt, currentModel)
            } catch (e: AuthenticationException) {
                // Don't retry auth errors or try other models — key is invalid
                throw e
            } catch (e: TranscriptionException) {
                lastException = e
                // Continue to next model
            } catch (e: IOException) {
                lastException = TranscriptionException("Network error: ${e.message}", e)
                // Continue to next model
            }
        }
        throw lastException ?: TranscriptionException("All models failed")
    }

    private suspend fun transcribeWithModel(audioFile: File, language: String?, prompt: String?, model: String): String {
        var lastException: Exception? = null
        for (attempt in 0 until MAX_RETRIES) {
            try {
                return executeTranscription(audioFile, language, prompt, model)
            } catch (e: AuthenticationException) {
                throw e // Never retry auth errors
            } catch (e: RetryableException) {
                lastException = e
                if (attempt < MAX_RETRIES - 1) {
                    kotlinx.coroutines.delay(RETRY_DELAYS_MS[attempt])
                }
            } catch (e: TranscriptionException) {
                throw e // Non-retryable API errors
            } catch (e: IOException) {
                lastException = TranscriptionException("Network error: ${e.message}", e)
                if (attempt < MAX_RETRIES - 1) {
                    kotlinx.coroutines.delay(RETRY_DELAYS_MS[attempt])
                }
            }
        }
        throw lastException ?: TranscriptionException("All retries failed")
    }

    private suspend fun executeTranscription(audioFile: File, language: String?, prompt: String?, model: String): String {
        val builder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                audioFile.name,
                audioFile.asRequestBody("audio/wav".toMediaType())
            )
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", "json")

        if (language != null) {
            builder.addFormDataPart("language", language)
        }

        if (!prompt.isNullOrEmpty()) {
            builder.addFormDataPart("prompt", prompt)
        }

        val requestBody = builder.build()

        val request = Request.Builder()
            .url(API_URL)
            .header("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        val response = client.newCall(request).await()

        response.use {
            val body = it.body?.string()
                ?: throw TranscriptionException("Empty response body")

            if (!it.isSuccessful) {
                val errorMsg = try {
                    JSONObject(body).optJSONObject("error")?.optString("message") ?: body
                } catch (_: Exception) {
                    body
                }
                when (it.code) {
                    401, 403 -> throw AuthenticationException("Invalid API key — check your key in Settings")
                    429 -> throw RetryableException("Rate limited — retrying...")
                    in 500..599 -> throw RetryableException("Server error ${it.code}: $errorMsg")
                    else -> throw TranscriptionException("API error ${it.code}: $errorMsg")
                }
            }

            val json = JSONObject(body)
            return json.optString("text", "").trim()
        }
    }

    fun shutdown() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}

/**
 * Awaits an OkHttp Call using coroutines.
 */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }

    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(TranscriptionException("Network error: ${e.message}", e))
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response)
        }
    })
}

open class TranscriptionException(message: String, cause: Throwable? = null) : Exception(message, cause)
class AuthenticationException(message: String, cause: Throwable? = null) : TranscriptionException(message, cause)
class RetryableException(message: String, cause: Throwable? = null) : TranscriptionException(message, cause)
