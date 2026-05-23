/*
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.applock;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.hardware.HardwareBuffer;
import android.os.SystemClock;
import android.util.Slog;
import com.android.internal.R;
import com.android.server.wm.AppLockService;

import android.window.TaskSnapshot;

/**
 * Draws App Lock masking (scrim, lock icon, label) onto task snapshots for recents.
 */
public final class AppLockSnapshotMasker {
    private static final String TAG = "AppLockSnapshotMasker";
    private static final String AUTH_PACKAGE = "com.android.applock";
    private static final String LABEL_RES = "applock_recents_locked_label";
    private static final int SCRIM_COLOR = 0x99000000;
    private static final float ICON_DP = 32f;
    private static final float LABEL_TEXT_SP = 14f;
    private static final float LABEL_GAP_DP = 8f;

    private AppLockSnapshotMasker() {
    }

    /** Returns a masked copy when {@code packageName} is in the App Lock list; otherwise original. */
    public static TaskSnapshot maskIfNeeded(TaskSnapshot snapshot, String packageName,
            Context systemContext) {
        if (snapshot == null || packageName == null || systemContext == null) {
            return snapshot;
        }
        if (!AppLockService.get().shouldMaskRecentsSnapshot(packageName)) {
            return snapshot;
        }
        if (!snapshot.isBufferValid()) {
            return snapshot;
        }
        final HardwareBuffer buffer = snapshot.getHardwareBuffer();
        if (buffer == null) {
            return snapshot;
        }
        Bitmap source = null;
        Bitmap software = null;
        try {
            source = Bitmap.wrapHardwareBuffer(buffer, snapshot.getColorSpace());
            if (source == null) {
                return snapshot;
            }
            software = source.copy(Bitmap.Config.ARGB_8888, true);
            if (software == null) {
                return snapshot;
            }
            drawMask(software, systemContext);
            final Bitmap hardware = software.copy(Bitmap.Config.HARDWARE, false);
            if (hardware == null) {
                return snapshot;
            }
            final TaskSnapshot.Builder builder = new TaskSnapshot.Builder();
            builder.setId(System.currentTimeMillis());
            builder.setCaptureTime(snapshot.getCaptureTime());
            builder.setSnapshot(hardware.getHardwareBuffer());
            builder.setColorSpace(hardware.getColorSpace());
            builder.setDensityDpi(snapshot.getDensityDpi());
            builder.setRotation(snapshot.getRotation());
            builder.setOrientation(snapshot.getOrientation());
            builder.setTaskSize(snapshot.getTaskSize());
            builder.setContentInsets(snapshot.getContentInsets());
            builder.setLetterboxInsets(snapshot.getLetterboxInsets());
            builder.setIsRealSnapshot(snapshot.isRealSnapshot());
            builder.setIsTranslucent(false);
            builder.setTopActivityComponent(snapshot.getTopActivityComponent());
            builder.setWindowingMode(snapshot.getWindowingMode());
            builder.setAppearance(snapshot.getAppearance());
            builder.setUiMode(snapshot.getUiMode());
            builder.setHasImeSurface(snapshot.hasImeSurface());
            return builder.build();
        } catch (Exception e) {
            Slog.w(TAG, "Failed to mask snapshot for " + packageName, e);
            return snapshot;
        } finally {
            if (software != null) {
                software.recycle();
            }
            if (source != null) {
                source.recycle();
            }
        }
    }

    private static void drawMask(Bitmap bitmap, Context systemContext) {
        final int width = bitmap.getWidth();
        final int height = bitmap.getHeight();
        final float density = bitmap.getDensity() > 0
                ? bitmap.getDensity() : Resources.getSystem().getDisplayMetrics().densityDpi;
        final float scale = density / 160f;
        final Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(SCRIM_COLOR, PorterDuff.Mode.SRC_OVER);

        final Drawable lock = systemContext.getDrawable(R.drawable.ic_lock_lock);
        final String label = getLockedLabel(systemContext);
        final int iconSize = (int) (ICON_DP * scale + 0.5f);
        final float textSize = LABEL_TEXT_SP * scale;
        final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(textSize);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));

        final float textHeight = textPaint.descent() - textPaint.ascent();
        final float blockHeight = iconSize + LABEL_GAP_DP * scale + textHeight;
        final int centerX = width / 2;
        final float topY = (height - blockHeight) / 2f;

        if (lock != null) {
            lock.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
            final int left = centerX - iconSize / 2;
            final int top = (int) topY;
            lock.setBounds(left, top, left + iconSize, top + iconSize);
            lock.draw(canvas);
        }

        final float textY = topY + iconSize + LABEL_GAP_DP * scale - textPaint.ascent();
        canvas.drawText(label, centerX, textY, textPaint);
    }

    private static String getLockedLabel(Context systemContext) {
        try {
            Context pkgContext = systemContext.createPackageContext(AUTH_PACKAGE, 0);
            int resId = pkgContext.getResources().getIdentifier(LABEL_RES, "string", AUTH_PACKAGE);
            if (resId != 0) {
                return pkgContext.getString(resId);
            }
        } catch (PackageManager.NameNotFoundException e) {
            Slog.w(TAG, "App Lock package not found for recents label", e);
        }
        return "App Locked";
    }
}
