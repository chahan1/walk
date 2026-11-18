package tw.chahan.walkcoach;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** 前景服務：關螢幕也照表計時，換段時震動、出聲（暫時壓低其他 App 音量）並更新通知 */
public class CoachService extends Service {
    static final String ACTION_START = "start", ACTION_PAUSE = "pause", ACTION_RESUME = "resume",
            ACTION_SEEK = "seek", ACTION_STOP = "stop", ACTION_SKIP = "skip", ACTION_TOGGLE = "toggle";
    private static final String CH_RUN = "run", CH_CUE = "cue";
    private static final int NID = 1, NID_CUE = 2;

    private final Handler h = new Handler(Looper.getMainLooper());
    private final Set<String> fired = new HashSet<>();
    private PowerManager.WakeLock wl;
    private int lastIdx = -1;
    private boolean endHandled;

    private final Runnable loop = new Runnable() {
        @Override public void run() {
            step();
            if (Engine.get().isRunning()) h.postDelayed(this, 200);
        }
    };

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent i, int flags, int id) {
        String a = i == null ? null : i.getAction();
        Engine e = Engine.get();
        if (ACTION_START.equals(a)) {
            channels();
            startFg(build("準備開始", "", -1, false));
            try {
                e.load(i.getStringExtra("plan"));
            } catch (Exception ex) {
                end(true);
                return START_NOT_STICKY;
            }
            Cues.get(this);
            wake();
            lastIdx = -1;
            fired.clear();
            endHandled = false;
            h.removeCallbacks(loop);
            h.post(loop);
        } else if (!e.isRunning()) {
            if (ACTION_STOP.equals(a)) end(true);
        } else if (ACTION_PAUSE.equals(a)) {
            e.pause(); refresh();
        } else if (ACTION_RESUME.equals(a)) {
            e.resume(); refresh();
        } else if (ACTION_TOGGLE.equals(a)) {
            if (e.isPaused()) e.resume(); else e.pause();
            refresh();
        } else if (ACTION_SEEK.equals(a)) {
            try { e.seek(Double.parseDouble(i.getStringExtra("sec"))); } catch (Exception ignored) { }
        } else if (ACTION_SKIP.equals(a)) {
            double p = e.pos();
            int k = e.idxAt(p);
            e.seek(k + 1 < e.segs.size() ? e.starts[k + 1] : e.total);
            if (e.isPaused()) refresh();
        } else if (ACTION_STOP.equals(a)) {
            e.stop();
            end(true);
        }
        return START_NOT_STICKY;
    }

    private void step() {
        Engine e = Engine.get();
        if (!e.isRunning() || e.isPaused()) return;
        double p = e.pos();
        if (p >= e.total) {
            if (!endHandled) { endHandled = true; onFinish(); }
            return;
        }
        int k = e.idxAt(p);
        Engine.Seg s = e.segs.get(k);
        if (k != lastIdx) { lastIdx = k; fired.clear(); enter(s); }
        double into = p - e.starts[k], rem = s.s - into;
        for (int m = 0; m < s.marks.size(); m++) {
            Engine.Mark mk = s.marks.get(m);
            if (into >= mk.at && into < mk.at + 2 && fired.add("m" + m)) {
                if (e.vib && mk.vib != null) Cues.vibrate(this, mk.vib);
                if (e.voice && !e.quiet && mk.say != null && !mk.say.isEmpty()) Cues.get(this).say(mk.say, e.lang, e.rate);
            }
        }
        int sec = (int) Math.ceil(rem);
        if (s.s >= 5 && sec >= 1 && sec <= 3 && fired.add("c" + sec)) {
            if (e.beep && !e.quiet) Cues.get(this).beep();
            if (e.vib && s.cdVib) Cues.vibrate(this, new long[]{90});
        }
    }

    private void enter(Engine.Seg s) {
        Engine e = Engine.get();
        if (e.vib) Cues.vibrate(this, s.vib);
        Cues.get(this).cue(s.tone, s.say, e.lang, e.rate, e.beep && !e.quiet, e.voice && !e.quiet);
        refresh();
        if (e.notify) postCue(s.title, s.sub);
    }

    private void onFinish() {
        Engine e = Engine.get();
        if (e.vib) Cues.vibrate(this, e.endVib);
        Cues.get(this).cue("end", e.endSay, e.lang, e.rate, e.beep && !e.quiet, e.voice && !e.quiet);
        e.finish();
        if (e.notify) postCue("完成了", e.title);
        h.postDelayed(() -> { if (!Engine.get().isRunning()) end(true); }, 6000);
    }

    private void end(boolean removeNotif) {
        h.removeCallbacks(loop);
        if (wl != null && wl.isHeld()) wl.release();
        wl = null;
        stopForeground(removeNotif ? STOP_FOREGROUND_REMOVE : STOP_FOREGROUND_DETACH);
        stopSelf();
    }

    private void wake() {
        if (wl == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "walkcoach:run");
        }
        if (!wl.isHeld()) wl.acquire(4L * 3600 * 1000);
    }

    // ---------- 通知 ----------
    private void channels() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel run = new NotificationChannel(CH_RUN, "運動進行中", NotificationManager.IMPORTANCE_LOW);
        run.setShowBadge(false);
        nm.createNotificationChannel(run);
        NotificationChannel cue = new NotificationChannel(CH_CUE, "換段提醒（手環）", NotificationManager.IMPORTANCE_HIGH);
        cue.setSound(null, null);
        cue.enableVibration(false);
        nm.createNotificationChannel(cue);
    }

    private PendingIntent svc(String action, int code) {
        return PendingIntent.getService(this, code, new Intent(this, CoachService.class).setAction(action),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification build(String title, String text, long endAt, boolean paused) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(this, CH_RUN)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_WORKOUT)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(new Notification.Action.Builder(null, paused ? "繼續" : "暫停", svc(ACTION_TOGGLE, 1)).build())
                .addAction(new Notification.Action.Builder(null, "下一段", svc(ACTION_SKIP, 2)).build())
                .addAction(new Notification.Action.Builder(null, "結束", svc(ACTION_STOP, 3)).build());
        if (endAt > 0 && !paused) {
            b.setUsesChronometer(true).setChronometerCountDown(true).setWhen(endAt).setShowWhen(true);
        }
        return b.build();
    }

    private void refresh() {
        Engine e = Engine.get();
        if (!e.isRunning() || e.segs.isEmpty()) return;
        double p = e.pos();
        int k = e.idxAt(p);
        Engine.Seg s = e.segs.get(k);
        double rem = e.starts[k] + s.s - p;
        long endAt = System.currentTimeMillis() + (long) (rem * 1000);
        String text = e.isPaused() ? "已暫停" : (s.sub.isEmpty() ? e.title : s.sub);
        String left = String.format(Locale.US, "　全部剩 %d:%02d", (int) (e.total - p) / 60, (int) (e.total - p) % 60);
        getSystemService(NotificationManager.class).notify(NID, build(s.title, text + left, endAt, e.isPaused()));
    }

    private void postCue(String title, String text) {
        Notification n = new Notification.Builder(this, CH_CUE)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setTimeoutAfter(10000)
                .setCategory(Notification.CATEGORY_REMINDER)
                .build();
        getSystemService(NotificationManager.class).notify(NID_CUE, n);
    }

    private void startFg(Notification n) {
        if (Build.VERSION.SDK_INT >= 34) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NID, n);
    }

    @Override
    public void onDestroy() {
        h.removeCallbacks(loop);
        if (wl != null && wl.isHeld()) wl.release();
        super.onDestroy();
    }
}
