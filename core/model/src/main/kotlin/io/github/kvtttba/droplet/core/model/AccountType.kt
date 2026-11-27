package io.github.kvtttba.droplet.core.model

import kotlinx.serialization.Serializable

/**
 * Five fundamental account classes in Droplet's double-entry accounting model.
 */
@Serializable
enum class AccountType {
    ASSET,
    LIABILITY,
    EQUITY,
    INCOME,
    EXPENSE,
}
