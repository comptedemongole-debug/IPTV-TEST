package com.perso.iptv

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

/**
 * pcm = true : le son est décodé par l'appareil au lieu d'être envoyé tel quel à la TV (« passthrough »).
 * Utile quand un appareil (ex. Chromecast) n'a pas de son avec certains formats (AC3, E-AC3, DTS…).
 */
@androidx.annotation.OptIn(UnstableApi::class)
fun makeRenderers(ctx: Context, pcm: Boolean): DefaultRenderersFactory {
    val f = object : DefaultRenderersFactory(ctx) {
        override fun buildAudioSink(
            context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean
        ): AudioSink? {
            if (!pcm) return super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)
            return DefaultAudioSink.Builder(context)
                .setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .build()
        }
    }
    f.setEnableDecoderFallback(true)
    // Décodeur logiciel FFmpeg (AC3, E-AC3, DTS, TrueHD…) : mode ON = essayé SEULEMENT si l'appareil ne sait pas lire la piste.
    // Sur la TCL, les décodeurs/passthrough de l'appareil restent prioritaires (comportement inchangé) ;
    // sur le Chromecast Google TV, qui n'a pas ces décodeurs, les pistes deviennent enfin lisibles et listées.
    f.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
    return f
}
