package tw.chahan.walkcoach;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.webkit.JavascriptInterface;

/** 網頁呼叫的介面：window.NativeCoach */
public class Bridge {
    private final Context ctx;

    Bridge(Context ctx) { this.ctx = ctx; }

    private void send(String action, String key, String val) {
        Intent i = new Intent(ctx, CoachService.class).setAction(action);
        if (key != null) i.putExtra(key, val);
        if (CoachService.ACTION_START.equals(action)) ctx.startForegroundService(i);
        else if (Engine.get().isRunning()) ctx.startService(i);
    }

    @JavascriptInterface public boolean available() { return true; }
    @JavascriptInterface public String version() { return "1"; }

    /** plan：整堂課的 JSON（每段秒數、語音、震動、提示音） */
    @JavascriptInterface public void start(String plan) { send(CoachService.ACTION_START, "plan", plan); }
    @JavascriptInterface public void pause() { send(CoachService.ACTION_PAUSE, null, null); }
    @JavascriptInterface public void resume() { send(CoachService.ACTION_RESUME, null, null); }
    @JavascriptInterface public void seek(double sec) { send(CoachService.ACTION_SEEK, "sec", String.valueOf(sec)); }
    @JavascriptInterface public void stop() { send(CoachService.ACTION_STOP, null, null); }

    /** {running,paused,done,pos,total,idx} */
    @JavascriptInterface public String state() { return Engine.get().stateJson(); }

    private final Handler main = new Handler(Looper.getMainLooper());
    @JavascriptInterface public void testVibrate(String patternJson) { Cues.vibrate(ctx, Cues.parsePattern(patternJson)); }
    @JavascriptInterface public void testCue(String text, String lang) { main.post(() -> Cues.get(ctx).cue("work", text, lang, 1.0f, true, true)); }

    @JavascriptInterface public void openAppSettings() {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.getPackageName()));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { ctx.startActivity(i); } catch (Exception ignored) { }
    }
}
