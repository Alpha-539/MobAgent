package com.mobagent.app.auth

import com.mobagent.app.i18n.AppLanguage
import com.mobagent.app.i18n.tr
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.UserProfileChangeRequest
import com.mobagent.app.R
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/** Auth natijasi: muvaffaqiyat, foydalanuvchi bekor qildi (jim), yoki xato matni. */
sealed class AuthResult {
    object Success : AuthResult()
    object Cancelled : AuthResult()
    data class Error(val message: String) : AuthResult()
}

/** Hisobni o'chirish natijasi. */
sealed class DeleteResult {
    object Success : DeleteResult()
    object Cancelled : DeleteResult()
    data class Error(val message: String) : DeleteResult()
}

/**
 * Firebase Authentication atrofidagi yagona kirish nuqtasi: Email/Parol va
 * Google. `user` Compose holati — kirish/chiqishda ekranlar avtomatik
 * qayta chiziladi (AppRoot shu qiymatga qarab Login yoki Home'ni ko'rsatadi).
 */
object AuthRepository {
    var user by mutableStateOf<FirebaseUser?>(null)
        private set

    /**
     * Foydalanuvchi ismi. FirebaseUser obyekti ism o'zgarganda ham o'sha-o'sha obyekt
     * bo'lib qolgani uchun ism alohida Compose holatida saqlanadi — o'zgarganda
     * yon panel va Sozlamalar avtomatik yangilanadi.
     */
    var displayName by mutableStateOf<String?>(null)
        private set

    /** Google orqali kirilganmi. */
    val isGoogleUser: Boolean
        get() = user?.providerData?.any { it.providerId == "google.com" } == true

    /** Email/parol bilan ro'yxatdan o'tilganmi. */
    val hasPassword: Boolean
        get() = user?.providerData?.any { it.providerId == "password" } == true

    /** Email tasdiqlanganmi (Google bilan kirganlarda doim true). */
    var emailVerified by mutableStateOf(true)
        private set

    /** Faqat email/parol bilan kirgan va hali tasdiqlamagan foydalanuvchi uchun true. */
    val needsVerification: Boolean
        get() {
            val u = user ?: return false
            return !emailVerified && u.providerData.any { it.providerId == "password" }
        }

    private var started = false

    /** Firebase yuboradigan xatlar (tasdiqlash, parol tiklash) ilova tilida kelsin. */
    private fun applyEmailLanguage() {
        FirebaseAuth.getInstance().setLanguageCode(AppLanguage.effective.code)
    }

    fun init() {
        if (started) return
        started = true
        val auth = FirebaseAuth.getInstance()
        applyEmailLanguage()
        user = auth.currentUser
        displayName = auth.currentUser?.displayName
        emailVerified = auth.currentUser?.isEmailVerified ?: true
        auth.addAuthStateListener {
            user = it.currentUser
            displayName = it.currentUser?.displayName
            emailVerified = it.currentUser?.isEmailVerified ?: true
        }
    }

    suspend fun signInWithEmail(email: String, password: String): AuthResult = runAuth {
        FirebaseAuth.getInstance().signInWithEmailAndPassword(email.trim(), password).await()
    }

    suspend fun signUpWithEmail(email: String, password: String): AuthResult = runAuth {
        applyEmailLanguage()
        val auth = FirebaseAuth.getInstance()
        auth.createUserWithEmailAndPassword(email.trim(), password).await()
        // Hisob yaratildi; tasdiqlash xati yuborilmasa ham ro'yxatdan o'tish
        // muvaffaqiyatli hisoblanadi (foydalanuvchi keyin "qayta yuborish"ni bosadi).
        try {
            when (AuthMailApi.sendVerification(AppLanguage.effective.code)) {
                MailResult.Sent, MailResult.RateLimited -> {}
                MailResult.Unavailable -> auth.currentUser?.sendEmailVerification()?.await()
            }
        } catch (_: Exception) {}
    }

    suspend fun resendVerification(): AuthResult {
        applyEmailLanguage()
        // Avval o'z domenimizdan (Worker), bo'lmasa Firebase'ning standart xati.
        return when (AuthMailApi.sendVerification(AppLanguage.effective.code)) {
            MailResult.Sent -> AuthResult.Success
            MailResult.RateLimited -> AuthResult.Error(tr("Juda ko'p urinish. Birozdan so'ng qayta urinib ko'ring."))
            MailResult.Unavailable -> runAuth { FirebaseAuth.getInstance().currentUser?.sendEmailVerification()?.await() }
        }
    }

    /** Serverdan yangilab, email tasdiqlanganini qaytaradi. */
    suspend fun refreshVerified(): Boolean {
        val u = FirebaseAuth.getInstance().currentUser ?: return false
        try {
            u.reload().await()
            emailVerified = u.isEmailVerified
        } catch (_: Exception) {
        }
        return emailVerified
    }

    suspend fun sendPasswordReset(email: String): AuthResult {
        applyEmailLanguage()
        return when (AuthMailApi.sendReset(email.trim(), AppLanguage.effective.code)) {
            MailResult.Sent -> AuthResult.Success
            MailResult.RateLimited -> AuthResult.Error(tr("Juda ko'p urinish. Birozdan so'ng qayta urinib ko'ring."))
            MailResult.Unavailable -> runAuth { FirebaseAuth.getInstance().sendPasswordResetEmail(email.trim()).await() }
        }
    }

    /** `activityContext` — Activity bo'lishi shart (Google akkaunt tanlash oynasi uchun). */
    suspend fun signInWithGoogle(activityContext: Context): AuthResult {
        return try {
            // google-services plugin shu qiymatni google-services.json'dagi
            // web client ID'dan o'zi yaratadi.
            val webClientId = activityContext.getString(R.string.default_web_client_id)
            val manager = CredentialManager.create(activityContext)
            val result = try {
                val option = GetGoogleIdOption.Builder()
                    .setFilterByAuthorizedAccounts(false)
                    .setServerClientId(webClientId)
                    .build()
                manager.getCredential(
                    activityContext,
                    GetCredentialRequest.Builder().addCredentialOption(option).build()
                )
            } catch (e: NoCredentialException) {
                // Zaxira yo'l: "Google bilan kirish" tugmasi oqimi — akkaunt tanlash
                // yoki yangi akkaunt qo'shish oynasini o'zi ochadi.
                val btnOption = GetSignInWithGoogleOption.Builder(webClientId).build()
                manager.getCredential(
                    activityContext,
                    GetCredentialRequest.Builder().addCredentialOption(btnOption).build()
                )
            }
            val cred = result.credential
            if (cred is CustomCredential &&
                cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                val idToken = GoogleIdTokenCredential.createFrom(cred.data).idToken
                FirebaseAuth.getInstance()
                    .signInWithCredential(GoogleAuthProvider.getCredential(idToken, null))
                    .await()
                AuthResult.Success
            } else {
                AuthResult.Error(tr("Google akkaunti aniqlanmadi. Qayta urinib ko'ring."))
            }
        } catch (e: GetCredentialCancellationException) {
            AuthResult.Cancelled
        } catch (e: NoCredentialException) {
            AuthResult.Error(tr("Qurilmada Google akkaunt topilmadi. Sozlamalardan akkaunt qo'shing."))
        } catch (e: GetCredentialException) {
            AuthResult.Error(tr("Google bilan kirib bo'lmadi. Internetni tekshirib, qayta urinib ko'ring."))
        } catch (e: Exception) {
            AuthResult.Error(mapError(e))
        }
    }

    /** Ilovadagi ismni o'zgartiradi (Google hisobining o'zidagi ism o'zgarmaydi). */
    suspend fun updateDisplayName(name: String): AuthResult = runAuth {
        val u = FirebaseAuth.getInstance().currentUser ?: error(tr("Hisob topilmadi"))
        val clean = name.trim()
        u.updateProfile(UserProfileChangeRequest.Builder().setDisplayName(clean).build()).await()
        displayName = clean
    }

    /**
     * Hisobni butunlay o'chiradi. Firebase xavfsizlik uchun yaqinda kirilganini talab qiladi:
     *  - email/parol: `password` beriladi va shu bilan qayta tasdiqlanadi;
     *  - Google: kerak bo'lsa Google akkaunt tanlash oynasi ochiladi.
     * `onDeleted` hisob o'chgach darhol chaqiriladi (ekran yopilib ketsa ham bajariladi).
     */
    suspend fun deleteAccount(
        activityContext: Context,
        password: String?,
        onDeleted: () -> Unit
    ): DeleteResult {
        val u = FirebaseAuth.getInstance().currentUser ?: return DeleteResult.Error(tr("Hisob topilmadi."))
        return try {
            if (password != null) {
                val email = u.email ?: return DeleteResult.Error(tr("Email topilmadi."))
                u.reauthenticate(EmailAuthProvider.getCredential(email, password)).await()
            }
            try {
                performDelete(activityContext, u, onDeleted)
            } catch (e: FirebaseAuthRecentLoginRequiredException) {
                if (!isGoogleUser) {
                    return DeleteResult.Error(tr("Xavfsizlik uchun hisobdan chiqib, qayta kiring va yana urinib ko'ring."))
                }
                val idToken = googleIdToken(activityContext)
                    ?: return DeleteResult.Error(tr("Google akkaunti aniqlanmadi. Qayta urinib ko'ring."))
                u.reauthenticate(GoogleAuthProvider.getCredential(idToken, null)).await()
                performDelete(activityContext, u, onDeleted)
            }
            DeleteResult.Success
        } catch (e: GetCredentialCancellationException) {
            DeleteResult.Cancelled
        } catch (e: GetCredentialException) {
            DeleteResult.Error(tr("Google bilan tasdiqlab bo'lmadi. Internetni tekshirib, qayta urinib ko'ring."))
        } catch (e: Exception) {
            DeleteResult.Error(mapError(e))
        }
    }

    private suspend fun performDelete(context: Context, u: FirebaseUser, onDeleted: () -> Unit) {
        // Hisob o'chishi bilan AppRoot Login'ga o'tadi va Profil ekrani yopiladi —
        // tozalash ishi to'xtab qolmasligi uchun bekor qilib bo'lmaydigan blokda.
        withContext(NonCancellable) {
            // Bulutdagi suhbatlar hisob o'chishidan OLDIN (hali tizimga kirgan holatda) o'chiriladi.
            com.mobagent.app.history.CloudHistory.deleteAll(u.uid)
            com.mobagent.app.online.CloudKeys.deleteAll(context)
            // Serverdagi yulduzchalar (hisob o'chishidan oldin, token hali yaroqli)
            com.mobagent.app.model.ApiClient.deleteMyData()
            u.delete().await()
            com.mobagent.app.online.ApiKeyStore.clearAccount(context, u.uid)
            onDeleted()
            try {
                CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun googleIdToken(activityContext: Context): String? {
        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(activityContext.getString(R.string.default_web_client_id))
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val cred = CredentialManager.create(activityContext).getCredential(activityContext, request).credential
        return if (cred is CustomCredential &&
            cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) GoogleIdTokenCredential.createFrom(cred.data).idToken else null
    }

    suspend fun signOut(context: Context) {
        FirebaseAuth.getInstance().signOut()
        // Google'ning "avto-kirish" holatini ham tozalaymiz, aks holda keyingi
        // safar akkaunt tanlash oynasi o'tkazib yuborilishi mumkin.
        try {
            CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
        } catch (_: Exception) {
        }
    }

    private suspend fun runAuth(block: suspend () -> Unit): AuthResult = try {
        block()
        AuthResult.Success
    } catch (e: Exception) {
        AuthResult.Error(mapError(e))
    }

    private fun mapError(e: Exception): String = when {
        e is FirebaseTooManyRequestsException -> tr("Juda ko'p urinish. Birozdan so'ng qayta urinib ko'ring.")
        e is FirebaseNetworkException -> tr("Internetga ulanib bo'lmadi. Tarmoqni tekshiring.")
        e is FirebaseAuthException -> when (e.errorCode) {
            "ERROR_INVALID_EMAIL" -> tr("Email manzili noto'g'ri yozilgan.")
            "ERROR_WRONG_PASSWORD",
            "ERROR_INVALID_CREDENTIAL",
            "ERROR_USER_NOT_FOUND" -> tr("Email yoki parol noto'g'ri.")
            "ERROR_EMAIL_ALREADY_IN_USE" -> tr("Bu email bilan hisob allaqachon mavjud. Kirish bo'limidan foydalaning.")
            "ERROR_WEAK_PASSWORD" -> tr("Parol juda oddiy. Kamida 6 ta belgi kiriting.")
            "ERROR_USER_DISABLED" -> tr("Bu hisob bloklangan.")
            "ERROR_USER_MISMATCH" -> tr("Boshqa hisob tanlandi. Joriy hisobingizni tanlang.")
            "ERROR_REQUIRES_RECENT_LOGIN" -> tr("Xavfsizlik uchun hisobdan chiqib, qayta kiring va yana urinib ko'ring.")
            "ERROR_TOO_MANY_REQUESTS" -> tr("Juda ko'p urinish. Birozdan so'ng qayta urinib ko'ring.")
            "ERROR_OPERATION_NOT_ALLOWED" -> tr("Bu kirish usuli Firebase'da yoqilmagan.")
            else -> tr("Xatolik yuz berdi (%1\$s).", e.errorCode)
        }
        else -> tr("Xatolik yuz berdi. Qayta urinib ko'ring.")
    }
}
