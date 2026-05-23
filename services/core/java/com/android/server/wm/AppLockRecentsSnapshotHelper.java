/*
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.wm;

import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.RenderNode;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.util.LruCache;
import android.util.Slog;

import java.util.ArrayList;
import java.util.List;
import android.view.Surface;
import android.view.ThreadedRenderer;
import android.window.TaskSnapshot;

import com.android.internal.graphics.ColorUtils;

/**
 * Draws a theme-aware recents placeholder (lock icon + label) for App Lock protected tasks.
 */
final class AppLockRecentsSnapshotHelper {
    private static final String TAG = "AppLockRecentsSnapshot";

    private static final int CACHE_SIZE = 24;

    private final LruCache<String, TaskSnapshot> mCache = new LruCache<>(CACHE_SIZE);

    @Nullable
    TaskSnapshot getSnapshot(Task task, Context context, boolean isLowResolution,
            @TaskSnapshot.ReferenceFlags int usage) {
        if (task == null || context == null) {
            return null;
        }
        final String cacheKey = buildCacheKey(task, context, isLowResolution);
        TaskSnapshot cached = mCache.get(cacheKey);
        if (cached != null) {
            cached.addReference(usage);
            return cached;
        }
        final TaskSnapshot snapshot = drawSnapshot(task, context, isLowResolution);
        if (snapshot == null) {
            return null;
        }
        mCache.put(cacheKey, snapshot);
        snapshot.addReference(usage);
        return snapshot;
    }

    void evictForTask(int taskId) {
        final List<String> toRemove = new ArrayList<>();
        for (String key : mCache.snapshot().keySet()) {
            if (key.startsWith(taskId + ":")) {
                toRemove.add(key);
            }
        }
        for (String key : toRemove) {
            final TaskSnapshot snapshot = mCache.remove(key);
            if (snapshot != null) {
                snapshot.closeBuffer();
            }
        }
    }

    private static String buildCacheKey(Task task, Context context, boolean isLowResolution) {
        final Configuration config = context.getResources().getConfiguration();
        final Rect bounds = task.getBounds();
        return task.mTaskId + ":" + config.uiMode + ":" + config.orientation + ":"
                + config.densityDpi + ":" + config.fontScale + ":" + isLowResolution + ":"
                + bounds.width() + "x" + bounds.height();
    }

    @Nullable
    private TaskSnapshot drawSnapshot(Task task, Context context, boolean isLowResolution) {
        final Rect taskBounds = task.getBounds();
        int taskWidth = taskBounds.width();
        int taskHeight = taskBounds.height();
        if (taskWidth <= 0 || taskHeight <= 0) {
            taskWidth = 1080;
            taskHeight = 1920;
        }

        float scale = context.getResources().getFloat(
                com.android.internal.R.dimen.config_highResTaskSnapshotScale);
        final float lowResScale = context.getResources().getFloat(
                com.android.internal.R.dimen.config_lowResTaskSnapshotScale);
        if (isLowResolution && lowResScale > 0 && scale > 0) {
            scale = lowResScale;
        }

        final int width = Math.max(1, (int) (taskWidth * scale));
        final int height = Math.max(1, (int) (taskHeight * scale));

        final int bgColor = ColorUtils.setAlphaComponent(context.getColor(
                com.android.internal.R.color.materialColorSurfaceContainerHigh), 255);
        final int fgColor = context.getColor(
                com.android.internal.R.color.materialColorOnSurface);

        final RenderNode node = RenderNode.create("AppLockRecentsSnapshot", null);
        node.setLeftTopRightBottom(0, 0, width, height);
        node.setClipToBounds(true);
        final RecordingCanvas canvas = node.start(width, height);
        canvas.drawColor(bgColor);

        final float density = context.getResources().getDisplayMetrics().density;
        final float scaledDensity = context.getResources().getDisplayMetrics().scaledDensity;
        final int iconSizePx = (int) (48 * density + 0.5f);
        final float textSizePx = 14 * scaledDensity;
        final int gapPx = (int) (12 * density + 0.5f);

        final Drawable lockIcon = context.getDrawable(com.android.internal.R.drawable.ic_lock);
        if (lockIcon != null) {
            lockIcon.mutate().setColorFilter(new PorterDuffColorFilter(fgColor, PorterDuff.Mode.SRC_IN));
            final int iconLeft = (width - iconSizePx) / 2;
            final int iconTop = (height - iconSizePx) / 2 - (int) (textSizePx / 2) - gapPx;
            lockIcon.setBounds(iconLeft, iconTop, iconLeft + iconSizePx, iconTop + iconSizePx);
            lockIcon.draw(canvas);
        }

        final String label = AppLockService.get().getRecentsLockedLabel();
        final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(fgColor);
        textPaint.setTextSize(textSizePx);
        textPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        textPaint.setTextAlign(Paint.Align.CENTER);
        final float textY = (height + iconSizePx) / 2f + gapPx + textSizePx * 0.35f;
        canvas.drawText(label, width / 2f, textY, textPaint);
        node.end(canvas);

        final Bitmap hwBitmap = ThreadedRenderer.createHardwareBitmap(node, width, height);
        if (hwBitmap == null) {
            Slog.w(TAG, "Failed to create hardware bitmap for task " + task.mTaskId);
            return null;
        }

        final ActivityRecord top = task.getTopNonFinishingActivity();
        final ComponentName topComponent = top != null ? top.mActivityComponent
                : (task.realActivity != null ? task.realActivity
                        : task.getBaseIntent().getComponent());
        final Configuration config = top != null ? top.getConfiguration() : task.getConfiguration();
        final int rotation = task.getWindowConfiguration() != null
                ? task.getWindowConfiguration().getRotation() : Surface.ROTATION_0;

        final TaskSnapshot.Builder builder = new TaskSnapshot.Builder();
        builder.setId(System.currentTimeMillis());
        builder.setCaptureTime(SystemClock.elapsedRealtimeNanos());
        builder.setTopActivityComponent(topComponent);
        builder.setSnapshot(hwBitmap.getHardwareBuffer());
        builder.setColorSpace(hwBitmap.getColorSpace());
        builder.setOrientation(config.orientation);
        builder.setRotation(rotation);
        builder.setTaskSize(new Point(taskWidth, taskHeight));
        builder.setContentInsets(new Rect());
        builder.setLetterboxInsets(new Rect());
        builder.setIsRealSnapshot(false);
        builder.setIsTranslucent(false);
        builder.setWindowingMode(task.getWindowingMode());
        builder.setUiMode(config.uiMode);
        builder.setDensityDpi(config.densityDpi);
        return builder.build();
    }
}
