package com.xlollx.songport.widget

import androidx.compose.ui.graphics.Color
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.ui.CardAccent
import com.xlollx.songport.ui.TileAccent
import com.xlollx.songport.ui.jobAccentFor
import com.xlollx.songport.ui.jobContainerTint
import com.xlollx.songport.ui.jobInkFor

/**
 * A coloured sync's card as its widget wears it: the card's colour and the ink of its controls, for
 * the phone's light and dark mode (a widget follows the phone, not the app's theme), and the card's
 * style, the sync's own or the one chosen in Settings. Null for a sync with no colour, whose
 * widget keeps the phone's own colours.
 */
class CardLook(val light: Color, val dark: Color, val inkLight: Color, val inkDark: Color, val style: CardAccent) {
    /** The card's background: the widget's surface with this much of the colour over it, as the card's own tint. */
    val tintLight: Color get() = light.copy(alpha = jobContainerTint(dark = false))
    val tintDark: Color get() = dark.copy(alpha = jobContainerTint(dark = true))

    /** The mark along the leading edge, in a widget's simple shapes; null for the styles that draw none there. */
    val mark: Mark? get() = when (style) {
        CardAccent.OUTLINE, CardAccent.WASH -> null
        else -> TileAccent.of(style)?.let { t ->
            // A widget draws plain rectangles: the crescent keeps half its peak width, the fade its width.
            Mark(widthDp = if (t.tapers) t.width.value / 2 else t.width.value, insetDp = t.inset.value, short = t.length < 1f)
        }
    }

    /** The colour all round the card rather than along its edge. */
    val outlined: Boolean get() = style == CardAccent.OUTLINE

    /** [short]: a rounded bar at mid height (the pill) rather than a band as tall as the card. */
    class Mark(val widthDp: Float, val insetDp: Float, val short: Boolean)

    companion object {
        /** [color] and [accentStyle] as the sync keeps them ([SyncJob.color], [SyncJob.accentStyle]); [settingsAccent]: the style in Settings. */
        fun of(color: String?, accentStyle: String?, settingsAccent: String?): CardLook? {
            val light = jobAccentFor(color, dark = false) ?: return null
            val dark = jobAccentFor(color, dark = true) ?: return null
            return CardLook(light, dark, jobInkFor(color, dark = false) ?: light, jobInkFor(color, dark = true) ?: dark, CardAccent.resolve(accentStyle, settingsAccent))
        }

        fun of(job: SyncJob, settingsAccent: String?): CardLook? = of(job.color, job.accentStyle, settingsAccent)
    }
}
