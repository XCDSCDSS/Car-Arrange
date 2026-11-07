package com.example.arrange

import android.app.Activity
import android.content.ActivityNotFoundException
import android.graphics.Color
import android.provider.ContactsContract
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import org.json.JSONObject
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : AppCompatActivity() {

    // 백업 파일 저장 (사용자가 고른 위치에 JSON 파일로 저장 — 별도 권한 필요 없음)
    private var pendingBackup: String? = null

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val json = pendingBackup
        pendingBackup = null
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult   // 사용자가 취소
        var ok = false
        val uri = result.data?.data
        if (uri != null && json != null) {
            try {
                contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                ok = true
            } catch (e: Exception) {
                ok = false
            }
        }
        findViewById<WebView>(R.id.webview).evaluateJavascript("onBackupSaved($ok)", null)
    }

    // 백업 파일 불러오기 (파일 선택 → 내용을 JS로 전달)
    private val importLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult
        val text = try {
            contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        } catch (e: Exception) {
            null
        }
        val js = if (text != null && text.length < 5_000_000) {
            "onBackupImported(${JSONObject.quote(text)})"
        } else {
            "alert('백업 파일을 읽을 수 없어요.')"
        }
        findViewById<WebView>(R.id.webview).evaluateJavascript(js, null)
    }

    // 연락처 선택 결과 처리 (ACTION_PICK은 별도 연락처 권한이 필요 없음)
    private val contactPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val name = cursor.getString(0) ?: ""
                val number = cursor.getString(1) ?: ""
                val js = "onContactPicked(${JSONObject.quote(name)}, ${JSONObject.quote(number)})"
                findViewById<WebView>(R.id.webview).evaluateJavascript(js, null)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. 상태바 아이콘과 배경 제어를 위한 설정
        val window = window
        val decorView = window.decorView

        // Edge-to-Edge 설정을 유지하되 시스템이 자동으로 색상을 바꾸지 못하게 합니다.
        WindowCompat.setDecorFitsSystemWindows(window, true)

        // 2~3. 상태바/내비게이션바 색: 폰의 다크 모드에 맞춰 시작 (이후 화면 모드 설정에 따라 JS가 다시 지정)
        applyBars(isSystemNight())

        // 웹뷰 설정
        val webView: WebView = findViewById(R.id.webview)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
        }

        webView.addJavascriptInterface(WebAppInterface(), "AndroidApp")
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = object : WebChromeClient() {
            // alert(): 기본 제목("file:// 페이지 내용") 대신 직접 만든 제목 사용
            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(dialogTitle(message ?: ""))
                    .setMessage(message)
                    .setPositiveButton("확인") { _, _ -> result?.confirm() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }

            // confirm(): 삭제/초기화 확인창
            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("확인")
                    .setMessage(message)
                    .setPositiveButton("확인") { _, _ -> result?.confirm() }
                    .setNegativeButton("취소") { _, _ -> result?.cancel() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }
        }
        webView.loadUrl("file:///android_asset/index.html")

        // 뒤로가기: 먼저 화면(JS)에 물어봄 → 알림창/선택 창 닫기, 다른 탭이면 문자 작성 탭으로
        // JS가 더 닫을 게 없다고 하면(false) 원래대로 앱 종료
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript("(typeof handleBack === 'function') && handleBack()") { handled ->
                    if (handled != "true") {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })
    }

    // 화면 회전·폰 다크 모드 변경 시 화면을 새로 만들지 않음 (매니페스트 configChanges)
    // → 작성 중이던 입력이 지워지지 않음. 다크 모드가 바뀌면 JS가 화면 모드를 다시 적용
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        findViewById<WebView>(R.id.webview)?.evaluateJavascript(
            "if (typeof applyTheme === 'function') applyTheme();", null
        )
    }

    private fun isSystemNight(): Boolean =
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    // 상태바/내비게이션바 색을 앱 배경과 맞춤 (라이트: #f5f5f7 + 검은 아이콘 / 다크: #000000 + 흰 아이콘)
    private fun applyBars(dark: Boolean) {
        val color = Color.parseColor(if (dark) "#000000" else "#f5f5f7")
        // 안드로이드 15 이상에서는 statusBarColor가 무시되므로, 상태바 뒤에 비치는 배경 자체를 칠함
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(color))
        window.decorView.setBackgroundColor(color)
        findViewById<android.view.View>(android.R.id.content)?.setBackgroundColor(color)
        findViewById<WebView>(R.id.webview)?.setBackgroundColor(color)
        window.statusBarColor = color
        window.navigationBarColor = color
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
    }

    // 알림창 메시지 내용에 따라 제목을 정함
    private fun dialogTitle(message: String): String = when {
        message.contains("복사되었습니다") -> "복사 성공"
        message.contains("복사에 실패") -> "복사 실패"
        else -> "알림"
    }

    inner class WebAppInterface {
        // 폰의 다크 모드 여부 (설정 > 화면 모드 > '시스템'에서 사용)
        @JavascriptInterface
        fun isSystemDark(): Boolean = isSystemNight()

        // 화면 모드에 맞춰 상태바/내비게이션바 색 변경 (JS에서 호출)
        @JavascriptInterface
        fun setStatusBarDark(dark: Boolean) {
            runOnUiThread { applyBars(dark) }
        }

        // 폰 연락처 앱 열기 → 선택한 이름/전화번호를 JS(onContactPicked)로 전달
        @JavascriptInterface
        fun pickContact() {
            runOnUiThread {
                val intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
                try {
                    contactPicker.launch(intent)
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(this@MainActivity, "연락처 앱을 열 수 없습니다.", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // 백업 파일 저장: 파일 저장 화면을 띄움
        @JavascriptInterface
        fun exportBackup(json: String, fileName: String) {
            runOnUiThread {
                pendingBackup = json
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/json"
                    putExtra(Intent.EXTRA_TITLE, fileName)
                }
                try {
                    exportLauncher.launch(intent)
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(this@MainActivity, "파일 저장 화면을 열 수 없습니다.", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // 백업 파일 불러오기: 파일 선택 화면을 띄움
        @JavascriptInterface
        fun importBackup() {
            runOnUiThread {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                try {
                    importLauncher.launch(intent)
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(this@MainActivity, "파일 선택 화면을 열 수 없습니다.", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun sendSMS(phone: String, message: String) {
            runOnUiThread {
                val intent = Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("smsto:${Uri.encode(phone)}")
                    putExtra("sms_body", message)
                }
                try {
                    startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(this@MainActivity, "문자 앱을 열 수 없습니다.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}