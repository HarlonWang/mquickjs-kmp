package wang.harlon.mquickjs

import kotlinx.serialization.Serializable

@Serializable
data class User(val id: Int, val name: String, val tags: List<String> = emptyList())

@Serializable
enum class Status { ACTIVE, DISABLED }

@Serializable
data class Reply(val ok: Boolean, val user: User? = null)
