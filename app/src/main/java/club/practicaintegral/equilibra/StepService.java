package club.practicaintegral.equilibra;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import org.json.JSONObject;

import java.util.Locale;

/**
 * Servicio que se queda contando pasos siempre (pantalla apagada, app cerrada).
 * Usa el podómetro del teléfono, muestra los pasos en una notificación fija y dice los avisos en voz alta.
 */
public class StepService extends Service implements SensorEventListener {
    static final String ACTION_SPEAK = "club.practicaintegral.equilibra.SPEAK";
    static final String ACTION_REFRESH = "club.practicaintegral.equilibra.REFRESH";
    static final String ACTION_MANUAL = "club.practicaintegral.equilibra.MANUAL";
    private static final String CHANNEL = "contador";
    private static final int NOTIF_ID = 1;
    private static final Locale ES = new Locale("es", "ES");

    private SensorManager sensors;
    private PowerManager.WakeLock wakeLock;
    private TextToSpeech tts;
    private boolean ttsReady;
    private String pendingSpeech;
    private AudioManager audio;
    private AudioFocusRequest focus;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastNotif;
    static boolean hasSensor = true;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Pasos de hoy", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Muestra los pasos de hoy mientras la app cuenta");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);

        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH);
        } else {
            startForeground(NOTIF_ID, n);
        }

        sensors = getSystemService(SensorManager.class);
        // Preferimos el podómetro "que despierta" el teléfono: avisa a tiempo sin gastar batería
        Sensor s = sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER, true);
        boolean wakeUp = s != null;
        if (s == null) s = sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        hasSensor = s != null;
        if (s != null) {
            sensors.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL, wakeUp ? 3_000_000 : 0);
            if (!wakeUp) {
                // Sin podómetro "despertador": se mantiene el procesador despierto para no retrasar los avisos
                PowerManager pm = getSystemService(PowerManager.class);
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "equilibra:pasos");
                wakeLock.acquire();
            }
        }

        audio = getSystemService(AudioManager.class);
        tts = new TextToSpeech(this, status -> {
            if (status != TextToSpeech.SUCCESS) return;
            tts.setLanguage(ES);
            tts.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) {}
                @Override public void onDone(String id) { releaseFocus(); }
                @Override public void onError(String id) { releaseFocus(); }
            });
            ttsReady = true;
            if (pendingSpeech != null) { speak(pendingSpeech); pendingSpeech = null; }
        });

        handler.postDelayed(tick, 60_000);
    }

    // Cada minuto: cambio de día a medianoche aunque no haya pasos
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateNotification(true);
            handler.postDelayed(this, 60_000);
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case ACTION_SPEAK:
                    String text = intent.getStringExtra("text");
                    if (text != null) speak(text);
                    break;
                case ACTION_MANUAL:
                    int before = intent.getIntExtra("before", 0), after = intent.getIntExtra("after", 0);
                    announce(before, after);
                    updateNotification(true);
                    break;
                case ACTION_REFRESH:
                    updateNotification(true);
                    break;
            }
        }
        return START_STICKY;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        int[] r = StepStore.onSensor(this, event.values[0]);
        if (r[1] != r[0]) {
            announce(r[0], r[1]);
            updateNotification(false);
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    /** Avisos al cruzar cada N pasos, cada bloque y la meta del día (mismos textos que la web). */
    private void announce(int before, int after) {
        JSONObject cfg = StepStore.config(this);
        int goal = cfg.optInt("goal", 8000);
        int every = Math.max(50, cfg.optInt("every", 500));
        int block = Math.max(100, cfg.optInt("block", 2000));
        boolean voice = cfg.optBoolean("voice", true);
        boolean blockOn = cfg.optBoolean("blockOn", true);
        boolean goalHit = before < goal && after >= goal;
        String text = null;
        if (blockOn && after / block > before / block) {
            int k = after / block;
            text = "¡Enhorabuena! Ya has alcanzado " + (k * block) + " pasos. Bloque " + k + " completado.";
            if (goalHit) text += " ¡Y has cumplido la meta del día!";
        } else if (voice && after / every > before / every) {
            text = "Llevas " + ((after / every) * every) + " pasos.";
            if (goalHit) text += " ¡Has cumplido la meta del día!";
        } else if (goalHit) {
            text = "¡Has cumplido la meta del día!";
        }
        if (text == null) return;
        playTone(cfg.optString("sound", "campana"));
        if (goalHit) vibrate();
        final String t = text;
        handler.postDelayed(() -> speak(t), 700);
    }

    private void playTone(String sound) {
        int tone;
        switch (sound) {
            case "ninguno": return;
            case "bip": tone = ToneGenerator.TONE_PROP_BEEP2; break;
            case "silbato": tone = ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD; break;
            default: tone = ToneGenerator.TONE_PROP_ACK;
        }
        try {
            ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_MUSIC, 90);
            tg.startTone(tone, 400);
            handler.postDelayed(tg::release, 800);
        } catch (RuntimeException ignored) {}
    }

    private void vibrate() {
        Vibrator v = getSystemService(Vibrator.class);
        if (v != null) v.vibrate(VibrationEffect.createWaveform(new long[]{0, 150, 80, 150}, -1));
    }

    void speak(String text) {
        if (!ttsReady) { pendingSpeech = text; return; }
        // Baja el volumen de la música que suene (Spotify, YouTube…) mientras habla
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .build();
        audio.requestAudioFocus(focus);
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "aviso" + System.currentTimeMillis());
    }

    private void releaseFocus() {
        if (focus != null) { audio.abandonAudioFocusRequest(focus); focus = null; }
    }

    private void updateNotification(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastNotif < 1000) return;
        lastNotif = now;
        getSystemService(NotificationManager.class).notify(NOTIF_ID, buildNotification());
    }

    private Notification buildNotification() {
        int steps = StepStore.steps(this);
        JSONObject cfg = StepStore.config(this);
        int goal = cfg.optInt("goal", 8000);
        int block = Math.max(100, cfg.optInt("block", 2000));
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String title = hasSensor ? fmt(steps) + " pasos" : "Este teléfono no tiene podómetro";
        String text = "Bloque " + (steps / block + 1) + ": " + fmt(steps % block) + " / " + fmt(block)
                + "  ·  Meta " + fmt(goal);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_pasos)
                .setContentTitle(title)
                .setContentText(text)
                .setProgress(goal, Math.min(steps, goal), false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_STATUS)
                .setColor(0xFFF08A24)
                .setContentIntent(open)
                .build();
    }

    private static String fmt(int n) {
        return String.format(ES, "%,d", n);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (sensors != null) sensors.unregisterListener(this);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    /** PC & Body-mind tiene preferencia para contar pasos: si está instalada, Equilibra no cuenta. */
    static boolean pcInstalada(Context c) {
        try { c.getPackageManager().getPackageInfo("club.practicaintegral.pasos", 0); return true; }
        catch (Exception e) { return false; }
    }

    /** Arranca el servicio si ya hay permiso de actividad física (y no está PC & Body-mind; si está, lo para). */
    static void start(Context c) {
        if (pcInstalada(c)) { c.stopService(new Intent(c, StepService.class)); return; }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && c.checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        c.startForegroundService(new Intent(c, StepService.class));
    }
}
