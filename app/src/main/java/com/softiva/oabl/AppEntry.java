package com.softiva.oabl;

import android.content.ComponentName;
import android.graphics.drawable.Drawable;

final class AppEntry {
    final String label;
    final ComponentName component;
    final Drawable icon;
    final String shortcutId;
    final String appLabel;

    AppEntry(String label, ComponentName component, Drawable icon) {
        this(label, component, icon, null, null);
    }

    AppEntry(String label, ComponentName component, Drawable icon, String shortcutId, String appLabel) {
        this.label = label;
        this.component = component;
        this.icon = icon;
        this.shortcutId = shortcutId;
        this.appLabel = appLabel;
    }

    boolean isShortcut() { return shortcutId != null; }
}
