package com.timetracker.core.sync

import com.timetracker.core.model.AuthUser
import com.timetracker.core.model.Tag
import com.timetracker.core.model.TimeEntry
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

class SyncClient(
    private val http: HttpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; explicitNulls = false })
        }
    },
) {
    suspend fun login(url: String, email: String, password: String): AuthResult = authenticate(url, "login", email, password)

    suspend fun register(url: String, email: String, password: String): AuthResult = authenticate(url, "register", email, password)

    suspend fun sync(url: String, token: String, request: SyncRequest): SyncResponse {
        val data = post(url, token, SYNC_QUERY, mapOf("input" to json.encodeToJsonElement(request)))
        return json.decodeFromJsonElement(SyncResponse.serializer(), data.field(request.operation))
    }

    private suspend fun authenticate(url: String, operation: String, email: String, password: String): AuthResult {
        val query = if (operation == "login") LOGIN_QUERY else REGISTER_QUERY
        val input = buildJsonObject {
            put("email", email)
            put("password", password)
        }
        val data = post(url, null, query, mapOf("input" to input))
        val payload = json.decodeFromJsonElement(AuthPayload.serializer(), data.field(operation))
        if (payload.errors.isNotEmpty() || payload.token == null || payload.user == null) {
            throw IllegalArgumentException(payload.errors.joinToString(" ").ifBlank { "Sign in failed." })
        }
        return AuthResult(payload.token, payload.user)
    }

    private suspend fun post(url: String, token: String?, query: String, variables: Map<String, JsonElement>): JsonElement {
        val response = http.post(url) {
            contentType(ContentType.Application.Json)
            if (token != null) header("Authorization", "Bearer $token")
            setBody(GraphRequest(query, variables))
        }.body<GraphResponse>()
        if (response.errors?.isNotEmpty() == true || response.data == null) {
            throw IllegalStateException(response.errors?.joinToString { it.message } ?: "Empty GraphQL response")
        }
        return response.data
    }

    fun close() = http.close()

    companion object {
        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    }
}

data class AuthResult(val token: String, val user: AuthUser)

@Serializable
data class SyncRequest(
    val deviceId: String,
    val lastSyncAt: String? = null,
    val tags: List<Tag> = emptyList(),
    val timeEntries: List<TimeEntry> = emptyList(),
) {
    val operation: String get() = "sync"
}

@Serializable
data class SyncResponse(
    val serverTime: String,
    val tags: List<Tag> = emptyList(),
    val timeEntries: List<TimeEntry> = emptyList(),
)

@Serializable
private data class GraphRequest(val query: String, val variables: Map<String, JsonElement>)

@Serializable
private data class GraphResponse(val data: JsonElement? = null, val errors: List<GraphError>? = null)

@Serializable
private data class GraphError(val message: String)

@Serializable
private data class AuthPayload(val token: String? = null, val errors: List<String> = emptyList(), val user: AuthUser? = null)

private fun JsonElement.field(name: String) = jsonObject.getValue(name)

private val JsonElement.jsonObject get() = this as kotlinx.serialization.json.JsonObject

private const val LOGIN_QUERY = """
  mutation Login(${'$'}input: LoginInput!) {
    login(input: ${'$'}input) { token errors user { id email admin } }
  }
"""

private const val REGISTER_QUERY = """
  mutation Register(${'$'}input: RegisterInput!) {
    register(input: ${'$'}input) { token errors user { id email admin } }
  }
"""

private const val SYNC_QUERY = """
  mutation Sync(${'$'}input: SyncInput!) {
    sync(input: ${'$'}input) {
      serverTime
      tags { id name description color deviceId updatedAt deletedAt }
      timeEntries { id title description startedAt endedAt tagIds deviceId updatedAt deletedAt }
    }
  }
"""
