package de.lenox.lightoverlay

import dev.isxander.yacl3.api.ConfigCategory
import dev.isxander.yacl3.api.Option
import dev.isxander.yacl3.api.OptionDescription
import dev.isxander.yacl3.api.OptionGroup
import dev.isxander.yacl3.api.YetAnotherConfigLib
import dev.isxander.yacl3.api.controller.ColorControllerBuilder
import dev.isxander.yacl3.api.controller.EnumControllerBuilder
import dev.isxander.yacl3.api.controller.FloatSliderControllerBuilder
import dev.isxander.yacl3.api.controller.IntegerSliderControllerBuilder
import dev.isxander.yacl3.api.controller.TickBoxControllerBuilder
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import java.awt.Color

/** YACL config screen. Opened via the config key mapping, Mod Menu (Fabric) or NeoForge's mod list. */
object LightOverlayConfigScreen {
    private fun tr(key: String): Component = Component.translatable("${LightOverlayMod.MOD_ID}.config.$key")

    private fun desc(key: String): OptionDescription = OptionDescription.of(tr("$key.description"))

    fun create(parent: Screen?): Screen = YetAnotherConfigLib.createBuilder()
        .title(tr("title"))
        .category(
            ConfigCategory.createBuilder()
                .name(tr("category.general"))
                .option(
                    Option.createBuilder<Boolean>()
                        .name(tr("enabled"))
                        .description(desc("enabled"))
                        .binding(false, { LightOverlayConfig.enabled }, { LightOverlayConfig.enabled = it })
                        .controller { TickBoxControllerBuilder.create(it) }
                        .build()
                )
                .option(
                    Option.createBuilder<Boolean>()
                        .name(tr("show_while_holding_torch"))
                        .description(desc("show_while_holding_torch"))
                        .binding(false, { LightOverlayConfig.showWhileHoldingTorch }, { LightOverlayConfig.showWhileHoldingTorch = it })
                        .controller { TickBoxControllerBuilder.create(it) }
                        .build()
                )
                .option(
                    Option.createBuilder<DisplayMode>()
                        .name(tr("display_mode"))
                        .description(desc("display_mode"))
                        .binding(DisplayMode.NUMBER, { LightOverlayConfig.displayMode }, { LightOverlayConfig.displayMode = it })
                        .controller { enumController(it, DisplayMode::class.java, "display_mode") }
                        .build()
                )
                .option(
                    Option.createBuilder<Boolean>()
                        .name(tr("hide_safe"))
                        .description(desc("hide_safe"))
                        .binding(false, { LightOverlayConfig.hideSafeBlocks }, { LightOverlayConfig.hideSafeBlocks = it })
                        .controller { TickBoxControllerBuilder.create(it) }
                        .build()
                )
                .group(
                    OptionGroup.createBuilder()
                        .name(tr("group.skirts"))
                        .option(
                            Option.createBuilder<Boolean>()
                                .name(tr("skirts_enabled"))
                                .description(desc("skirts_enabled"))
                                .binding(false, { LightOverlayConfig.skirtsEnabled }, { LightOverlayConfig.skirtsEnabled = it })
                                .controller { TickBoxControllerBuilder.create(it) }
                                .build()
                        )
                        .option(
                            Option.createBuilder<Float>()
                                .name(tr("skirt_height"))
                                .description(desc("skirt_height"))
                                .binding(LightOverlayConfig.DEFAULT_SKIRT_HEIGHT, { LightOverlayConfig.skirtHeight }, { LightOverlayConfig.skirtHeight = it })
                                .controller { FloatSliderControllerBuilder.create(it).range(0.1f, 1f).step(0.05f) }
                                .build()
                        )
                        .build()
                )
                .group(
                    OptionGroup.createBuilder()
                        .name(tr("group.range"))
                        .option(
                            Option.createBuilder<Int>()
                                .name(tr("horizontal_radius"))
                                .description(desc("horizontal_radius"))
                                .binding(16, { LightOverlayConfig.horizontalRadius }, { LightOverlayConfig.horizontalRadius = it })
                                .controller { IntegerSliderControllerBuilder.create(it).range(1, 64).step(1) }
                                .build()
                        )
                        .option(
                            Option.createBuilder<Int>()
                                .name(tr("vertical_radius"))
                                .description(desc("vertical_radius"))
                                .binding(8, { LightOverlayConfig.verticalRadius }, { LightOverlayConfig.verticalRadius = it })
                                .controller { IntegerSliderControllerBuilder.create(it).range(1, 32).step(1) }
                                .build()
                        )
                        .option(
                            Option.createBuilder<Int>()
                                .name(tr("update_interval"))
                                .description(desc("update_interval"))
                                .binding(10, { LightOverlayConfig.updateIntervalTicks }, { LightOverlayConfig.updateIntervalTicks = it })
                                .controller { IntegerSliderControllerBuilder.create(it).range(1, 100).step(1) }
                                .build()
                        )
                        .build()
                )
                .group(
                    OptionGroup.createBuilder()
                        .name(tr("group.colors"))
                        .option(colorOption("safe_color", LightOverlayConfig.DEFAULT_SAFE_COLOR, { LightOverlayConfig.safeColor }, { LightOverlayConfig.safeColor = it }))
                        .option(colorOption("warning_color", LightOverlayConfig.DEFAULT_WARNING_COLOR, { LightOverlayConfig.warningColor }, { LightOverlayConfig.warningColor = it }))
                        .option(colorOption("danger_color", LightOverlayConfig.DEFAULT_DANGER_COLOR, { LightOverlayConfig.dangerColor }, { LightOverlayConfig.dangerColor = it }))
                        .build()
                )
                .build()
        )
        .category(
            ConfigCategory.createBuilder()
                .name(tr("category.torch_helper"))
                .option(
                    Option.createBuilder<TorchHelperMode>()
                        .name(tr("torch_helper_mode"))
                        .description(desc("torch_helper_mode"))
                        .binding(TorchHelperMode.SMART, { LightOverlayConfig.torchHelperMode }, { LightOverlayConfig.torchHelperMode = it })
                        .controller { enumController(it, TorchHelperMode::class.java, "torch_helper_mode") }
                        .build()
                )
                .option(
                    Option.createBuilder<Int>()
                        .name(tr("min_light_level"))
                        .description(desc("min_light_level"))
                        .binding(1, { LightOverlayConfig.minLightLevel }, { LightOverlayConfig.minLightLevel = it })
                        .controller { IntegerSliderControllerBuilder.create(it).range(1, 15).step(1) }
                        .build()
                )
                .option(
                    Option.createBuilder<Boolean>()
                        .name(tr("show_coverage_preview"))
                        .description(desc("show_coverage_preview"))
                        .binding(true, { LightOverlayConfig.showCoveragePreview }, { LightOverlayConfig.showCoveragePreview = it })
                        .controller { TickBoxControllerBuilder.create(it) }
                        .build()
                )
                .option(colorOption("indicator_color", LightOverlayConfig.DEFAULT_INDICATOR_COLOR, { LightOverlayConfig.indicatorColor }, { LightOverlayConfig.indicatorColor = it }))
                .group(
                    OptionGroup.createBuilder()
                        .name(tr("group.smart"))
                        .option(
                            Option.createBuilder<Int>()
                                .name(tr("suggestion_count"))
                                .description(desc("suggestion_count"))
                                .binding(3, { LightOverlayConfig.suggestionCount }, { LightOverlayConfig.suggestionCount = it })
                                .controller { IntegerSliderControllerBuilder.create(it).range(1, 8).step(1) }
                                .build()
                        )
                        .option(
                            Option.createBuilder<Int>()
                                .name(tr("look_ahead"))
                                .description(desc("look_ahead"))
                                .binding(8, { LightOverlayConfig.lookAhead }, { LightOverlayConfig.lookAhead = it })
                                .controller { IntegerSliderControllerBuilder.create(it).range(0, 16).step(1) }
                                .build()
                        )
                        .build()
                )
                .group(
                    OptionGroup.createBuilder()
                        .name(tr("group.grid"))
                        .option(
                            Option.createBuilder<GridPattern>()
                                .name(tr("grid_pattern"))
                                .description(desc("grid_pattern"))
                                .binding(GridPattern.DIAMOND, { LightOverlayConfig.gridPattern }, { LightOverlayConfig.gridPattern = it })
                                .controller { enumController(it, GridPattern::class.java, "grid_pattern") }
                                .build()
                        )
                        .option(
                            Option.createBuilder<GridAnchor>()
                                .name(tr("grid_anchor"))
                                .description(desc("grid_anchor"))
                                .binding(GridAnchor.NEWEST_TORCH, { LightOverlayConfig.gridAnchor }, { LightOverlayConfig.gridAnchor = it })
                                .controller { enumController(it, GridAnchor::class.java, "grid_anchor") }
                                .build()
                        )
                        .option(
                            Option.createBuilder<Int>()
                                .name(tr("grid_margin"))
                                .description(desc("grid_margin"))
                                .binding(0, { LightOverlayConfig.gridMargin }, { LightOverlayConfig.gridMargin = it })
                                .controller { IntegerSliderControllerBuilder.create(it).range(0, 6).step(1) }
                                .build()
                        )
                        .build()
                )
                .build()
        )
        .save { LightOverlayConfig.save() }
        .build()
        .generateScreen(parent)

    private fun <E : Enum<E>> enumController(option: Option<E>, enumClass: Class<E>, key: String) =
        EnumControllerBuilder.create(option)
            .enumClass(enumClass)
            .formatValue { value -> tr("$key.${value.name.lowercase()}") }

    private fun colorOption(key: String, default: Int, get: () -> Int, set: (Int) -> Unit): Option<Color> =
        Option.createBuilder<Color>()
            .name(tr(key))
            .description(desc(key))
            .binding(Color(default), { Color(get()) }, { set(it.rgb and 0xFFFFFF) })
            .controller { ColorControllerBuilder.create(it) }
            .build()
}
