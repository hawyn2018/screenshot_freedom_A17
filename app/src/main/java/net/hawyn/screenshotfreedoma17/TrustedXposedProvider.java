package net.hawyn.screenshotfreedoma17;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.content.res.Configuration;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;

import io.github.libxposed.service.XposedProvider;

/** Authenticates the direct Binder sender before the SDK can accept a framework connection. */
public final class TrustedXposedProvider extends ContentProvider {
    private final XposedProvider delegate = new XposedProvider();

    @Override
    public void attachInfo(Context context, ProviderInfo info) {
        super.attachInfo(context, info);
        // attachInfo supplies the SDK's real Context/ProviderInfo and invokes its onCreate once.
        // Calling delegate.onCreate ourselves would either omit that Context or initialize twice.
        delegate.attachInfo(context, info);
    }

    @Override
    public boolean onCreate() {
        // The framework publishes this provider only after our complete attachInfo returns.
        return true;
    }

    private static void enforceTrustedCaller() {
        // Read kernel-supplied identity on the incoming Binder thread. Neither attribution data
        // nor the supplied service Binder proves the identity of the process delivering it.
        int uid = Binder.getCallingUid();
        if (uid != 0 && uid != 1000) {
            throw new SecurityException("Untrusted framework service caller UID " + uid);
        }
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        enforceTrustedCaller();
        return method == null ? null : delegate.call(method, arg, extras);
    }

    @Override
    public Bundle call(String authority, String method, String arg, Bundle extras) {
        enforceTrustedCaller();
        // Preserve the SDK's legacy call implementation for both Android dispatch signatures.
        return method == null ? null : delegate.call(method, arg, extras);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {
        enforceTrustedCaller();
        return delegate.query(uri, projection, selection, selectionArgs, sortOrder);
    }

    @Override
    public String getType(Uri uri) {
        enforceTrustedCaller();
        return delegate.getType(uri);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        enforceTrustedCaller();
        return delegate.insert(uri, values);
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        enforceTrustedCaller();
        return delegate.delete(uri, selection, selectionArgs);
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        enforceTrustedCaller();
        return delegate.update(uri, values, selection, selectionArgs);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        delegate.onConfigurationChanged(newConfig);
    }

    @Override
    public void onLowMemory() {
        delegate.onLowMemory();
    }

    @Override
    public void onTrimMemory(int level) {
        delegate.onTrimMemory(level);
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
    }
}
