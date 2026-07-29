// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.battery;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

/**
 * Fork: column layout for the battery ranking, chosen from the same 3×3 grid
 * icon the main list uses. 0 = adaptive auto-fit (one column per 450 dp), 2/3/4
 * = fixed. Its own SharedPreferences file, so settings export/import covers it.
 */
public final class BatteryLayoutPrefs {
    public static final int COLUMNS_ADAPTIVE = 0;

    private static final String PREF_FILE = "shiroikuma_battery_layout";
    private static final String KEY_COLUMNS = "columns";

    private BatteryLayoutPrefs() {}

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
