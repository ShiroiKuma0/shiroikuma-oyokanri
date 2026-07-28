// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.snooping.lever;

import android.annotation.UserIdInt;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.net.ConnectivityManagerHidden;
import android.net.NetworkPolicyManager;
import android.os.Build;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.annotation.WorkerThread;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.compat.ConnectivityManagerCompat;
import io.github.muntashirakon.AppManager.compat.ManifestCompat;
import io.github.muntashirakon.AppManager.compat.NetworkPolicyManagerCompat;
import io.github.muntashirakon.AppManager.logs.Log;
import io.github.muntashirakon.AppManager.self.SelfPermissions;
import io.github.muntashirakon.AppManager.snooping.NetBlockState;
import io.github.muntashirakon.AppManager.snooping.SnoopingState;

/**
 * Fork: can this app send what it collected off the phone?
 * <p>
 * The most consequential row on the page, and the one capability with no app-op
 * and no revocable permission behind it. {@code INTERNET} is not enforced by a
 * permission check at all: an app that declares it is put in the {@code inet}
 * group (gid 3003) when its uid is created at install, and there is no path —
 * for us, for {@code adb}, or for root — to take a live uid out of a group. Every
 * other capability on this tab only matters if the data can leave, so this row
 * has three positions rather than two:
 * <ol>
 *   <li><b>Allowed</b> — no policy, no firewall rule.</li>
 *   <li><b>No background mobile data</b> — the per-uid network policy
 *       ({@code POLICY_REJECT_METERED_BACKGROUND} on stock, stronger rungs where
 *       the ROM has them). Narrow, and honestly labelled: it bites only on a
 *       metered interface and only in the background.</li>
 *   <li><b>Blocked</b> — a firewall DENY on every interface, foreground included,
 *       via the per-uid firewall chains Android 13 exposes. This is the real cut,
 *       and it is the same mechanism a root firewall uses.</li>
 * </ol>
 * <b>Which chain and why:</b> {@code FIREWALL_CHAIN_OEM_DENY_2}. The chains
 * AOSP drives itself (dozable, standby, powersave, restricted, low-power standby)
 * are owned by {@code AppStandbyController} and friends, which rewrite their rules
 * whenever an app changes bucket — a rule of ours there would be silently undone.
 * The three {@code OEM_DENY} chains exist for exactly this purpose and AOSP never
 * touches them; the second is taken rather than the first on the assumption that
 * an OEM helping itself would reach for the first.
 * <p>
 * <b>Write-only on Android 13.</b> {@code getUidFirewallRule} arrived in Android
 * 14 and the chain state is not in any dump, so where the platform cannot be
 * asked, {@link NetBlockState} records what we wrote. That is weaker than this tab
 * likes, and acceptable only because this API <em>throws</em> when it refuses,
 * unlike app-ops, which accept a write and discard it.
 */
public class NetworkLever implements SnoopingLever {
    private static final String PERM_INTERNET = "android.permission.INTERNET";
    /**
     * Neither is public API nor in {@code ManifestCompat}. Shell holds
     * {@code NETWORK_SETTINGS} on this phone (checked 2026-07-28), which is what
     * {@code ConnectivityService.setUidFirewallRule} accepts alongside
     * {@code NETWORK_STACK}.
     */
    private static final String PERM_NETWORK_SETTINGS = "android.permission.NETWORK_SETTINGS";
    private static final String PERM_NETWORK_STACK = "android.permission.NETWORK_STACK";

    /** Ours by convention — see the class comment. */
    private static final int CHAIN = ConnectivityManagerHidden.FIREWALL_CHAIN_OEM_DENY_2;

    /**
     * Candidate policy fields, strongest first. Each entry is one <em>set</em> of
     * field names that must all resolve for that rung to be usable — the
     * per-network rejections only make sense applied together.
     */
    private static final String[][] BLOCK_CANDIDATES = {
            // LineageOS 18+ / some OEM builds: reject everything, everywhere.
            {"POLICY_REJECT_ALL"},
            // LineageOS up to 17.1 called the same thing this.
            {"POLICY_NETWORK_ISOLATED"},
            // Both transports rejected individually amounts to the same thing.
            {"POLICY_REJECT_WIFI", "POLICY_REJECT_CELLULAR"},
            {"POLICY_REJECT_ON_WLAN", "POLICY_REJECT_ON_DATA"},
            // Stock AOSP: the only rung guaranteed to exist. Narrower than the
            // others — background traffic on metered networks — and the row says so.
            {"POLICY_REJECT_METERED_BACKGROUND"},
    };

    @Nullable
    private static volatile int[] sBlockMask;
    @Nullable
    private static volatile String sBlockNames;

    @WorkerThread
    @Override
    public boolean isApplicable(@NonNull PackageInfo packageInfo, @UserIdInt int userId) {
        if (packageInfo.applicationInfo == null) {
            return false;
        }
        if (blockMask() == 0 && !firewallAvailable()) {
            // Nothing on this platform can express a rejection: a switch here
            // would be decorative, which is the one thing this tab refuses to be.
            return false;
        }
        // An app that never asked for INTERNET cannot use the network however
        // permissive the policy is.
        return packageInfo.requestedPermissions != null
                && Arrays.asList(packageInfo.requestedPermissions).contains(PERM_INTERNET);
    }

    @Override
    public boolean isModifiable() {
        return SelfPermissions.checkSelfOrRemotePermission(ManifestCompat.permission.MANAGE_NETWORK_POLICY)
                || canFirewall();
    }

    @NonNull
    @Override
    public int[] supportedStates() {
        if (!firewallAvailable() || !canFirewall()) {
            // No real block available: the policy rung is all there is, and it
            // takes the "blocked" position rather than pretending to a middle one.
            return new int[]{SnoopingState.ALLOWED, SnoopingState.BLOCKED};
        }
        if (blockMask() == 0) {
            return new int[]{SnoopingState.ALLOWED, SnoopingState.BLOCKED};
        }
        return new int[]{SnoopingState.ALLOWED, SnoopingState.FOREGROUND, SnoopingState.BLOCKED};
    }

    @WorkerThread
    @Override
    public boolean isAllowed(@NonNull PackageInfo packageInfo, @UserIdInt int userId) {
        return getState(packageInfo, userId) != SnoopingState.BLOCKED;
    }

    @WorkerThread
    @Override
    public int getState(@NonNull PackageInfo packageInfo, @UserIdInt int userId) {
        if (packageInfo.applicationInfo == null) {
            return SnoopingState.ALLOWED;
        }
        int uid = packageInfo.applicationInfo.uid;
        if (isFirewallBlocked(packageInfo.packageName, uid)) {
            return SnoopingState.BLOCKED;
        }
        boolean threeState = supportedStates().length == 3;
        if (hasRejectPolicy(uid)) {
            // With no firewall to offer, the policy is the strongest thing this
            // row has, so it is what "blocked" means here.
            return threeState ? SnoopingState.FOREGROUND : SnoopingState.BLOCKED;
        }
        return SnoopingState.ALLOWED;
    }

    @WorkerThread
    @Override
    public boolean setAllowed(@NonNull PackageInfo packageInfo, @UserIdInt int userId, boolean allowed) {
        return setState(packageInfo, userId, SnoopingState.fromAllowed(allowed));
    }

    @WorkerThread
    @Override
    public boolean setState(@NonNull PackageInfo packageInfo, @UserIdInt int userId,
                            @SnoopingState.State int state) {
        if (packageInfo.applicationInfo == null) {
            return false;
        }
        String packageName = packageInfo.packageName;
        int uid = packageInfo.applicationInfo.uid;
        boolean threeState = supportedStates().length == 3;
        boolean wantFirewall = state == SnoopingState.BLOCKED && firewallAvailable() && canFirewall();
        // The policy rides along with the firewall block rather than being cleared
        // by it: if the chain is ever flushed (a reboot on a ROM that does not
        // persist it, an OEM helping itself to the same chain) the weaker
        // protection is still there, and it costs nothing.
        boolean wantPolicy = state == SnoopingState.BLOCKED || (threeState && state == SnoopingState.FOREGROUND);

        if (wantFirewall) {
            if (!setFirewallBlocked(packageName, uid, true)) {
                return false;
            }
        } else {
            setFirewallBlocked(packageName, uid, false);
        }
        setRejectPolicy(uid, wantPolicy);
        return getState(packageInfo, userId) == state;
    }

    @Nullable
    @Override
    public Boolean defaultAllowed() {
        return Boolean.TRUE;
    }

    @StringRes
    @Override
    public int stateLabelRes(@SnoopingState.State int state) {
        if (state == SnoopingState.FOREGROUND) {
            // "Only while in use" would be a lie: this rung is about metered
            // interfaces, not about whether you are looking at the app.
            return R.string.snooping_state_no_background_data;
        }
        return 0;
    }

    @Nullable
    @Override
    public CharSequence detail(@NonNull Context context) {
        String names = sBlockNames;
        if (names == null) {
            blockMask();
            names = sBlockNames;
        }
        if (firewallAvailable() && canFirewall()) {
            return names != null && !names.isEmpty()
                    ? context.getString(R.string.snooping_detail_net_firewall_policy, names)
                    : context.getString(R.string.snooping_detail_net_firewall);
        }
        return names != null && !names.isEmpty()
                ? context.getString(R.string.snooping_detail_net_policy, names)
                : null;
    }

    // ── The real block: a per-uid firewall DENY on every interface ───────────

    private static boolean firewallAvailable() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
    }

    private static boolean canFirewall() {
        return firewallAvailable()
                && (SelfPermissions.checkSelfOrRemotePermission(PERM_NETWORK_SETTINGS)
                || SelfPermissions.checkSelfOrRemotePermission(PERM_NETWORK_STACK));
    }

    @WorkerThread
    private static boolean isFirewallBlocked(@NonNull String packageName, int uid) {
        if (!firewallAvailable()) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                // Android 14+ can simply be asked, and its answer beats our record.
                return ConnectivityManagerCompat.getUidFirewallRule(CHAIN, uid)
                        == ConnectivityManagerHidden.FIREWALL_RULE_DENY;
            } catch (Throwable ignore) {
                // Fall through to the record.
            }
        }
        return NetBlockState.blockedUid(packageName) == uid;
    }

    @WorkerThread
    private static boolean setFirewallBlocked(@NonNull String packageName, int uid, boolean blocked) {
        if (!firewallAvailable() || !canFirewall()) {
            return false;
        }
        try {
            if (blocked) {
                // Enabling is idempotent and cannot be read back before Android
                // 14, so it is simply asserted every time rather than tracked.
                ConnectivityManagerCompat.setFirewallChainEnabled(CHAIN, true);
                ConnectivityManagerCompat.setUidFirewallRule(CHAIN, uid,
                        ConnectivityManagerHidden.FIREWALL_RULE_DENY);
                NetBlockState.setBlocked(packageName, uid);
                return true;
            }
            int recorded = NetBlockState.blockedUid(packageName);
            if (recorded != NetBlockState.NO_UID && recorded != uid) {
                // The app was reinstalled since we wrote the rule. Clear the old
                // uid too, or a DENY outlives the app and lands on whichever uid
                // the system recycles next.
                ConnectivityManagerCompat.setUidFirewallRule(CHAIN, recorded,
                        ConnectivityManagerHidden.FIREWALL_RULE_DEFAULT);
            }
            ConnectivityManagerCompat.setUidFirewallRule(CHAIN, uid,
                    ConnectivityManagerHidden.FIREWALL_RULE_DEFAULT);
            NetBlockState.clear(packageName);
            return true;
        } catch (Throwable th) {
            Log.w("NetworkLever", "Firewall rule for %s (uid %d) refused", th, packageName, uid);
            return false;
        }
    }

    // ── The narrower rung: per-uid network policy ────────────────────────────

    @WorkerThread
    private static boolean hasRejectPolicy(int uid) {
        int mask = blockMask();
        if (mask == 0) {
            return false;
        }
        try {
            return (NetworkPolicyManagerCompat.getUidPolicy(uid) & mask) != 0;
        } catch (Throwable th) {
            return false;
        }
    }

    @WorkerThread
    private static void setRejectPolicy(int uid, boolean reject) {
        int mask = blockMask();
        if (mask == 0) {
            return;
        }
        try {
            int policy = NetworkPolicyManagerCompat.getUidPolicy(uid);
            int updated;
            if (reject) {
                // Clearing the metered-background *allow* bit as well: leaving an
                // explicit allow next to an explicit reject is a contradiction the
                // framework resolves in the app's favour.
                updated = (policy & ~NetworkPolicyManager.POLICY_ALLOW_METERED_BACKGROUND) | mask;
            } else {
                updated = policy & ~mask;
            }
            if (updated != policy) {
                NetworkPolicyManagerCompat.setUidPolicy(uid, updated);
            }
        } catch (Throwable ignore) {
            // Best effort: the firewall is the load-bearing half.
        }
    }

    /**
     * The bits this device can reject traffic with, or {@code 0} when it has none.
     * Resolved once by reflection over {@link NetworkPolicyManager}'s own fields —
     * the same trick {@link NetworkPolicyManagerCompat} uses to build its
     * readable-policy map, and the reason this needs no per-ROM list.
     */
    private static int blockMask() {
        int[] cached = sBlockMask;
        if (cached != null) {
            return cached[0];
        }
        int mask = 0;
        List<String> names = new ArrayList<>();
        for (String[] candidate : BLOCK_CANDIDATES) {
            int rung = 0;
            List<String> rungNames = new ArrayList<>(candidate.length);
            boolean complete = true;
            for (String fieldName : candidate) {
                Integer value = constant(fieldName);
                if (value == null) {
                    complete = false;
                    break;
                }
                rung |= value;
                rungNames.add(fieldName);
            }
            if (complete && rung != 0) {
                mask = rung;
                names = rungNames;
                break;
            }
        }
        sBlockNames = TextUtils.join(" + ", names);
        sBlockMask = new int[]{mask};
        return mask;
    }

    @Nullable
    private static Integer constant(@NonNull String fieldName) {
        try {
            Field field = NetworkPolicyManager.class.getField(fieldName);
            return field.getInt(null);
        } catch (Throwable th) {
            return null;
        }
    }
}
