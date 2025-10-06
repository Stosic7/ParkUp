package com.stosic.parkup.core

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

object RankAutoUpdater {

    private const val TAG = "RankAutoUpdater"

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { FirebaseFirestore.getInstance() }

    private var reg: ListenerRegistration? = null
    private var lastSeenPoints: Long? = null
    private var isUpdating = false

    fun start() {
        stop()

        val uid = auth.currentUser?.uid ?: run {
            Log.d(TAG, "start(): no user → skip.")
            return
        }

        val meRef = db.collection("users").document(uid)

        reg = meRef.addSnapshotListener { snap, err ->
            if (err != null) {
                Log.w(TAG, "snapshot error", err)
                return@addSnapshotListener
            }
            if (snap == null || !snap.exists()) {
                Log.d(TAG, "no snapshot/user doc")
                return@addSnapshotListener
            }

            val pts = when (val p = snap.get("points")) {
                is Number -> p.toLong()
                is String -> p.toLongOrNull() ?: 0L
                else -> 0L
            }

            if (isUpdating) return@addSnapshotListener

            if (lastSeenPoints != null && lastSeenPoints == pts) return@addSnapshotListener
            lastSeenPoints = pts

            val q = db.collection("users").whereGreaterThan("points", pts)

            q.count().get(AggregateSource.SERVER)
                .addOnSuccessListener { agg ->
                    val newRank = (agg.count ?: 0L) + 1L

                    val existingRank = when (val r = snap.get("rank")) {
                        is Number -> r.toLong()
                        is String -> r.toLongOrNull() ?: 0L
                        else -> 0L
                    }
                    if (existingRank == newRank) {
                        Log.d(TAG, "rank already up-to-date: $newRank")
                        return@addOnSuccessListener
                    }

                    isUpdating = true
                    meRef.update("rank", newRank)
                        .addOnSuccessListener {
                            Log.d(TAG, "rank updated → $newRank (points=$pts)")
                        }
                        .addOnFailureListener { e ->
                            Log.w(TAG, "failed to update rank", e)
                        }
                        .addOnCompleteListener {
                            isUpdating = false
                        }
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "aggregate count failed", e)
                }
        }

        Log.d(TAG, "RankAutoUpdater started for $uid")
    }

    fun stop() {
        reg?.remove()
        reg = null
        lastSeenPoints = null
        isUpdating = false
    }
}
