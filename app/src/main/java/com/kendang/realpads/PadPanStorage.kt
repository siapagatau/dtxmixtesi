package com.kendang.realpads

import android.content.Context

private const val PAN_PREFS = "realpads_pad_pan"

// Nyimpen pan (-1f..1f, 0 = tengah) per (preset, pad) di SharedPreferences - pola sama persis dengan
// PadTuneStorage/PadVolumeStorage, dipakai fitur MIXER. Pad yang tidak pernah di-pan tidak punya entri
// (otomatis tengah).
object PadPanStorage {
    private fun key(preset: Int, pad: Int) = "pan_p${preset}_pad$pad"

    fun loadAllIntoEngine(context: Context) {
        val prefs = context.getSharedPreferences(PAN_PREFS, Context.MODE_PRIVATE)
        for (preset in 1..PresetStorage.MAX_PRESET) {
            for (pad in 1..PresetStorage.MAX_PAD) {
                val k = key(preset, pad)
                if (prefs.contains(k)) AudioEngine.nativeSetPadPan(preset, pad, prefs.getFloat(k, 0f))
            }
        }
    }

    // Update ke native (real-time) sekaligus ditulis ke disk. Nilai 0 menghapus entri (kembali ke default).
    fun set(context: Context, preset: Int, pad: Int, pan: Float) {
        val v = pan.coerceIn(-1f, 1f)
        AudioEngine.nativeSetPadPan(preset, pad, v)
        val ed = context.getSharedPreferences(PAN_PREFS, Context.MODE_PRIVATE).edit()
        if (v == 0f) ed.remove(key(preset, pad)) else ed.putFloat(key(preset, pad), v)
        ed.apply()
    }

    fun get(preset: Int, pad: Int): Float = AudioEngine.nativeGetPadPan(preset, pad)

    // Dipakai deletePreset: kembalikan semua pan satu preset ke tengah.
    fun clearPreset(context: Context, preset: Int) {
        val ed = context.getSharedPreferences(PAN_PREFS, Context.MODE_PRIVATE).edit()
        for (pad in 1..PresetStorage.MAX_PAD) {
            ed.remove(key(preset, pad))
            AudioEngine.nativeSetPadPan(preset, pad, 0f)
        }
        ed.apply()
    }
}
