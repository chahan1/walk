package tw.chahan.walkcoach;

import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 一堂課的內容與計時（以 elapsedRealtime 為準，關螢幕也準確） */
final class Engine {
    private static final Engine I = new Engine();
    static Engine get() { return I; }

    static final class Mark { double at; String say; long[] vib; }
    static final class Seg {
        double s; String title = "", sub = "", say = "", tone = "easy";
        long[] vib; boolean cdVib; List<Mark> marks = new ArrayList<>();
    }

    String title = "", lang = "zh", endSay = "";
    boolean voice = true, beep = true, vib = true, quiet = false, notify = true;
    float rate = 1f;
    long[] endVib;
    final List<Seg> segs = new ArrayList<>();
    double[] starts = new double[0];
    double total;

    private boolean running, paused, done;
    private long base;
    private double pausedPos;

    synchronized void load(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        title = o.optString("title", "");
        lang = o.optString("lang", "zh");
        voice = o.optBoolean("voice", true);
        beep = o.optBoolean("beep", true);
        vib = o.optBoolean("vib", true);
        quiet = o.optBoolean("quiet", false);
        notify = o.optBoolean("notify", true);
        rate = (float) o.optDouble("rate", 1.0);
        endSay = o.optString("endSay", "");
        endVib = Cues.toLongs(o.optJSONArray("endVib"));
        segs.clear();
        JSONArray a = o.getJSONArray("segs");
        starts = new double[a.length()];
        double acc = 0;
        for (int i = 0; i < a.length(); i++) {
            JSONObject g = a.getJSONObject(i);
            Seg s = new Seg();
            s.s = Math.max(1, g.optDouble("s", 1));
            s.title = g.optString("title", "");
            s.sub = g.optString("sub", "");
            s.say = g.optString("say", "");
            s.tone = g.optString("tone", "easy");
            s.vib = Cues.toLongs(g.optJSONArray("vib"));
            s.cdVib = g.optBoolean("cdVib", false);
            JSONArray ms = g.optJSONArray("marks");
            if (ms != null) for (int k = 0; k < ms.length(); k++) {
                JSONObject m = ms.getJSONObject(k);
                Mark mk = new Mark();
                mk.at = m.optDouble("at", 0);
                mk.say = m.optString("say", "");
                mk.vib = Cues.toLongs(m.optJSONArray("vib"));
                s.marks.add(mk);
            }
            segs.add(s);
            starts[i] = acc;
            acc += s.s;
        }
        total = acc;
        running = true; paused = false; done = false;
        base = SystemClock.elapsedRealtime();
    }

    synchronized boolean isRunning() { return running; }
    synchronized boolean isPaused() { return paused; }

    synchronized double pos() {
        if (done) return total;
        if (!running) return 0;
        if (paused) return pausedPos;
        return (SystemClock.elapsedRealtime() - base) / 1000.0;
    }

    synchronized void pause() {
        if (!running || paused) return;
        pausedPos = pos();
        paused = true;
    }

    synchronized void resume() {
        if (!running || !paused) return;
        base = SystemClock.elapsedRealtime() - (long) (pausedPos * 1000);
        paused = false;
    }

    synchronized void seek(double t) {
        if (!running) return;
        t = Math.max(0, Math.min(total, t));
        if (paused) pausedPos = t;
        else base = SystemClock.elapsedRealtime() - (long) (t * 1000);
    }

    synchronized void finish() { running = false; paused = false; done = true; }
    synchronized void stop() { running = false; paused = false; done = false; }

    synchronized int idxAt(double p) {
        int i = 0;
        while (i + 1 < starts.length && starts[i + 1] <= p) i++;
        return i;
    }

    synchronized String stateJson() {
        double p = pos();
        return "{\"running\":" + running + ",\"paused\":" + paused + ",\"done\":" + done
                + ",\"pos\":" + p + ",\"total\":" + total + ",\"idx\":" + (segs.isEmpty() ? -1 : idxAt(p)) + "}";
    }
}
