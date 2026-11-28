package com.xfiles.android

import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.xfiles.feedback.FeedbackResult
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

internal class FirestoreReports @Inject constructor() {
    suspend fun sendFeedback(fields: Map<String, Any>): FeedbackResult {
        val write = try {
            FirebaseFirestore.getInstance().collection("feedback").document().set(fields)
        } catch (_: RuntimeException) {
            return unavailable()
        }
        return withTimeoutOrNull(ACK_TIMEOUT_MS) { write.awaitResult() } ?: FeedbackResult.Queued
    }

    /** Transactions require a server connection. The crash file remains available if sending fails. */
    suspend fun sendCrash(reportId: String, fields: Map<String, Any>): FeedbackResult {
        val write = try {
            val store = FirebaseFirestore.getInstance()
            val target = store.collection("crash_reports").document(reportId)
            store.runTransaction { transaction ->
                transaction.set(target, fields)
                Unit
            }
        } catch (_: RuntimeException) {
            return unavailable()
        }
        return write.awaitResult()
    }

    private suspend fun Task<*>.awaitResult(): FeedbackResult = suspendCancellableCoroutine { continuation ->
        addOnCompleteListener { task ->
            val result = when {
                task.isSuccessful -> FeedbackResult.Sent
                (task.exception as? FirebaseFirestoreException)?.code ==
                    FirebaseFirestoreException.Code.PERMISSION_DENIED ->
                    FeedbackResult.Failed("The reporting service did not accept this report.")
                else -> FeedbackResult.Failed("Could not send the report. Check your connection and try again.")
            }
            continuation.resume(result)
        }
    }

    private fun unavailable() = FeedbackResult.Failed("The reporting service is unavailable in this build.")

    private companion object {
        const val ACK_TIMEOUT_MS = 8_000L
    }
}
