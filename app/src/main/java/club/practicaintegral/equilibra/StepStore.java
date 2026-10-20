package club.practicaintegral.equilibra;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Guarda los pasos del día y el historial.
 * El podómetro del teléfono da un total acumulado desde que se encendió; aquí se convierte en pasos de hoy.
 */
final class StepStore {
    private StepStore() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("pasos", Context.MODE_PRIVATE);
    }

    static String todayKey() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    /** Si ha cambiado el día, pasa lo de ayer al historial y empieza de cero. */
    static synchronized void rollDay(Context c) {
        SharedPreferences p = prefs(c);
        String day = p.getString("day", null);
        String now = todayKey();
        if (now.equals(day)) return;
        SharedPreferences.Editor e = p.edit();
        if (day != null) {
            try {
                JSONObject h = new JSONObject(p.getString("history", "{}"));
                JSONObject d = new JSONObject();
                d.put("steps", p.getInt("today", 0));
                d.put("activeMs", p.getLong("activeMs", 0));
                h.put(day, d);
                // solo los últimos 90 días
                List<String> keys = new ArrayList<>();
                for (Iterator<String> it = h.keys(); it.hasNext(); ) keys.add(it.next());
                Collections.sort(keys);
                for (int i = 0; i < keys.size() - 90; i++) h.remove(keys.get(i));
                e.putString("history", h.toString());
            } catch (JSONException ignored) {}
        }
        e.putString("day", now).putInt("today", 0).putLong("activeMs", 0).apply();
    }

    /** Procesa una lectura del podómetro. Devuelve {pasos antes, pasos después}. */
    static synchronized int[] onSensor(Context c, float value) {
        rollDay(c);
        SharedPreferences p = prefs(c);
        float last = p.getFloat("lastSensor", -1f);
        int today = p.getInt("today", 0);
        int delta = 0;
        if (last >= 0) {
            delta = Math.round(value - last);
            if (delta < 0) delta = Math.round(value); // el teléfono se reinició: el contador empezó de cero
            if (delta > 50000) delta = 0;              // lectura imposible: se ignora
        }
        long now = System.currentTimeMillis();
        long active = p.getLong("activeMs", 0);
        long lastStep = p.getLong("lastStepTime", 0);
        if (delta > 0) {
            if (lastStep > 0 && now - lastStep < 15000) active += now - lastStep;
            lastStep = now;
        }
        p.edit().putFloat("lastSensor", value).putInt("today", today + delta)
                .putLong("activeMs", active).putLong("lastStepTime", lastStep).apply();
        return new int[]{today, today + delta};
    }

    static synchronized int[] addManual(Context c, int n) {
        rollDay(c);
        SharedPreferences p = prefs(c);
        int today = p.getInt("today", 0);
        int after = Math.max(0, today + n);
        p.edit().putInt("today", after).apply();
        return new int[]{today, after};
    }

    static synchronized void resetToday(Context c) {
        rollDay(c);
        prefs(c).edit().putInt("today", 0).putLong("activeMs", 0).apply();
    }

    static synchronized int steps(Context c) {
        rollDay(c);
        return prefs(c).getInt("today", 0);
    }

    static synchronized String todayJson(Context c) {
        rollDay(c);
        SharedPreferences p = prefs(c);
        try {
            JSONObject o = new JSONObject();
            o.put("date", p.getString("day", todayKey()));
            o.put("steps", p.getInt("today", 0));
            o.put("activeMs", p.getLong("activeMs", 0));
            return o.toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    static synchronized String historyJson(Context c) {
        rollDay(c);
        return prefs(c).getString("history", "{}");
    }

    // ---------- Ajustes de avisos (los manda la pantalla web) ----------
    static synchronized void setConfig(Context c, String json) {
        prefs(c).edit().putString("config", json).apply();
    }

    static synchronized JSONObject config(Context c) {
        try {
            return new JSONObject(prefs(c).getString("config", "{}"));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }
}
