package io.github.ponpokoo.mastodonclient.data.repository

import io.github.ponpokoo.mastodonclient.data.remote.dto.PollConfigurationDto
import io.github.ponpokoo.mastodonclient.domain.model.PollConfiguration

internal fun PollConfigurationDto?.toDomain(): PollConfiguration {
    val min = this?.minExpiration?.takeIf { it > 0 } ?: 300L
    val max = this?.maxExpiration?.takeIf { it > 0 } ?: maxOf(604_800L, min)
    val validRange = max >= min
    return PollConfiguration(
        maxOptions = this?.maxOptions?.takeIf { it >= 2 } ?: 4,
        maxCharactersPerOption = this?.maxCharactersPerOption?.takeIf { it > 0 } ?: 50,
        minExpiration = if (validRange) min else minOf(300L, max),
        maxExpiration = max,
        confirmed = this?.let { it.maxOptions != null && it.maxOptions >= 2 &&
            it.maxCharactersPerOption != null && it.maxCharactersPerOption > 0 &&
            it.minExpiration != null && it.minExpiration > 0 &&
            it.maxExpiration != null && validRange } == true,
    )
}
