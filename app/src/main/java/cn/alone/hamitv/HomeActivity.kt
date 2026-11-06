package cn.alone.hamitv

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/**
 * The TV's home screen, and therefore the only reliable boot hook on this box.
 *
 * The vendor security centre marks every app that is not in its whitelist as
 * stopped while the package manager scans at boot, so `BOOT_COMPLETED` is never
 * delivered to us and the agent stays down until someone opens it by hand. The
 * home activity is started by the system no matter what that flag says - the
 * stock launcher is stopped the same way and still comes up - so we take the
 * home slot, start the agent, and hand the screen straight back to the TV's own
 * launcher.
 */
class HomeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { AgentService.start(this) }
            .onFailure { Log.e(TAG, "cannot start the agent", it) }
        if (launchTvLauncher()) {
            finish()
            return
        }
        showAppList()
    }

    /** @return false when there is no other launcher to hand the screen to. */
    private fun launchTvLauncher(): Boolean {
        for (target in launcherCandidates()) {
            val intent = Intent(Intent.ACTION_MAIN)
                .setComponent(target)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            if (runCatching { startActivity(intent) }.isSuccess) return true
        }
        return false
    }

    /** The default home first, then anything else that answers to HOME. */
    private fun launcherCandidates(): List<ComponentName> {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = packageManager.resolveActivity(home, 0)?.activityInfo
        val all = packageManager.queryIntentActivities(home, 0).map { it.activityInfo }
        return (listOfNotNull(resolved) + all)
            .filter { it.packageName != packageName }
            .map { ComponentName(it.packageName, it.name) }
            .distinct()
    }

    /**
     * Last resort: without a launcher the box would sit on a black screen with
     * no way out, so list every app that can be opened and let the remote pick.
     */
    private fun showAppList() {
        val apps = packageManager
            .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo }
            .distinctBy { it.packageName }
        val labels = apps.map { it.loadLabel(packageManager).toString() }

        val header = TextView(this).apply {
            text = getString(R.string.home_fallback)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(Color.parseColor("#B0BEC5"))
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 24)
        }
        val list = ListView(this).apply {
            adapter = object : ArrayAdapter<String>(this@HomeActivity, android.R.layout.simple_list_item_1, labels) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                    (super.getView(position, convertView, parent) as TextView).apply {
                        setTextColor(Color.WHITE)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                        setPadding(48, 24, 48, 24)
                    }
            }
            setOnItemClickListener { _, _, position, _ ->
                val info = apps[position]
                runCatching {
                    startActivity(
                        Intent(Intent.ACTION_MAIN)
                            .setComponent(ComponentName(info.packageName, info.name))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#101418"))
            addView(header)
            addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        })
    }

    companion object {
        private const val TAG = "HomeActivity"
    }
}
