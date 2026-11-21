package ru.boostyatv.client;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.util.Log;
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

    record UiState(boolean updating, boolean downloading, int downloadPercent,
                   String statusMessage, AppRelease offer) {}

    interface Listener {
        void onUpdateState(UiState state);
    }

    private final Activity activity;
    private final Host host;
    private final ExecutorService network = AppExecutors.network("update", 1);
    private Listener listener;
    private AppRelease startupUpdate;
    private AppRelease offeredRelease;
    private String statusMessage = "";
    private int downloadPercent = -1;
    private boolean updating;
    private boolean downloading;
    private volatile boolean installCancelled;

    UpdateController(Activity activity, Host host) {
        this.activity = activity;
        this.host = host;
    }

    void setListener(Listener listener) {
        this.listener = listener;
        emit();
    }

    UiState state() {
        return new UiState(updating, downloading, downloadPercent, statusMessage, offeredRelease);
    }

    private void emit() {
        Listener current = listener;
        if (current != null) current.onUpdateState(state());
    }

    void checkInteractive() {
        if (updating) return;
        updating = true;
        statusMessage = activity.getString(R.string.about_updates_checking);
        emit();
        request(AppRelease::latest, release -> {
            updating = false;
            if (!release.newerThan(BuildConfig.VERSION_NAME)) {
                statusMessage = activity.getString(R.string.about_updates_latest);
                emit();
                return;
            }
            startupUpdate = null;
            offeredRelease = release;
            statusMessage = activity.getString(R.string.about_update_available, release.version());
            emit();
        }, failure -> {
            updating = false;
            statusMessage = activity.getString(R.string.about_updates_check_failed);
            emit();
        });
    }

    void checkBackground() {
        if (updating) return;
        updating = true;
        emit();
        request(AppRelease::latest, release -> {
            updating = false;
            if (!release.newerThan(BuildConfig.VERSION_NAME)) {
                emit();
                return;
            }
            startupUpdate = release;
            emit();
            showStartupUpdate();
        }, failure -> {
            updating = false;
            emit();
        });
    }

    void showStartupUpdate() {
        if (startupUpdate == null || updating || offeredRelease != null || !host.canShowUpdate()) return;
        offeredRelease = startupUpdate;
        startupUpdate = null;
        emit();
    }

    void dismissOffer() {
        offeredRelease = null;
        emit();
    }

    void acceptOffer() {
        AppRelease release = offeredRelease;
        if (release == null) return;
        offeredRelease = null;
        emit();
        install(release);
    }

    void cancelDownload() {
        installCancelled = true;
    }

    void destroy() {
        installCancelled = true;
        offeredRelease = null;
        network.shutdownNow();
    }

    private void install(AppRelease release) {
        if (updating) return;
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            // Keep the release so the offer reappears after the permission round-trip.
            startupUpdate = release;
            statusMessage = activity.getString(R.string.update_install_unknown_sources);
            emit();
            Toast.makeText(activity, statusMessage, Toast.LENGTH_LONG).show();
            try {
                activity.startActivity(new Intent(
                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName())));
            } catch (ActivityNotFoundException e) {
                statusMessage = activity.getString(R.string.update_install_unavailable);
                emit();
                Toast.makeText(activity, statusMessage, Toast.LENGTH_LONG).show();
            }
            return;
        }
        updating = true;
        downloading = true;
        downloadPercent = -1;
        installCancelled = false;
        statusMessage = activity.getString(R.string.update_downloading);
        emit();
        request(() -> release.download(activity.getCacheDir(), (downloaded, total) -> {
            if (total > 0) {
                int percent = (int) Math.min(100, downloaded * 100 / total);
                activity.runOnUiThread(() -> {
                    if (!downloading) return;
                    downloadPercent = percent;
                    emit();
                });
            }
            return !installCancelled;
        }), apk -> {
            updating = false;
            downloading = false;
            downloadPercent = -1;
            if (installCancelled) {
                statusMessage = activity.getString(R.string.update_download_cancelled);
                emit();
                return;
            }
            if (!isNewerPackage(apk)) {
                statusMessage = activity.getString(R.string.update_verification_failed);
                emit();
                Toast.makeText(activity, statusMessage, Toast.LENGTH_LONG).show();
                return;
            }
            Uri uri = androidx.core.content.FileProvider.getUriForFile(activity,
                activity.getPackageName() + ".updates", apk);
            try {
                activity.startActivity(new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK));
                statusMessage = activity.getString(R.string.update_install_confirm);
                emit();
            } catch (ActivityNotFoundException e) {
                statusMessage = activity.getString(R.string.update_installer_unavailable);
                emit();
                Toast.makeText(activity, statusMessage, Toast.LENGTH_LONG).show();
            }
        }, failure -> {
            updating = false;
            downloading = false;
            downloadPercent = -1;
            statusMessage = (failure instanceof java.io.InterruptedIOException || installCancelled)
                ? activity.getString(R.string.update_download_cancelled)
                : activity.getString(R.string.update_download_failed);
            emit();
            Toast.makeText(activity, statusMessage, Toast.LENGTH_LONG).show();
        });
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
