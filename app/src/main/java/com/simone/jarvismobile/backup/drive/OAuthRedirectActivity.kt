package com.simone.jarvismobile.backup.drive

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.simone.jarvismobile.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Catches the `com.simone.jarvismobile:/oauth2redirect` the browser opens after
 * the user finishes (or cancels) Google's consent page — see [GoogleAuthManager].
 * Shows nothing: it hands the redirect to [GoogleAuthManager.handleRedirect],
 * brings the existing app task back to the front, and finishes itself.
 */
@AndroidEntryPoint
class OAuthRedirectActivity : ComponentActivity() {

    @Inject lateinit var authManager: GoogleAuthManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        if (uri != null) {
            lifecycleScope.launch { authManager.handleRedirect(uri) }
        }
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }
}
