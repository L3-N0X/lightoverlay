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
                        .controller {
                            EnumControllerBuilder.create(it)
                                .enumClass(DisplayMode::class.java)
                                .formatValue { mode -> tr("display_mode.${mode.name.lowercase()}") }
                        }
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
        .save { LightOverlayConfig.save() }
        .build()
        .generateScreen(parent)

    private fun colorOption(key: String, default: Int, get: () -> Int, set: (Int) -> Unit): Option<Color> =
        Option.createBuilder<Color>()
            .name(tr(key))
            .description(desc(key))
            .binding(Color(default), { Color(get()) }, { set(it.rgb and 0xFFFFFF) })
            .controller { ColorControllerBuilder.create(it) }
            .build()
}
