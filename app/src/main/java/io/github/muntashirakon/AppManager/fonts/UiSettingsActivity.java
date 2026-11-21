// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.fonts;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;

import io.github.muntashirakon.AppManager.BaseActivity;
import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.util.UiUtils;

/**
 * Fork (白い熊, +123): the 白い熊 応用管理 UI screen. Until +121 it was a page inside Settings — back
 * arrow, the title "Settings" — which is not what it is: it is the fork's own screen, and
 * Settings is where its first pill leads. Hosts {@link FontsPreferences}, which builds the page.
 */
public class UiSettingsActivity extends BaseActivity {
    @NonNull
    public static Intent getIntent(@NonNull Context context) {
        return new Intent(context, UiSettingsActivity.class);
    }

    @Override
    protected void onAuthenticated(@Nullable Bundle savedInstanceState) {
        setContentView(R.layout.activity_ui_settings);
        setSupportActionBar(findViewById(R.id.toolbar));
        ActionBar ab = getSupportActionBar();
        if (ab != null) {
            ab.setTitle(R.string.pref_fonts);
            ab.setDisplayHomeAsUpEnabled(true);
        }
        View progress = findViewById(R.id.progress_linear);
        if (progress != null) progress.setVisibility(View.GONE);
        View container = findViewById(R.id.ui_settings_container);
        UiUtils.applyWindowInsetsAsPadding(container, false, true, true, true);
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.ui_settings_container, new FontsPreferences())
                    .commit();
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            getOnBackPressedDispatcher().onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
