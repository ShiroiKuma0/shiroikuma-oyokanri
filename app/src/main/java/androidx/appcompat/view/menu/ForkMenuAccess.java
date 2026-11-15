// SPDX-License-Identifier: GPL-3.0-or-later

package androidx.appcompat.view.menu;

import androidx.annotation.Nullable;

/**
 * Fork (白い熊, +104): the menu a menu item belongs to. {@code MenuItemImpl} keeps it in a
 * package-private field and offers no getter, and a row has to know whether any of its
 * neighbours carries an icon — hence this one class in AppCompat's package.
 */
public final class ForkMenuAccess {
    private ForkMenuAccess() {
    }

    @Nullable
    public static MenuBuilder menuOf(@Nullable MenuItemImpl item) {
        try {
            return item != null ? item.mMenu : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
