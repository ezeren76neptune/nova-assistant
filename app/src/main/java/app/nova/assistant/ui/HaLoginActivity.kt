package app.nova.assistant.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import app.nova.assistant.app
import app.nova.assistant.home.HomeAssistant
import kotlinx.coroutines.launch

/** Home Assistant's own login page in a WebView; we catch the OAuth redirect and keep the tokens. */
class HaLoginActivity : ComponentActivity() {
    private var handled = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.toString().startsWith(HomeAssistant.REDIRECT_URI)) { finishWith(request.url); return true }
                return false
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (url.startsWith(HomeAssistant.REDIRECT_URI)) { view.stopLoading(); finishWith(Uri.parse(url)) }
            }
        }
        setContentView(web)
        web.loadUrl(app.home.authorizeUrl())
    }

    private fun finishWith(uri: Uri) {
        if (handled) return
        handled = true
        val code = uri.getQueryParameter("code")
        if (code == null) { Toast.makeText(this, "Sign-in was cancelled", Toast.LENGTH_LONG).show(); finish(); return }
        lifecycleScope.launch {
            val ok = runCatching { app.home.exchangeCode(code) }.getOrDefault(false)
            Toast.makeText(this@HaLoginActivity, if (ok) "Signed in to Home Assistant" else "Sign-in failed — check the address", Toast.LENGTH_LONG).show()
            finish()
        }
    }
}
