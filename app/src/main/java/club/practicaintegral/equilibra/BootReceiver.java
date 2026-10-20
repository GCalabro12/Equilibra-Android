package club.practicaintegral.equilibra;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Vuelve a poner el contador en marcha al encender el teléfono o al actualizar la app. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            StepService.start(context);
        } catch (RuntimeException ignored) {}
    }
}
