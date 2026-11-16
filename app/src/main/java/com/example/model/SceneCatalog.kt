package com.example.model

import com.example.R

object SceneCatalog {

    const val SCENE_STORM_ID = "storm"
    const val SCENE_DEEP_SPACE_ID = "deep_space"
    const val SCENE_ABYSS_ID = "abyss"

    val scenes: List<SceneSpec> = listOf(
        SceneSpec(
            id = SCENE_STORM_ID,
            indexLabel = "01",
            titleRes = R.string.scene_storm_title,
            subtitleRes = R.string.scene_storm_subtitle,
            artworkResId = R.drawable.img_scene_storm,
            accentHex = 0xFF3E9C8E,
            channels = listOf(
                AudioChannelSpec(
                    id = "storm_rainfall",
                    nameRes = R.string.channel_storm_rainfall,
                    rawResId = R.raw.test_storm_rainfall,
                    defaultVolume = 0.78f,
                    pan = 0.0f,
                    sourceOrigin = AudioSourceOrigin.SYNTHESIZED_TEST
                ),
                AudioChannelSpec(
                    id = "storm_distant_thunder",
                    nameRes = R.string.channel_storm_distant_thunder,
                    rawResId = R.raw.test_storm_distant_thunder,
                    defaultVolume = 0.56f,
                    pan = -0.25f,
                    sourceOrigin = AudioSourceOrigin.SYNTHESIZED_TEST
                ),
                AudioChannelSpec(
                    id = "storm_heavy_thunder",
                    nameRes = R.string.channel_storm_heavy_thunder,
                    rawResId = R.raw.test_storm_heavy_thunder,
                    defaultVolume = 0.38f,
                    pan = 0.22f,
                    sourceOrigin = AudioSourceOrigin.SYNTHESIZED_TEST
                ),
                AudioChannelSpec(
                    id = "storm_wind_gusts",
                    nameRes = R.string.channel_storm_wind_gusts,
                    rawResId = R.raw.test_storm_wind_gusts,
                    defaultVolume = 0.48f,
                    pan = -0.14f,
                    sourceOrigin = AudioSourceOrigin.SYNTHESIZED_TEST
                )
            )
        ),
        SceneSpec(
            id = SCENE_DEEP_SPACE_ID,
            indexLabel = "02",
            titleRes = R.string.scene_space_title,
            subtitleRes = R.string.scene_space_subtitle,
            artworkResId = R.drawable.img_scene_deep_space,
            accentHex = 0xFF369B96,
            channels = listOf(
                AudioChannelSpec(
                    id = "space_engine_drone",
                    nameRes = R.string.channel_space_engine,
                    rawResId = R.raw.test_space_engine_drone,
                    defaultVolume = 0.74f,
                    pan = 0.0f,
                    sourceOrigin = AudioSourceOrigin.SYNTHESIZED_TEST
                ),
                AudioChannelSpec(
                    id = "space_reactor_hum",
                    nameRes = R.string.channel_space_reactor,
                    rawResId = R.raw.test_space_reactor_hum,
                    defaultVolume = 0.58f,
                    pan = -0.18f,
                    sourceOrigin = AudioSourceOrigin.SYNTHESIZED_TEST
                ),
                AudioChannelSpec(
                    id = "space_metallic_resonance",
                    nameRes = R.string.channel_space_metallic,
                    rawResId = R.raw.test_space_metallic_resonance,
                    defaultVolume = 0.36f,
                    pan = 0.28f,
                    sourceOrigin = AudioSourceOrigin.SYNTHESIZED_TEST
                ),
                AudioChannelSpec(
                    id = "space_electronic_ambience",
                    nameRes = R.string.channel_space_electronic,
                    rawResId = R.raw.test_space_electronic_ambience,
                    defaultVolume = 0.52f,
                    pan = -0.12f,
                    sourceOrigin = AudioSourceOrigin.SYNTHESIZED_TEST
                )
            )
        ),
        SceneSpec(
            id = SCENE_ABYSS_ID,
            indexLabel = "03",
            titleRes = R.string.scene_abyss_title,
            subtitleRes = R.string.scene_abyss_subtitle,
            artworkResId = R.drawable.img_scene_abyss,
            accentHex = 0xFF2D8B94,
            channels = listOf(
                AudioChannelSpec(
                    id = "abyss_underwater_rumble",
                    nameRes = R.string.channel_abyss_rumble,
                    rawResId = R.raw.test_abyss_underwater_rumble,
                    defaultVolume = 0.76f,
                    pan = 0.0f,
                    sourceOrigin = AudioSourceOrigin.SYNTHESIZED_TEST
                ),
                AudioChannelSpec(
                    id = "abyss_currents",
                    nameRes = R.string.channel_abyss_currents,
                    rawResId = R.raw.test_abyss_currents,
                    defaultVolume = 0.60f,
                    pan = 0.22f,
                    sourceOrigin = AudioSourceOrigin.SYNTHESIZED_TEST
                ),
                AudioChannelSpec(
                    id = "abyss_whale_calls",
                    nameRes = R.string.channel_abyss_whales,
                    rawResId = R.raw.test_abyss_whale_calls,
                    defaultVolume = 0.40f,
                    pan = -0.26f,
                    sourceOrigin = AudioSourceOrigin.SYNTHESIZED_TEST
                ),
                AudioChannelSpec(
                    id = "abyss_resonant_drone",
                    nameRes = R.string.channel_abyss_drone,
                    rawResId = R.raw.test_abyss_resonant_drone,
                    defaultVolume = 0.64f,
                    pan = 0.12f,
                    sourceOrigin = AudioSourceOrigin.SYNTHESIZED_TEST
                )
            )
        )
    )

    fun findScene(sceneId: String): SceneSpec =
        scenes.firstOrNull { it.id == sceneId } ?: scenes.first()

    val defaultScene: SceneSpec
        get() = scenes.first()
}
