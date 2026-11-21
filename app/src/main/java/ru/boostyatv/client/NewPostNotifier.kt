package ru.boostyatv.client

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
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit

internal object NewPostNotifier {
    const val EXTRA_POST_SLUG = "open_post_slug"
    const val EXTRA_POST_ID = "open_post_id"
    const val EXTRA_OPEN_FEED = "open_subscription_feed"
    private const val JOB_ID = 4201
    private const val CHANNEL_ID = "new_videos"
    private const val PREFS = "new_post_notifier"
    private const val KEY_LAST_SEEN = "last_seen_id"
    private const val KEY_PUBLISHED = "last_seen_published"
    private const val INTERVAL_MS = 30L * 60L * 1000L

    private val checkpointLock = Any()
    private var sessionGeneration = 0

    fun syncSchedule(context: Context, enabled: Boolean) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        if (!enabled) {
            scheduler.cancel(JOB_ID)
            return
        }
        ensureChannel(context)
        if (scheduler.getPendingJob(JOB_ID) != null) return
        val info = JobInfo.Builder(JOB_ID, ComponentName(context, NewPostJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(INTERVAL_MS)
            .build()
        scheduler.schedule(info)
    }

    fun startTracking(context: Context) = synchronized(checkpointLock) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_LAST_SEEN) && !prefs.contains(KEY_PUBLISHED)) {
            prefs.edit { putLong(KEY_PUBLISHED, System.currentTimeMillis() / 1000L) }
        }
    }

    fun reset(context: Context) = synchronized(checkpointLock) {
        sessionGeneration++
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { clear() }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        for (entry in manager.activeNotifications) {
            if (entry.notification.channelId == CHANNEL_ID) manager.cancel(entry.tag, entry.id)
        }
    }

    internal data class Checkpoint(val id: String, val published: Long)

    internal data class Scan(val latest: Checkpoint?, val fresh: List<Post>)

    internal fun scan(
        previous: Checkpoint,
        loadPage: (String) -> BoostyApi.Page,
        cancelled: () -> Boolean,
    ): Scan? {
        val fresh = LinkedHashMap<String, Post>()
        val offsets = HashSet<String>()
        var offset = ""
        var latest: Checkpoint? = null
        val initializing = previous.id.isEmpty() && previous.published == 0L
        while (offsets.add(offset)) {
            if (cancelled()) return null
            val page = loadPage(offset)
            var beyondCheckpoint = false
            for (post in page.posts()) {
                if (post.id() == previous.id && previous.id.isNotEmpty()) return Scan(latest, fresh.values.toList())
                if (previous.published > 0L && post.published() in 1 until previous.published) {
                    beyondCheckpoint = true
                    continue
                }
                if (post.id().isEmpty() || !post.allowed() || !post.hasVideo()) continue
                if (latest == null) latest = Checkpoint(post.id(), maxOf(post.published(), previous.published))
                if (initializing) return Scan(latest, emptyList())
                fresh.putIfAbsent(post.id(), post)
            }
            if (beyondCheckpoint) return Scan(latest, fresh.values.toList())
            offset = page.next()
            if (offset.isEmpty()) {
                // An older install stored only an ID, which may have left the feed.
                return Scan(latest, if (previous.published > 0L) fresh.values.toList() else emptyList())
            }
        }
        return null
    }

    internal fun checkAndNotify(context: Context, cancelled: () -> Boolean) {
        val options = PlaybackOptions(context)
        if (!options.notificationsEnabled()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val (generation, previous) = synchronized(checkpointLock) {
            sessionGeneration to Checkpoint(
                prefs.getString(KEY_LAST_SEEN, "").orEmpty(),
                prefs.getLong(KEY_PUBLISHED, 0L),
            )
        }
        val api = BoostyApi(SessionStore(context))
        if (!api.hasSession()) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(context)
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled() ||
            manager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE
        ) return

        val startedAt = System.currentTimeMillis() / 1000L
        val result = scan(previous, { api.posts(null, it) }, cancelled) ?: return
        val latest = result.latest ?: if (previous.id.isEmpty() && previous.published == 0L) {
            Checkpoint("", startedAt)
        } else {
            return
        }

        synchronized(checkpointLock) {
            if (generation != sessionGeneration || cancelled() || !options.notificationsEnabled()) return
            if (result.fresh.size > 3) {
                val count = result.fresh.size
                val style = NotificationCompat.InboxStyle()
                result.fresh.take(5).forEach { style.addLine(it.displayTitle(context)) }
                val summary = notification(context)
                    .setContentTitle(context.resources.getQuantityString(R.plurals.notifications_video_count, count, count))
                    .setStyle(style)
                    .setContentIntent(openIntent(context, null))
                    .build()
                manager.notify(CHANNEL_ID, JOB_ID, summary)
            } else {
                for (post in result.fresh.asReversed()) {
                    val title = post.displayTitle(context)
                    val entry = notification(context)
                        .setContentTitle(post.author().ifEmpty { context.getString(R.string.app_name) })
                        .setContentText(title)
                        .setStyle(NotificationCompat.BigTextStyle().bigText(title))
                        .setContentIntent(openIntent(context, post))
                        .build()
                    manager.notify(post.id(), JOB_ID, entry)
                }
            }
            prefs.edit {
                putString(KEY_LAST_SEEN, latest.id)
                putLong(KEY_PUBLISHED, latest.published)
            }
        }
    }

    private fun notification(context: Context) = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_play)
        .setAutoCancel(true)

    private fun openIntent(context: Context, post: Post?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (post == null) {
            intent.putExtra(EXTRA_OPEN_FEED, true)
        } else {
            intent.data = Uri.fromParts("boostyatv-notification", post.id(), null)
            intent.putExtra(EXTRA_POST_SLUG, post.slug())
            intent.putExtra(EXTRA_POST_ID, post.id())
        }
        return PendingIntent.getActivity(
            context, JOB_ID, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notifications_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.notifications_channel_description) },
        )
    }
}

class NewPostJobService : JobService() {
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var activeJob: JobParameters? = null

    override fun onStartJob(params: JobParameters): Boolean {
        activeJob = params
        Thread({
            try {
                NewPostNotifier.checkAndNotify(applicationContext) { activeJob !== params }
            } catch (e: Exception) {
                Log.d("BoostyATV", "Background new-video check failed", e)
            } finally {
                main.post {
                    if (activeJob === params) {
                        activeJob = null
                        jobFinished(params, false)
                    }
                }
            }
        }, "new-video-check").start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        if (activeJob === params) activeJob = null
        return false
    }
}
