// SPDX-License-Identifier: Apache-2.0

package android.os;

import androidx.annotation.RequiresApi;

import misc.utils.HiddenUtil;

@RequiresApi(Build.VERSION_CODES.M)
public interface IDeviceIdleController extends IInterface {
    void addPowerSaveWhitelistApp(String name) throws RemoteException;
    void removePowerSaveWhitelistApp(String name) throws RemoteException;

    // Fork: the *system* whitelist is a second list entirely, and the one an OEM
    // ships its own apps on. removePowerSaveWhitelistApp only ever touches the
    // user list, so without these a ROM-whitelisted app could never be optimised
    // (measured on a Motorola razr 40 ultra: com.android.vending arrives as a
    // system entry and the plain remove is a silent no-op). Safe to declare here
    // because :hiddenapi is compileOnly — Stub.asInterface resolves against the
    // framework's own class at runtime, so these bind to the real transactions.
    void removeSystemPowerWhitelistApp(String name) throws RemoteException;
    void restoreSystemPowerWhitelistApp(String name) throws RemoteException;

    // The three lists behind `dumpsys deviceidle whitelist`, whose output is
    // literally "user,<pkg>" / "system,<pkg>" / "system-excidle,<pkg>". Membership
    // is the only honest way to know whether an exemption can be taken away:
    // the except-idle list has no per-package removal on any release.
    String[] getUserPowerWhitelist() throws RemoteException;
    String[] getSystemPowerWhitelist() throws RemoteException;
    String[] getSystemPowerWhitelistExceptIdle() throws RemoteException;

    boolean isPowerSaveWhitelistExceptIdleApp(String name) throws RemoteException;
    boolean isPowerSaveWhitelistApp(String name) throws RemoteException;

    abstract class Stub {
        public static IDeviceIdleController asInterface(android.os.IBinder obj) {
            return HiddenUtil.throwUOE(obj);
        }
    }
}
