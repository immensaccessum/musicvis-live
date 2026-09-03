package com.musicvis.live.vis

enum class VisKind {
    WAVEFORM,
    SPECTRUM,
    OCTAVE,
    SCOPE,
    WATERFALL,
    CIRCLE,
    PARTICLES,
    VU,
    LEVEL,
    XY,
    CHROMA,
    TUNNEL;

    val title: String get() = when (this) {
        WAVEFORM -> "Волна столбиками"
        SPECTRUM -> "Спектр"
        OCTAVE -> "Октавы"
        SCOPE -> "Осциллограф"
        WATERFALL -> "Водопад"
        CIRCLE -> "Круг"
        PARTICLES -> "Точки"
        VU -> "VU"
        LEVEL -> "Уровень"
        XY -> "Стерео XY"
        CHROMA -> "Ноты"
        TUNNEL -> "Туннель"
    }

    companion object {
        fun fromId(id: String) = entries.find { it.name == id } ?: SPECTRUM
    }
}

enum class HapticPreset {
    KICK, BASS, LOUD, BUSY, SOFT;

    val title: String get() = when (this) {
        KICK -> "Бочка"
        BASS -> "Бас"
        LOUD -> "Громкость"
        BUSY -> "Часто"
        SOFT -> "Мягко"
    }

    fun sens() = when (this) { KICK -> 48; BASS -> 55; LOUD -> 62; BUSY -> 78; SOFT -> 32 }
    fun gap() = when (this) { KICK -> 52; BASS -> 44; LOUD -> 38; BUSY -> 12; SOFT -> 68 }
    fun len() = when (this) { KICK -> 22; BASS -> 36; LOUD -> 42; BUSY -> 16; SOFT -> 58 }

    companion object {
        fun fromId(id: String) = entries.find { it.name == id } ?: KICK
    }
}
