package io.github.kvtttba.droplet.core.model

import kotlinx.serialization.Serializable

/**
 * Flexible user-defined label attached to transactions in a many-to-many relationship.
 */
@Serializable
data class Tag(
    val id: String,
    val name: String,
    val color: String? = null,
    val icon: String? = null,
    val sortOrder: Int = 0,
    val isArchived: Boolean = false,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    init {
        require(id.isNotBlank()) { "Tag id must not be blank" }
        require(name.isNotBlank()) { "Tag name must not be blank" }
    }
}
