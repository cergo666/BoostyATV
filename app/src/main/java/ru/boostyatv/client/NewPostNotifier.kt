package ru.boostyatv.client

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/** Schedules periodic background checks for new subscription videos and posts Android notifications. */
internal object NewPostNotifier {
    const val EXTRA_POST_SLUG = "open_post_slug"
    const val EXTRA_POST_ID = "open_post_id"
    private const val JOB_ID = 4201
    private const val CHANNEL_ID = "new_videos"
    private const val PREFS = "new_post_notifier"
    private const val KEY_LAST_SEEN = "last_seen_id"
    private const val INTERVAL_MS = 30L * 60L * 1000L

    fun syncSchedule(context: Context, enabled: Boolean) {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
        if (!enabled) {
            scheduler.cancel(JOB_ID)
            return
        }
        ensureChannel(context)
        val info = JobInfo.Builder(JOB_ID, ComponentName(context, NewPostJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(INTERVAL_MS)
            .build()
        scheduler.schedule(info)
    }

    fun markLatestSeen(context: Context, postId: String) {
        if (postId.isEmpty()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_SEEN, postId)
            .apply()
    }

    internal fun checkAndNotify(context: Context) {
        val options = PlaybackOptions(context)
        if (!options.notificationsEnabled()) return
        val api = BoostyApi(SessionStore(context))
        if (!api.hasSession()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val page = api.posts(null, "")
        val videos = page.posts().filter { it.hasVideo() }
        val latest = videos.firstOrNull() ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastSeen = prefs.getString(KEY_LAST_SEEN, "").orEmpty()
        prefs.edit().putString(KEY_LAST_SEEN, latest.id()).apply()
        if (lastSeen.isEmpty() || lastSeen == latest.id()) return

        val fresh = ArrayList<Post>()
        for (post in videos) {
            if (post.id() == lastSeen) break
            fresh.add(post)
            if (fresh.size >= 3) break
        }
        if (fresh.isEmpty()) return

        ensureChannel(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        for (post in fresh.reversed()) {
            val openIntent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_POST_SLUG, post.slug())
                .putExtra(EXTRA_POST_ID, post.id())
            val pending = PendingIntent.getActivity(
                context,
                post.id().hashCode(),
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val author = post.author().ifEmpty { context.getString(R.string.app_name) }
            val title = post.displayTitle(context)
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_play)
                .setContentTitle(author)
                .setContentText(title)
                .setStyle(NotificationCompat.BigTextStyle().bigText(title))
                .setAutoCancel(true)
                .setContentIntent(pending)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            manager.notify(post.id().hashCode(), notification)
        }
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notifications_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notifications_channel_description)
        }
        manager.createNotificationChannel(channel)
    }
}

class NewPostJobService : JobService() {
    @Volatile
    private var cancelled = false

    override fun onStartJob(params: JobParameters): Boolean {
        cancelled = false
        val appContext = applicationContext
        Thread({
            try {
                if (!cancelled) NewPostNotifier.checkAndNotify(appContext)
            } catch (e: Exception) {
                Log.d("BoostyATV", "Background new-video check failed", e)
            } finally {
                jobFinished(params, false)
            }
        }, "new-video-check").start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        cancelled = true
        return false
    }
}
