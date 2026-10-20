package club.practicaintegral.equilibra;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.os.CancellationSignal;
import android.speech.RecognizerIntent;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.GetCredentialCancellationException;
import androidx.credentials.exceptions.GetCredentialException;
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;
import android.view.Gravity;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * App Android de Equilibra: muestra la web de siempre (https://gcalabro12.github.io/Equilibra/)
 * con una diferencia importante: la música de inicio suena sola al abrir (sin tener que tocar la pantalla).
 * La web sabe que está dentro de la app porque existe window.AndroidEquilibra.
 */
public class MainActivity extends Activity {
    private static final String WEB = "https://gcalabro12.github.io/Equilibra/";
    private static final String HOST = "gcalabro12.github.io";
    private static final int REQ_DICTAR = 42;
    /** ID de cliente web del proyecto Firebase equilibra-6aff8 (proveedor Google). */
    private static final String CLIENTE_WEB = "682128349771-a175emei7guudhv3gt08ra5vb3bcuuus.apps.googleusercontent.com";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView web;
    private TextView cargando;
    private boolean cargada;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF140F26);

        web = new WebView(this);
        web.setBackgroundColor(0xFF140F26);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));

        cargando = new TextView(this);
        cargando.setText("Equilibra");
        cargando.setTextColor(0xFFF4F0FB);
        cargando.setTextSize(30);
        cargando.setGravity(Gravity.CENTER);
        cargando.setBackgroundColor(0xFF140F26);
        root.addView(cargando, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        // Lo importante: el sonido puede empezar sin que la persona toque la pantalla
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        web.addJavascriptInterface(new Puente(), "AndroidEquilibra");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (HOST.equals(u.getHost()) || "file".equals(u.getScheme())) return false;
                // Inicio de sesión de Firebase: se queda dentro; el resto de enlaces se abren fuera
                if (u.getHost() != null && u.getHost().endsWith("firebaseapp.com")) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (RuntimeException ignored) {}
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                cargada = true;
                cargando.setVisibility(View.GONE);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                if (!req.isForMainFrame()) return;
                int c = err.getErrorCode();
                if (c == ERROR_HOST_LOOKUP || c == ERROR_CONNECT || c == ERROR_TIMEOUT)
                    view.loadUrl("file:///android_asset/sin-conexion.html");
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler h, SslError e) {
                h.cancel();
                view.loadUrl("file:///android_asset/sin-conexion.html");
            }
        });
        web.loadUrl(WEB);
        handler.postDelayed(() -> { if (!cargada) cargando.setVisibility(View.GONE); }, 15000);
        pedirPermisos();
    }

    /** Pasos: permiso de «Actividad física» (y avisos). Con permiso, el contador arranca y sigue con la pantalla apagada. */
    private void pedirPermisos() {
        // Si está PC & Body-mind, ella cuenta los pasos: aquí no se pide el permiso y se para el contador propio
        if (StepService.pcInstalada(this)) { StepService.start(this); return; }
        List<String> faltan = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED)
            faltan.add(Manifest.permission.ACTIVITY_RECOGNITION);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            faltan.add(Manifest.permission.POST_NOTIFICATIONS);
        if (faltan.isEmpty()) StepService.start(this);
        else requestPermissions(faltan.toArray(new String[0]), 1);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        StepService.start(this);
        web.evaluateJavascript("window.nativeRefresh && window.nativeRefresh()", null);
    }

    private boolean permisoPasos() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                || checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED;
    }

    private void alServicio(Intent i) {
        if (!permisoPasos()) return;
        i.setClass(this, StepService.class);
        try { startForegroundService(i); } catch (RuntimeException ignored) {}
    }

    @Override
    public void onBackPressed() {
        // La web decide: cerrar una hoja o volver a Inicio. Si ya está en Inicio, la app se va al fondo.
        web.evaluateJavascript("(function(){try{return window.atrasNativo ? atrasNativo() : false}catch(e){return false}})()", v -> {
            if (!"true".equals(v)) moveTaskToBack(true);
        });
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_DICTAR) return;
        String texto = null;
        if (res == RESULT_OK && data != null) {
            ArrayList<String> r = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (r != null && !r.isEmpty()) texto = r.get(0);
        }
        enviarDictado(texto, texto == null ? "No se ha entendido nada. Vuelve a intentarlo." : null);
    }

    /** Entrar con Google dentro de la app: el teléfono enseña sus cuentas de Google y devuelve un token a la web. */
    private void entrarConGoogle() {
        try {
            CredentialManager cm = CredentialManager.create(this);
            GetCredentialRequest req = new GetCredentialRequest.Builder()
                    .addCredentialOption(new GetSignInWithGoogleOption.Builder(CLIENTE_WEB).build())
                    .build();
            cm.getCredentialAsync(this, req, new CancellationSignal(), getMainExecutor(),
                    new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                        @Override public void onResult(GetCredentialResponse r) {
                            try {
                                if (r.getCredential() instanceof CustomCredential
                                        && GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(r.getCredential().getType())) {
                                    String token = GoogleIdTokenCredential.createFrom(r.getCredential().getData()).getIdToken();
                                    enviarGoogle(token, null);
                                } else enviarGoogle(null, "Google no ha devuelto una cuenta válida.");
                            } catch (RuntimeException e) { enviarGoogle(null, "No se pudo leer la cuenta de Google: " + e.getMessage()); }
                        }
                        @Override public void onError(GetCredentialException e) {
                            if (e instanceof GetCredentialCancellationException) enviarGoogle(null, "");
                            else enviarGoogle(null, "No se pudo entrar con Google (" + e.getType() + "). Usa email y PIN.");
                        }
                    });
        } catch (RuntimeException e) {
            enviarGoogle(null, "No se pudo abrir el inicio de sesión de Google: " + e.getMessage());
        }
    }

    private void enviarGoogle(String token, String error) {
        web.evaluateJavascript("window.nativeGoogle && window.nativeGoogle("
                + (token == null ? "null" : JSONObject.quote(token)) + ","
                + (error == null ? "null" : JSONObject.quote(error)) + ")", null);
    }

    private void enviarDictado(String texto, String error) {
        web.evaluateJavascript("window.nativeDictation && window.nativeDictation("
                + (texto == null ? "null" : JSONObject.quote(texto)) + ","
                + (error == null ? "null" : JSONObject.quote(error)) + ")", null);
    }

    /** Funciones que la web puede llamar con window.AndroidEquilibra.xxx() */
    class Puente {
        /** Dictado con el reconocimiento de voz de Google (la web no puede usar el micrófono dentro de la app). */
        @JavascriptInterface public void dictar() {
            runOnUiThread(() -> {
                Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES");
                i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Dime tu acción");
                try {
                    startActivityForResult(i, REQ_DICTAR);
                } catch (ActivityNotFoundException e) {
                    enviarDictado(null, "Este teléfono no tiene el dictado por voz de Google. Usa el micrófono del teclado.");
                }
            });
        }

        /** Entrar con Google (cuentas del teléfono). Responde con window.nativeGoogle(idToken, error). */
        @JavascriptInterface public void googleLogin() { runOnUiThread(MainActivity.this::entrarConGoogle); }

        @JavascriptInterface public String version() {
            try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
            catch (Exception e) { return "?"; }
        }

        // ---------- Pasos (podómetro del teléfono, también con la pantalla apagada) ----------
        @JavascriptInterface public String getToday() { return StepStore.todayJson(MainActivity.this); }

        @JavascriptInterface public String getHistory() { return StepStore.historyJson(MainActivity.this); }

        @JavascriptInterface public void setConfig(String json) {
            StepStore.setConfig(MainActivity.this, json);
            alServicio(new Intent(StepService.ACTION_REFRESH));
        }

        /** Habla en voz alta con la voz del teléfono (también con la pantalla apagada). */
        @JavascriptInterface public void speak(String text) {
            alServicio(new Intent(StepService.ACTION_SPEAK).putExtra("text", text));
        }

        @JavascriptInterface public boolean permissionOk() { return permisoPasos(); }

        @JavascriptInterface public boolean hasSensor() { return StepService.hasSensor; }

        @JavascriptInterface public boolean batteryOk() {
            PowerManager pm = getSystemService(PowerManager.class);
            return pm.isIgnoringBatteryOptimizations(getPackageName());
        }

        @JavascriptInterface public void openBatterySettings() {
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:" + getPackageName())));
                } catch (RuntimeException e) {
                    startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                }
            });
        }

        @JavascriptInterface public void openAppSettings() {
            runOnUiThread(() -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()))));
        }

        @JavascriptInterface public void askPermissions() { runOnUiThread(MainActivity.this::pedirPermisos); }

        /** ¿Está instalada PC & Body-mind? Entonces es ella la que cuenta los pasos. */
        @JavascriptInterface public boolean pcInstalada() { return StepService.pcInstalada(MainActivity.this); }

        @JavascriptInterface public void abrirPC() {
            runOnUiThread(() -> {
                Intent i = getPackageManager().getLaunchIntentForPackage("club.practicaintegral.pasos");
                if (i != null) startActivity(i);
            });
        }
    }
}
