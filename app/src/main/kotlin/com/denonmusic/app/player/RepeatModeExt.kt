package com.denonmusic.app.player

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOn
import androidx.compose.material.icons.filled.RepeatOne
import com.denonmusic.heos.RepeatMode

/** Cycles Off -> All -> One -> Off, the single repeat button's tap behaviour used across the app. */
fun RepeatMode?.next(): RepeatMode = when (this) {
    RepeatMode.Off, null -> RepeatMode.All
    RepeatMode.All -> RepeatMode.One
    RepeatMode.One -> RepeatMode.Off
}

fun RepeatMode?.icon() = when (this) {
    RepeatMode.All -> Icons.Filled.RepeatOn
    RepeatMode.One -> Icons.Filled.RepeatOne
    else -> Icons.Filled.Repeat
}
