package io.github.magikdidi24.budspopup

import android.content.Context
import androidx.core.content.edit
import io.github.magikdidi24.budspopup.protocol.BudsColor

const val TAG = "BudsPopup"

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var budsAddress: String?
        get() = sp.getString("buds_address", null)
        set(value) = sp.edit { putString("buds_address", value) }

    var headphonesAddress: String?
        get() = sp.getString("headphones_address", null)
        set(value) = sp.edit { putString("headphones_address", value) }

    /** Last colour the buds reported; the plain status message does not carry it. */
    var color: BudsColor
        get() = BudsColor.valueOf(sp.getString("color", null) ?: BudsColor.White.name)
        set(value) = sp.edit { putString("color", value.name) }
}
