/*
 * Yalp Store
 * Copyright (C) 2018 Sergey Yeriomin <yeriomin@gmail.com>
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package com.github.yeriomin.yalpstore.install;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.support.v4.content.FileProvider;
import android.util.Log;

import com.github.yeriomin.yalpstore.BuildConfig;
import com.github.yeriomin.yalpstore.Paths;
import com.github.yeriomin.yalpstore.Util;
import com.github.yeriomin.yalpstore.model.App;
import com.github.yeriomin.yalpstore.notification.NotificationManagerWrapper;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

public class InstallerDefault extends InstallerAbstract {

    public InstallerDefault(Context context) {
        super(context);
    }

    @Override
    public boolean verify(App app) {
        if (background) {
            Log.e(getClass().getSimpleName(), "Background installation is not supported by default installer");
            return false;
        }
        return super.verify(app);
    }

    @Override
    protected void install(App app) {
        new NotificationManagerWrapper(context).cancel(app.getPackageName());
        if (Paths.getApkAndSplits(context, app.getPackageName(), app.getVersionCode()).size() > 1) {
            installSplitApks(app);
        } else {
            InstallationState.setSuccess(app.getPackageName());
            context.startActivity(getOpenApkIntent(app));
        }
    }

    private Intent getOpenApkIntent(App app) {
        Intent intent;
        File file = Paths.getApkPath(context, app.getPackageName(), app.getVersionCode());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            intent.setData(FileProvider.getUriForFile(context, BuildConfig.APPLICATION_ID + ".fileprovider", file));
            intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(Uri.fromFile(file), "application/vnd.android.package-archive");
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    private void installSplitApks(App app) {
        PackageInstaller mPackageInstaller = null;
        PackageInstaller.Session session = null;
        PackageInstaller.SessionParams sessionParams = null;

        try {
            mPackageInstaller = context.getPackageManager().getPackageInstaller();
            sessionParams = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);

            int sessionID = mPackageInstaller.createSession(sessionParams);
            session = mPackageInstaller.openSession(sessionID);
            Log.d("YalpStore", "sessionID: " + sessionID);

            List<File> apks = Paths.getApkAndSplits(context, app.getPackageName(), app.getVersionCode());
            InputStream inputStream;
            OutputStream outputStream;
            for (File apk: apks) {
                inputStream = new FileInputStream(apk);
                outputStream = session.openWrite(apk.getName(), 0, apk.length());
                Util.copyStream(inputStream, outputStream);
                session.fsync(outputStream);
                outputStream.close();
                Log.d("YalpStore", "streamed input " + apk.getName());
            }

            Intent callbackIntent = new Intent(context, SplitAPKInstallerService.class);
            PendingIntent pendingIntent = PendingIntent.getService(context, 0, callbackIntent, 0);
            Log.d("YalpStore", "committing session");
            session.commit(pendingIntent.getIntentSender());
        } catch (Exception e) {
            Log.e(getClass().getSimpleName(), e.getMessage());
        } finally {
            if (session != null) {
                session.close();
            }
        }
    }

}
