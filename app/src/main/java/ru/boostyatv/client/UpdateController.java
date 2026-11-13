package ru.boostyatv.client;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/** Background update check, APK verification and installer hand-off. */
final class UpdateController {
    interface Host {
        boolean canShowUpdate();
    }

    private final Activity activity;
    private final Host host;
    private final ExecutorService network = AppExecutors.network("update", 1);
    private AppRelease startupUpdate;
    private boolean updating;
    private boolean downloading;
    private AlertDialog notifyDialog;
    private AlertDialog downloadDialog;
    private volatile boolean installCancelled;

    UpdateController(Activity activity, Host host) {
        this.activity = activity;
        this.host = host;
    }

    boolean isUpdating() {
        return updating;
    }

    boolean isDownloading() {
        return downloading;
    }

    void check(TextView status) {
        if (updating) return;
        updating = true;
        if (status != null) status.setText(activity.getString(R.string.about_updates_checking));
        request(AppRelease::latest, release -> {
            updating = false;
            if (!release.newerThan(BuildConfig.VERSION_NAME)) {
                if (status != null) status.setText(activity.getString(R.string.about_updates_latest));
                return;
            }
            if (status != null && status.isAttachedToWindow()) {
                startupUpdate = null;
                status.setText(activity.getString(R.string.about_update_available, release.version()));
                showDialog(release, status);
            } else {
                startupUpdate = release;
                showStartupUpdate();
            }
        }, failure -> {
            updating = false;
            if (status != null) status.setText(activity.getString(R.string.about_updates_check_failed));
        });
    }

    void showStartupUpdate() {
        if (startupUpdate == null || updating || downloadDialog != null || notifyDialog != null
            || !host.canShowUpdate()) return;
        AppRelease release = startupUpdate;
        startupUpdate = null;
        showDialog(release, null);
    }

    void destroy() {
        dismissDownload();
        dismissNotify();
        network.shutdownNow();
    }

    private void showDialog(AppRelease release, TextView status) {
        dismissNotify();
        notifyDialog = new AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.about_update_available, release.version()))
            .setMessage(release.notes().isBlank()
                ? activity.getString(R.string.about_no_changelog) : release.notes())
            .setNegativeButton(activity.getString(R.string.update_install_later), null)
            .setPositiveButton(activity.getString(R.string.update_install),
                (dialog, which) -> {
                    notifyDialog = null;
                    install(release, status);
                })
            .setOnDismissListener(dialog -> {
                if (notifyDialog == dialog) notifyDialog = null;
            })
            .show();
    }

    private void dismissNotify() {
        if (notifyDialog != null) {
            if (notifyDialog.isShowing()) notifyDialog.dismiss();
            notifyDialog = null;
        }
    }

    private void install(AppRelease release, TextView status) {
        if (updating) return;
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            // Keep the release so the offer reappears after the permission round-trip.
            startupUpdate = release;
            status(status, activity.getString(R.string.update_install_unknown_sources));
            try {
                activity.startActivity(new Intent(
                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName())));
            } catch (ActivityNotFoundException e) {
                status(status, activity.getString(R.string.update_install_unavailable));
            }
            return;
        }
        updating = true;
        downloading = true;
        installCancelled = false;
        status(status, activity.getString(R.string.update_downloading));
        int pad = (int) (24 * activity.getResources().getDisplayMetrics().density + 0.5f);
        android.widget.LinearLayout layout = new android.widget.LinearLayout(activity);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(pad, pad / 2, pad, 0);
        android.widget.ProgressBar bar = new android.widget.ProgressBar(activity, null,
            android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
        layout.addView(bar, new android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView progressText = new TextView(activity);
        progressText.setPadding(0, pad / 2, 0, 0);
        layout.addView(progressText, new android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        downloadDialog = new AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.update_download_title))
            .setView(layout)
            .setNegativeButton(activity.getString(R.string.cancel),
                (dialog, which) -> installCancelled = true)
            .setOnCancelListener(dialog -> installCancelled = true)
            .create();
        downloadDialog.show();
        request(() -> release.download(activity.getCacheDir(), (downloaded, total) -> {
            activity.runOnUiThread(() -> {
                if (downloadDialog == null) return;
                if (total > 0) {
                    bar.setIndeterminate(false);
                    int percent = (int) Math.min(100, downloaded * 100 / total);
                    bar.setProgress(percent);
                    progressText.setText(activity.getString(R.string.update_download_progress, percent));
                }
            });
            return !installCancelled;
        }), apk -> {
            updating = false;
            downloading = false;
            dismissDownload();
            if (!isNewerPackage(apk)) {
                status(status, activity.getString(R.string.update_verification_failed));
                return;
            }
            Uri uri = androidx.core.content.FileProvider.getUriForFile(activity,
                activity.getPackageName() + ".updates", apk);
            try {
                activity.startActivity(new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
                status(status, activity.getString(R.string.update_install_confirm));
            } catch (ActivityNotFoundException e) {
                status(status, activity.getString(R.string.update_installer_unavailable));
            }
        }, failure -> {
            updating = false;
            downloading = false;
            dismissDownload();
            if (failure instanceof java.io.InterruptedIOException || installCancelled) {
                status(status, activity.getString(R.string.update_download_cancelled));
            } else {
                status(status, activity.getString(R.string.update_download_failed));
            }
        });
    }

    private void dismissDownload() {
        if (downloadDialog != null) {
            if (downloadDialog.isShowing()) downloadDialog.dismiss();
            downloadDialog = null;
        }
    }

    private void status(TextView status, String message) {
        if (status != null && status.isAttachedToWindow()) status.setText(message);
        else Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
    }

    private <T> void request(Callable<T> request, Consumer<T> done, Consumer<Exception> failed) {
        network.execute(() -> {
            try {
                T result = request.call();
                activity.runOnUiThread(() -> {
                    if (!activity.isDestroyed() && !activity.isFinishing()) done.accept(result);
                });
            } catch (Exception error) {
                activity.runOnUiThread(() -> {
                    if (!activity.isDestroyed() && !activity.isFinishing()) failed.accept(error);
                });
            }
        });
    }

    @SuppressWarnings("deprecation")
    private boolean isNewerPackage(File apk) {
        int flags = android.os.Build.VERSION.SDK_INT >= 28
            ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo archive = activity.getPackageManager()
            .getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        if (archive == null || !activity.getPackageName().equals(archive.packageName)) return false;
        long code = android.os.Build.VERSION.SDK_INT >= 28
            ? archive.getLongVersionCode() : archive.versionCode;
        return code > BuildConfig.VERSION_CODE && sameSigner(archive);
    }

    @SuppressWarnings("deprecation")
    private boolean sameSigner(PackageInfo archive) {
        try {
            int flags = android.os.Build.VERSION.SDK_INT >= 28
                ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
            PackageInfo installed = activity.getPackageManager()
                .getPackageInfo(activity.getPackageName(), flags);
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                return java.util.Arrays.equals(
                    archive.signingInfo.getApkContentsSigners()[0].toByteArray(),
                    installed.signingInfo.getApkContentsSigners()[0].toByteArray());
            }
            return java.util.Arrays.equals(
                archive.signatures[0].toByteArray(), installed.signatures[0].toByteArray());
        } catch (Exception e) {
            Log.w("BoostyATV", "Downloaded APK signature check failed", e);
            return false;
        }
    }
}
