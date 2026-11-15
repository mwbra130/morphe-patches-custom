package app.template.patches.strava

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

// Liquid Glass is audited against the 486.x hotfix line: the
// nav-controller class below carries an obfuscated name that can move
// between major versions, so this patch deliberately does not claim
// the wider Strava target list. If a build's mapping differs, the
// patch fails loudly at patch time instead of breaking the app.
private val STRAVA_GLASS_COMPATIBILITY = Compatibility(
    name = "Strava",
    packageName = "com.strava",
    apkFileType = ApkFileType.APKS,
    appIconColor = 0xFC4C02,
    targets = listOf(
        AppTarget(version = "486.9", versionCode = 12400193),
        AppTarget(version = "486.11", versionCode = 12400195),
    ),
)

private const val BOTTOM_NAV_ACTIVITY_CLASS =
    "Lcom/strava/view/bottomnavigation/BottomNavigationActivity;"
private const val NAV_CONTROLLER_CLASS =
    "Lcom/strava/view/bottomnavigation/a;"
private const val GLASS_CLASS =
    "Lapp/template/extension/extension/GlassNav;"

/**
 * Liquid Glass bottom navigation (486.9 / 486.11).
 *
 * Strava's main screen carries two bottom bars in the same layout
 * slot — the classic Material BottomNavigationView and a ComposeView —
 * and a feature flag in the nav controller (bottomnavigation/a.b(), a
 * lazy Boolean) picks which one is shown. The Compose bar paints its
 * own opaque background, so it cannot be glassed from outside.
 *
 * 1. Force the classic bar: a.b() is the Compose-bar flag, so it
 *    returns FALSE from its first instruction. Verified against the
 *    486.11 dex: BottomNavigationActivity.onCreate only binds the
 *    classic BottomNavigationView into the nav controller on the
 *    b()==false path; b()==true selects the ComposeView bar. The
 *    classic path is fully wired (menu, listeners, tints), not dead
 *    code. Trade-off: Compose-bar-only behavior (the expandable
 *    Record create menu) is lost.
 *
 * 2. Wrap BottomNavigationActivity.onCreate (rename + wrapper, the
 *    same pattern ExtraSettingsPatch uses for SettingsFragment) and
 *    call the extension's GlassNav.apply() after the original, which
 *    restyles the bar as a floating glass pill — detached margins,
 *    live blurred backdrop, translucent tint, top highlight — and
 *    re-constrains the content so pages scroll underneath it.
 *
 * Tweaks live in Extra Settings (Liquid Glass section): master
 * switch, floating vs docked, backdrop blur on/off, tint opacity.
 * They are read from Strava's preferences at startup.
 */
@Suppress("unused")
val liquidGlassNavPatch = bytecodePatch(
    name = "Liquid Glass navigation",
    description = "Turns Strava's bottom bar into a floating Liquid Glass pill: detached " +
        "from the screen edges, live blurred backdrop with a translucent tint, content " +
        "scrolling underneath it, like the current YouTube and Proton VPN bars. " +
        "Tweak it in Extra Settings. Uses Strava's classic bottom bar to make the " +
        "effect possible.",
    default = true,
) {
    compatibleWith(STRAVA_GLASS_COMPATIBILITY)

    // The glass tweaks live in the Extra Settings screen; selecting
    // Liquid Glass always brings that screen (and its resources).
    dependsOn(extraSettingsPatch)

    extendWith("extensions/extension.mpe")

    execute {
        // 1. Force the classic BottomNavigationView path.
        val controller = mutableClassDefBy(NAV_CONTROLLER_CLASS)
        val switch = controller.methods.firstOrNull { method ->
            method.name == "b" &&
                method.returnType == "Z" &&
                method.parameterTypes.isEmpty()
        } ?: throw PatchException(
            "Liquid Glass: nav controller switch b() not found in $NAV_CONTROLLER_CLASS — re-audit for this version.",
        )
        switch.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return v0
            """.trimIndent(),
        )

        // 2. BottomNavigationActivity.onCreate -> rename + wrapper.
        // onCreate has 22 registers, so its parameter registers are
        // v20/v21: invoke-virtual/invoke-static (format 35c) can only
        // encode registers up to v15, so copy the parameters into low
        // registers with move-object/from16 before calling through.
        val activity = mutableClassDefBy(BOTTOM_NAV_ACTIVITY_CLASS)
        val original = activity.methods.firstOrNull { method ->
            method.name == "onCreate" &&
                method.returnType == "V" &&
                method.parameterTypes == listOf("Landroid/os/Bundle;")
        } ?: throw PatchException(
            "Liquid Glass: BottomNavigationActivity.onCreate not found — re-audit for this version.",
        )

        val renamed = ImmutableMethod(
            BOTTOM_NAV_ACTIVITY_CLASS,
            "morpheOnCreate",
            original.parameters,
            original.returnType,
            original.accessFlags,
            original.annotations,
            null,
            MutableMethodImplementation(original.implementation!!),
        ).toMutable()
        activity.methods.remove(original)
        activity.methods.add(renamed)

        val wrapper = ImmutableMethod(
            BOTTOM_NAV_ACTIVITY_CLASS,
            "onCreate",
            original.parameters,
            original.returnType,
            original.accessFlags,
            null,
            null,
            MutableMethodImplementation(8),
        ).toMutable().apply {
            addInstructions(
                0,
                """
                    move-object/from16 v0, p0
                    move-object/from16 v1, p1
                    invoke-virtual {v0, v1}, $BOTTOM_NAV_ACTIVITY_CLASS->morpheOnCreate(Landroid/os/Bundle;)V
                    invoke-static {v0}, $GLASS_CLASS->apply(Landroid/app/Activity;)V
                    return-void
                """.trimIndent(),
            )
        }
        activity.methods.add(wrapper)

        println("Liquid Glass: classic nav forced, BottomNavigationActivity.onCreate wrapped")
    }
}
