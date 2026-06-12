// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.main;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

// Fork: persists the main-list column layout chosen from the toolbar grid icon.
// 0 = adaptive auto-fit grid (the original layout, one column per 450dp);
// 2/3/4 = fixed column count. Dedicated SharedPreferences file so settings
// export/import picks it up automatically (it bundles shared_prefs/*.xml).
public final class MainLayoutPrefs {
    public static final int COLUMNS_ADAPTIVE = 0;

    private static final String PREF_FILE = "shiroikuma_main_layout";
    private static final String KEY_COLUMNS = "columns";

    private MainLayoutPrefs() {
    }

    @NonNull
    private static SharedPreferences prefs(@NonNull Context context) {
        return context.getApplicationContext().getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
    }

    public static int getColumns(@NonNull Context context) {
        return prefs(context).getInt(KEY_COLUMNS, COLUMNS_ADAPTIVE);
    }

    public static void setColumns(@NonNull Context context, int columns) {
        prefs(context).edit().putInt(KEY_COLUMNS, columns).apply();
    }
}
