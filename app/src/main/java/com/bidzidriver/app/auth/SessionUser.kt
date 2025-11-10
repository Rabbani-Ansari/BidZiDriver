package com.bidzidriver.app.auth





import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await

object SessionUser {
    @Volatile
    private var _currentUserId: String? = null

    fun initialize() {
        synchronized(this) {
            _currentUserId = FirebaseAuth.getInstance().currentUser?.uid
        }
    }

    fun clear() {
        synchronized(this) {
            _currentUserId = null
        }
    }

    // KEY FIX: Return String (non-null), never String?
    val currentUserId: String
        get() = synchronized(this) {
            _currentUserId ?: FirebaseAuth.getInstance().currentUser?.uid ?: ""
        }

    val isUserLoggedIn: Boolean
        get() = synchronized(this) {
            _currentUserId != null || FirebaseAuth.getInstance().currentUser != null
        }

    suspend fun refreshTokenIfNeeded(): Boolean {
        return try {
            FirebaseAuth.getInstance().currentUser?.getIdToken(true)?.await()
            synchronized(this) {
                _currentUserId = FirebaseAuth.getInstance().currentUser?.uid
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun addAuthStateListener(listener: FirebaseAuth.AuthStateListener) {
        FirebaseAuth.getInstance().addAuthStateListener(listener)
    }

    fun removeAuthStateListener(listener: FirebaseAuth.AuthStateListener) {
        FirebaseAuth.getInstance().removeAuthStateListener(listener)
    }
}



