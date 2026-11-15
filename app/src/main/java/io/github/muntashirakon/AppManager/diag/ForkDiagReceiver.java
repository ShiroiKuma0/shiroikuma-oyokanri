// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.diag;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import io.github.muntashirakon.AppManager.R;

/** Fork: the two buttons of the diagnostics recording notification — Mark and Turn all off. */
public class ForkDiagReceiver extends BroadcastReceiver {
    public static final String ACTION_MARK = "io.github.muntashirakon.AppManager.diag.MARK";
    public static final String ACTION_ALL_OFF = "io.github.muntashirakon.AppManager.diag.ALL_OFF";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        if (ACTION_MARK.equals(intent.getAction())) {
            int n = ForkDiag.mark(context);
            Toast.makeText(context, context.getString(R.string.diag_mark_done, n), Toast.LENGTH_SHORT).show();
        } else if (ACTION_ALL_OFF.equals(intent.getAction())) {
            ForkDiag.turnAllOff(context);
        }
    }
}
