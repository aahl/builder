package cn.alone.hamitv

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import cn.alone.hamitv.config.ConfigStore
import cn.alone.hamitv.net.localIpv4
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/**
 * The only screen on the TV: where to configure the agent, and the 4 digit
 * pairing code. Pressing OK on the remote rotates the code.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var store: ConfigStore
    private lateinit var urlView: TextView
    private lateinit var codeView: TextView
    private lateinit var qrView: ImageView

    /** A long press already opened the settings, so the key-up must not rotate. */
    private var longPressHandled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ConfigStore(this)

        val title = TextView(this).apply {
            text = getString(R.string.app_name)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 36f)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        urlView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setTextColor(Color.parseColor("#B0BEC5"))
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 0)
        }
        qrView = ImageView(this).apply {
            setPadding(0, 28, 0, 0)
        }
        codeView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 56f)
            setTextColor(Color.WHITE)
            letterSpacing = 0.25f
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 0)
        }
        val hint = TextView(this).apply {
            text = getString(R.string.pair_hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(Color.parseColor("#607D8B"))
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 0)
        }
        val autostart = TextView(this).apply {
            text = getString(R.string.autostart_hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(Color.parseColor("#455A64"))
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 0)
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#101418"))
            addView(title)
            addView(urlView)
            addView(qrView)
            addView(codeView)
            addView(hint)
            addView(autostart)
        })

        AgentService.start(this)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (isOk(keyCode)) {
            // Track the key so a long press can open the system settings.
            event?.startTracking()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (isOk(keyCode)) {
            if (longPressHandled) {
                longPressHandled = false
            } else {
                store.rotatePairingCode()
                render()
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean {
        if (isOk(keyCode)) {
            longPressHandled = true
            openAppSettings()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    private fun isOk(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER

    /** Where the TV's autostart whitelist lives; the vendor UI is the only way in. */
    private fun openAppSettings() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
            )
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (isOk(keyCode)) {
            // Track the key so a long press can open the system settings.
            event?.startTracking()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (isOk(keyCode)) {
            if (longPressHandled) {
                longPressHandled = false
            } else {
                store.rotatePairingCode()
                render()
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean {
        if (isOk(keyCode)) {
            longPressHandled = true
            openAppSettings()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    private fun isOk(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER

    private fun render() {
        val url = "http://${localIpv4()}:${store.webPort}"
        urlView.text = url
        codeView.text = store.pairingCode
        qrView.setImageBitmap(qrBitmap(url, 360))
    }

    private fun qrBitmap(text: String, size: Int): Bitmap {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            val row = y * size
            for (x in 0 until size) {
                pixels[row + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }
}
