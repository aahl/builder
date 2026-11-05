package ng.anlo.hamitv

import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import ng.anlo.hamitv.net.localIpv4

/**
 * Placeholder status screen. The TV is only used to display where the
 * configuration UI lives; everything else is configured from a browser.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val ip = localIpv4()

        val title = TextView(this).apply {
            text = getString(R.string.app_name)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val body = TextView(this).apply {
            text = buildString {
                append("配置台   http://").append(ip).append(":8122\n")
                append("ESPHome  ").append(ip).append(":6053\n")
                append("状态     未启动 (M0)")
            }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTextColor(Color.parseColor("#B0BEC5"))
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 24, 0, 0)
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#101418"))
            addView(title)
            addView(body)
        })
    }
}
