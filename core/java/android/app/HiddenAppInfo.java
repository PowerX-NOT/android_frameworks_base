/*
 * SPDX-License-Identifier: Apache-2.0
 */
package android.app;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.graphics.Bitmap;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Metadata for a hidden app shown in the authenticated launcher drawer.
 *
 * @hide
 */
public final class HiddenAppInfo implements Parcelable {

    @NonNull
    public final String packageName;
    @NonNull
    public final String label;
    @Nullable
    public final ComponentName launchComponent;
    @Nullable
    public final Bitmap icon;

    /** @hide */
    public HiddenAppInfo(@NonNull String packageName, @NonNull String label,
            @Nullable ComponentName launchComponent, @Nullable Bitmap icon) {
        this.packageName = packageName;
        this.label = label;
        this.launchComponent = launchComponent;
        this.icon = icon;
    }

    private HiddenAppInfo(Parcel in) {
        packageName = in.readString();
        label = in.readString();
        launchComponent = in.readTypedObject(ComponentName.CREATOR);
        icon = in.readTypedObject(Bitmap.CREATOR);
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(packageName);
        dest.writeString(label);
        dest.writeTypedObject(launchComponent, flags);
        dest.writeTypedObject(icon, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final @NonNull Creator<HiddenAppInfo> CREATOR = new Creator<>() {
        @Override
        public HiddenAppInfo createFromParcel(Parcel in) {
            return new HiddenAppInfo(in);
        }

        @Override
        public HiddenAppInfo[] newArray(int size) {
            return new HiddenAppInfo[size];
        }
    };
}
