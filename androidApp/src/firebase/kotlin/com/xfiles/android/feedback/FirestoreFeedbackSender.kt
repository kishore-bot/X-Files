package com.xfiles.android.feedback

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.xfiles.feedback.FeedbackDestination
import com.xfiles.feedback.FeedbackReport
import com.xfiles.feedback.FeedbackResult
import com.xfiles.feedback.FeedbackSender
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Stores each report as one document in `feedback`. The rules (firebase/firestore.rules) allow only creating
 * such documents, so the app can write feedback but never read, change or delete it.
 *
 * Offline: Firestore keeps the write in its on-device cache and sends it when a connection returns, so the
 * sender returns [FeedbackResult.Queued] without waiting. Online: it waits briefly for the server to accept it;
 * a slow answer also counts as queued, because the write is still delivered and retrying would duplicate it.
 */
internal class FirestoreFeedbackSender @Inject constructor(
    @ApplicationContext private val context: Context,
) : FeedbackSender {
    override val destination = FeedbackDestination.Cloud

    override suspend fun send(report: FeedbackReport): FeedbackResult {
        val document = buildMap<String, Any> {
            put("category", report.category.wire)
            put("message", report.message)
            put("createdAt", FieldValue.serverTimestamp())
            report.replyTo?.let { put("replyTo", it) }
            report.environment?.let { env ->
                put("appVersion", env.appVersion)
                put("versionCode", env.versionCode)
                put("deviceModel", env.deviceModel)
                put("androidSdk", env.androidSdk)
                put("flavor", env.flavor)
                put("locale", env.locale)
            }
        }
        return try {
            val collection = FirebaseFirestore.getInstance().collection(COLLECTION)
            if (!isOnline()) {
                collection.add(document) // Queued locally; the task completes once the server has it.
                FeedbackResult.Queued
            } else {
                val accepted = withTimeoutOrNull(ACK_TIMEOUT_MS) { collection.add(document).awaitTask() }
                if (accepted != null) FeedbackResult.Sent else FeedbackResult.Queued
            }
        } catch (e: FirebaseFirestoreException) {
            if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                FeedbackResult.Failed("The feedback service did not accept this report. Please try again later.")
            } else {
                FeedbackResult.Failed("Could not send. Check your connection and try again.")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: RuntimeException) {
            FeedbackResult.Failed("Feedback is not set up in this build.")
        }
    }

    private fun isOnline(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private companion object {
        const val COLLECTION = "feedback"
        const val ACK_TIMEOUT_MS = 12_000L
    }
}

private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        if (task.isSuccessful) {
            continuation.resume(task.result)
        } else {
            continuation.resumeWithException(task.exception ?: IllegalStateException("Firestore write failed"))
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal interface FirestoreFeedbackModule {
    @Binds
    fun feedbackSender(impl: FirestoreFeedbackSender): FeedbackSender
}
