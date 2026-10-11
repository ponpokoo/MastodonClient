package io.github.ponpokoo.mastodonclient.feature.common

import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus

enum class StatusConfirmationAction { Delete, Unfollow, Mute, Unmute, Block, Unblock }

data class StatusConfirmation(val action: StatusConfirmationAction, val status: TimelineStatus)
