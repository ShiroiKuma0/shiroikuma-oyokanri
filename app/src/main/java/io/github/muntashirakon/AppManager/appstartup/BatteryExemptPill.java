// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.appstartup;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.View;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.compat.DeviceIdleManagerCompat;
import io.github.muntashirakon.AppManager.compat.ManifestCompat;
import io.github.muntashirakon.AppManager.main.ApplicationItem;
import io.github.muntashirakon.AppManager.self.SelfPermissions;
import io.github.muntashirakon.AppManager.utils.ForkThemeUtils;
import io.github.muntashirakon.AppManager.utils.ThreadUtils;
import io.github.muntashirakon.AppManager.utils.UIUtils;

import androidx.appcompat.widget.AppCompatImageView;

/**
 * Fork (白い熊): binds the crossed-battery exemption icon that lives in a main-list row's strip.
 *
 * <p>It is the row counterpart of what used to be a switch inside the unrolled pane: it shows
 * whether the app is exempt from battery optimization (Doze), and a tap toggles it. The exempt
 * state is the <b>lit</b> glyph, optimized is the faded one, and a system-pinned exemption is
 * fainter still and only explains itself on a tap.
 *
 * <p><b>Why it is read off the main thread, and cached.</b> The state is a privileged binder call
 * ({@link DeviceIdleManagerCompat}); reading it on the main thread hangs the whole app while the
 * server binds (the +033 landmine family). So every row's state is resolved on a background thread
 * and cached per package — a list of hundreds of apps would otherwise re-ask on every bind and
 * every scroll. The icon starts in a neutral "checking" state and settles a moment later, exactly
 * as the old pane switch did. The cache is invalidated by our own writes; {@link #invalidateAll()}
 * is there for a refresh to clear it wholesale.
 *
 * <p>The adapter owns the click <em>listener</em> (it alone knows selection mode); this class owns
 * the visual state ({@link #apply}) and the toggle action ({@link #toggle}).
 */
public final class BatteryExemptPill {
    private BatteryExemptPill() {
    }

    /** package → {exempt, modifiable}. */
    private static final Map<String, boolean[]> CACHE = new ConcurrentHashMap<>();

    public static void invalidate(@NonNull String pkg) {
        CACHE.remove(pkg);
    }

    public static void invalidateAll() {
        CACHE.clear();
    }

    /**
     * Reflect this row's exemption state on {@code icon}. Installed apps only — Doze exemption has
     * nothing to act on otherwise. Tagged by package so a slow read that returns after the row was
     * recycled onto another app lands on nothing.
     */
    public static void apply(@NonNull AppCompatImageView icon, @NonNull ApplicationItem item) {
        if (!item.isInstalled) {
            icon.setVisibility(View.GONE);
            icon.setTag(null);
            return;
        }
        final String pkg = item.packageName;
        icon.setVisibility(View.VISIBLE);
        icon.setTag(pkg);
        BatteryExemptDrawable d = drawableOf(icon, ForkThemeUtils.getTextColor());
        boolean[] s = CACHE.get(pkg);
        if (s != null) {
            render(icon, d, item.label, s[0], s[1], false);
        } else {
            render(icon, d, item.label, false, false, true);   // checking…
            readAsync(icon, pkg, item.label);
        }
    }

    /**
     * Act on a tap: toggle the exemption if we may, otherwise explain why not. Flashes either way,
     * so the tap always registers.
     */
    public static void toggle(@NonNull Context context, @NonNull AppCompatImageView icon,
                              @NonNull ApplicationItem item) {
        final String pkg = item.packageName;
        boolean[] s = CACHE.get(pkg);
        if (s == null) {
            // State not known yet (still checking). Flash so the tap registers, resolve it, and
            // the user can tap again once the glyph has settled.
            flash(icon);
            readAsync(icon, pkg, item.label);
            return;
        }
        if (!s[1]) {
            flash(icon);
            UIUtils.displayLongToast(R.string.app_launch_exempt_pinned);
            return;
        }
        final boolean target = !s[0];
        flash(icon);
        BatteryExemptDrawable d = drawableOf(icon, ForkThemeUtils.getTextColor());
        render(icon, d, item.label, target, true, true);       // optimistic, pending the re-read
        final CharSequence label = item.label;
        ThreadUtils.postOnBackgroundThread(() -> {
            try {
                if (target) {
                    DeviceIdleManagerCompat.disableBatteryOptimization(pkg);
                } else {
                    DeviceIdleManagerCompat.enableBatteryOptimization(pkg);
                }
            } catch (Throwable ignore) {
                // The write may refuse; the re-read below is the truth either way.
            }
            boolean nowExempt;
            try {
                nowExempt = !DeviceIdleManagerCompat.isBatteryOptimizedApp(pkg);
            } catch (Throwable th) {
                nowExempt = !target;
            }
            CACHE.put(pkg, new boolean[]{nowExempt, true});
            final boolean fExempt = nowExempt;
            final boolean ok = nowExempt == target;
            ThreadUtils.postOnMainThread(() -> {
                if (!pkg.equals(icon.getTag())) {
                    return;
                }
                if (ok) {
                    // Fork (白い熊): the tap must say what it did — the glyph only brightens, which
                    // is easy to miss, so flash the state it has just been toggled to.
                    UIUtils.displayShortToast(fExempt
                                    ? R.string.app_launch_exempt_toast_on
                                    : R.string.app_launch_exempt_toast_off,
                            label);
                } else {
                    UIUtils.displayShortToast(R.string.app_launch_exempt_failed);
                }
                render(icon, drawableOf(icon, ForkThemeUtils.getTextColor()), label, fExempt, true, false);
            });
        });
    }

    private static void readAsync(@NonNull AppCompatImageView icon, @NonNull String pkg,
                                  CharSequence label) {
        ThreadUtils.postOnBackgroundThread(() -> {
            boolean modifiable;
            boolean exempt;
            try {
                modifiable = SelfPermissions.checkSelfOrRemotePermission(
                        ManifestCompat.permission.DEVICE_POWER)
                        && DeviceIdleManagerCompat.isExemptionRemovable(pkg);
            } catch (Throwable th) {
                modifiable = false;
            }
            try {
                exempt = !DeviceIdleManagerCompat.isBatteryOptimizedApp(pkg);
            } catch (Throwable th) {
                exempt = false;
            }
            CACHE.put(pkg, new boolean[]{exempt, modifiable});
            final boolean fExempt = exempt;
            final boolean fModifiable = modifiable;
            ThreadUtils.postOnMainThread(() -> {
                if (!pkg.equals(icon.getTag())) {
                    return;
                }
                render(icon, drawableOf(icon, ForkThemeUtils.getTextColor()), label,
                        fExempt, fModifiable, false);
            });
        });
    }

    @NonNull
    private static BatteryExemptDrawable drawableOf(@NonNull AppCompatImageView icon,
                                                    @ColorInt int ink) {
        Drawable cur = icon.getDrawable();
        if (cur instanceof BatteryExemptDrawable) {
            ((BatteryExemptDrawable) cur).setInk(ink);
            return (BatteryExemptDrawable) cur;
        }
        BatteryExemptDrawable d = new BatteryExemptDrawable(ink);
        icon.setImageDrawable(d);
        return d;
    }

    private static void render(@NonNull AppCompatImageView icon, @NonNull BatteryExemptDrawable d,
                               CharSequence label, boolean exempt, boolean modifiable,
                               boolean checking) {
        int alpha;
        int stateRes;
        if (checking) {
            alpha = 150;
            stateRes = R.string.app_launch_state_checking;
        } else if (exempt) {
            alpha = modifiable ? 255 : 140;
            stateRes = modifiable ? R.string.app_launch_state_exempt : R.string.app_launch_state_locked;
        } else {
            alpha = modifiable ? 107 : 77;
            stateRes = modifiable ? R.string.app_launch_state_optimised : R.string.app_launch_state_locked;
        }
        d.setAlpha(alpha);
        icon.invalidate();
        Context ctx = icon.getContext();
        icon.setContentDescription(ctx.getString(R.string.app_launch_exempt_label)
                + ": " + ctx.getString(stateRes));
    }

    private static void flash(@NonNull AppCompatImageView icon) {
        icon.animate().cancel();
        icon.setScaleX(1f);
        icon.setScaleY(1f);
        icon.animate().scaleX(1.32f).scaleY(1.32f).setDuration(110)
                .withEndAction(() -> icon.animate().scaleX(1f).scaleY(1f).setDuration(150).start())
                .start();
    }
}
