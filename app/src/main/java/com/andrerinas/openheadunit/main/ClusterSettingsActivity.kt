package com.andrerinas.openheadunit.main

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import com.andrerinas.openheadunit.aap.ClusterLink

/**
 * JLY E60 build: the cluster settings page (CarSettings) on the head unit's own screen, so
 * settings can be changed without a phone. It is the same page a phone browser gets, served
 * by this app on the loopback address (cleartext allowed for 127.0.0.1 only, see
 * res/xml/network_security_config.xml).
 */
class ClusterSettingsActivity : Activity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ClusterLink.start()
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            webViewClient = WebViewClient()      // keep navigation inside this view
            setBackgroundColor(0xff0b0d10.toInt())
            loadUrl("http://127.0.0.1:${ClusterLink.PORT}/settings")
        }
        setContentView(web)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
