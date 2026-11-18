package tw.chahan.walkcoach;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import org.json.JSONArray;

import java.util.Locale;

/**
 * 提示音、語音與震動。
 * 出聲前向系統要求「暫時降低其他 App 音量」（AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK），
 * 英語記事本的例句只會短暫變小聲，不會被暫停；說完立刻歸還。
 */
final class Cues {
    private static Cues inst;
    static synchronized Cues get(Context c) {
        if (inst == null) inst = new Cues(c.getApplicationContext());
        return inst;
    }

    private final Handler h = new Handler(Looper.getMainLooper());
    private final AudioManager am;
    private final AudioFocusRequest req;
    private final AudioAttributes attrs;
    private TextToSpeech tts;
    private boolean ttsReady, focused;
    private ToneGenerator tg;
    private String curLang = "";

    private final Runnable release = new Runnable() {
        @Override public void run() {
            if (tts != null && tts.isSpeaking()) { h.postDelayed(this, 300); return; }
            if (focused) { am.abandonAudioFocusRequest(req); focused = false; }
        }
    };

    private Cues(Context ctx) {
        am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
        req = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(f -> { })
                .build();
        try { tg = new ToneGenerator(AudioManager.STREAM_MUSIC, 85); } catch (RuntimeException e) { tg = null; }
        tts = new TextToSpeech(ctx, status -> {
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true;
                tts.setAudioAttributes(attrs);
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) { }
                    @Override public void onDone(String id) { later(300); }
                    @Override public void onError(String id) { later(300); }
                });
            }
        });
    }

    private void acquire() {
        h.removeCallbacks(release);
        if (!focused) {
            focused = am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
    }

    private void later(long ms) { h.removeCallbacks(release); h.postDelayed(release, ms); }

    private void setLang(String lang) {
        if (!ttsReady || lang.equals(curLang)) return;
        Locale loc = "en".equals(lang) ? Locale.US : Locale.TAIWAN;
        int r = tts.setLanguage(loc);
        if (r < 0 && !"en".equals(lang)) tts.setLanguage(Locale.CHINESE);
        curLang = lang;
    }

    private int toneOf(String kind) {
        switch (kind) {
            case "work": return ToneGenerator.TONE_PROP_BEEP2;
            case "end": return ToneGenerator.TONE_CDMA_CONFIRM;
            default: return ToneGenerator.TONE_PROP_ACK;
        }
    }

    /** 換段：提示音＋語音 */
    void cue(String kind, String text, String lang, float rate, boolean sound, boolean voice) {
        boolean speak = voice && text != null && !text.isEmpty();
        if (!sound && !speak) return;
        acquire();
        long delay = 0;
        if (sound && tg != null) { tg.startTone(toneOf(kind), 450); delay = 550; }
        if (speak && ttsReady) {
            h.postDelayed(() -> {
                setLang(lang);
                tts.setSpeechRate(rate);
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "cue" + System.nanoTime());
            }, delay);
        }
        later(delay + 1500);
    }

    void say(String text, String lang, float rate) { cue("easy", text, lang, rate, false, true); }

    void beep() {
        if (tg == null) return;
        acquire();
        tg.startTone(ToneGenerator.TONE_PROP_BEEP, 120);
        later(700);
    }

    static void vibrate(Context c, long[] p) {
        if (p == null || p.length == 0) return;
        Vibrator v = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
        if (v == null || !v.hasVibrator()) return;
        long[] t = new long[p.length + 1];
        t[0] = 0;
        System.arraycopy(p, 0, t, 1, p.length);
        VibrationEffect e = VibrationEffect.createWaveform(t, -1);
        // 用「鬧鐘」類別：關螢幕也會震，不受觸感回饋強度影響
        if (Build.VERSION.SDK_INT >= 33) {
            v.vibrate(e, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM));
        } else {
            v.vibrate(e, new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build());
        }
    }

    static long[] toLongs(JSONArray a) {
        if (a == null) return null;
        long[] r = new long[a.length()];
        for (int i = 0; i < a.length(); i++) r[i] = a.optLong(i, 0);
        return r;
    }

    static long[] parsePattern(String json) {
        try { return toLongs(new JSONArray(json)); } catch (Exception e) { return new long[]{800, 200, 800}; }
    }
}
