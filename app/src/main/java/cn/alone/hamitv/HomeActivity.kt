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
 * The TV's home screen, and therefore the boot hook.
 *
 * The vendor security centre marks every app that is not on its allowlist as
 * stopped while the package manager scans at boot, so `BOOT_COMPLETED` never
 * reaches us. The home activity is started no matter what that flag says, so
 * the agent comes up from here; the screen is then handed back to the launcher
 * the owner actually uses, which keeps the TV looking exactly as before.
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

    /** The launchers this TV is known to ship with first, then anything else. */
    private fun launcherCandidates(): List<ComponentName> {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val others = packageManager.queryIntentActivities(home, 0)
            .map { it.activityInfo }
            .filter { it.packageName != packageName }
        val preferred = others.firstOrNull { it.packageName in PREFERRED }
        return (listOfNotNull(preferred) + others)
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

        /** Projectivy Launcher, then PatchWall: the two this set has been run with. */
        private val PREFERRED = listOf("com.spocky.projengmenu", "com.mitv.tvhome")
    }
}
