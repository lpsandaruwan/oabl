package com.softiva.oabl;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.audiofx.AudioEffect;

/** Compatible players advertise sessions through Android's audio-effect contract. */
public final class EqualizerSessionReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        int session = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1);
        if (session <= 0) return;
        if (AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION.equals(action)) {
            if (intent.getIntExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
                    == AudioEffect.CONTENT_TYPE_VOICE) return;
            SoundEqualizerService.rememberSession(context, session,
                    intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME));
        } else if (AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION.equals(action)) {
            SoundEqualizerService.forgetSession(context, session);
        }
    }
}
