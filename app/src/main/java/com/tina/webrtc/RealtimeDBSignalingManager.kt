package com.tina.webrtc

import android.content.Context
import com.google.firebase.database.*

/**
 * Signaling over Firebase Realtime Database — same approach as Talksy's
 * RealtimeDBSignalingManager. No server to deploy/host; Firebase handles
 * the relay. Structure:
 *
 * rooms/{roomId}/
 *   callerOffer:   { sdp }
 *   calleeAnswer:  { sdp }
 *   callerCandidates/{pushId}: { sdpMid, sdpMLineIndex, candidate }
 *   calleeCandidates/{pushId}: { sdpMid, sdpMLineIndex, candidate }
 *   hangup: true
 */
class RealtimeDBSignalingManager(
    context: Context,
    private val roomId: String
) {
    private val db = FirebaseDatabase.getInstance().reference.child("rooms").child(roomId)

    private var offerListener: ValueEventListener? = null
    private var answerListener: ValueEventListener? = null
    private var callerCandidatesListener: ChildEventListener? = null
    private var calleeCandidatesListener: ChildEventListener? = null
    private var hangupListener: ValueEventListener? = null

    // ─── Offer / Answer ───────────────────────────────────────────────────

    fun sendOffer(sdp: String, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        db.child("callerOffer").child("sdp").setValue(sdp)
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { onFailure(it) }
    }

    fun listenForOffer(onOffer: (String) -> Unit) {
        offerListener = db.child("callerOffer").child("sdp")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    snapshot.getValue(String::class.java)?.let { onOffer(it) }
                }
                override fun onCancelled(error: DatabaseError) {}
            })
    }

    fun sendAnswer(sdp: String, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        db.child("calleeAnswer").child("sdp").setValue(sdp)
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { onFailure(it) }
    }

    fun listenForAnswer(onAnswer: (String) -> Unit) {
        answerListener = db.child("calleeAnswer").child("sdp")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    snapshot.getValue(String::class.java)?.let { onAnswer(it) }
                }
                override fun onCancelled(error: DatabaseError) {}
            })
    }

    // ─── ICE candidates ───────────────────────────────────────────────────

    /** The caller writes to callerCandidates, the callee reads from it, and vice versa. */
    fun sendIceCandidate(isCaller: Boolean, sdpMid: String?, sdpMLineIndex: Int, candidate: String) {
        val node = if (isCaller) "callerCandidates" else "calleeCandidates"
        val entry = mapOf(
            "sdpMid" to (sdpMid ?: ""),
            "sdpMLineIndex" to sdpMLineIndex,
            "candidate" to candidate
        )
        db.child(node).push().setValue(entry)
    }

    /** isCaller here means "I am the caller" — so I listen on calleeCandidates, and vice versa. */
    fun listenForIceCandidates(
        isCaller: Boolean,
        onCandidate: (sdpMid: String, sdpMLineIndex: Int, candidate: String) -> Unit
    ) {
        val nodeToListenTo = if (isCaller) "calleeCandidates" else "callerCandidates"
        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val sdpMid = snapshot.child("sdpMid").getValue(String::class.java) ?: return
                val sdpMLineIndex = snapshot.child("sdpMLineIndex").getValue(Int::class.java) ?: return
                val candidate = snapshot.child("candidate").getValue(String::class.java) ?: return
                onCandidate(sdpMid, sdpMLineIndex, candidate)
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        }
        db.child(nodeToListenTo).addChildEventListener(listener)

        if (isCaller) calleeCandidatesListener = listener else callerCandidatesListener = listener
    }

    // ─── Hang up ──────────────────────────────────────────────────────────

    fun listenForHangUp(onHangUp: () -> Unit) {
        hangupListener = db.child("hangup").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.getValue(Boolean::class.java) == true) onHangUp()
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    fun markRoomEnded() {
        db.child("hangup").setValue(true)
    }

    // ─── Cleanup ──────────────────────────────────────────────────────────

    fun cleanup() {
        offerListener?.let { db.child("callerOffer").child("sdp").removeEventListener(it) }
        answerListener?.let { db.child("calleeAnswer").child("sdp").removeEventListener(it) }
        callerCandidatesListener?.let { db.child("callerCandidates").removeEventListener(it) }
        calleeCandidatesListener?.let { db.child("calleeCandidates").removeEventListener(it) }
        hangupListener?.let { db.child("hangup").removeEventListener(it) }

        // Auto-clean the room after a short delay so a straggling listener isn't
        // orphaned mid-callback. Comment this out if you want call history/debugging.
        db.removeValue()
    }
}
