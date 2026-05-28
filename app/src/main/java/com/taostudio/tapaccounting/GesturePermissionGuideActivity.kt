package com.taostudio.tapaccounting

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.taostudio.tapaccounting.ui.dialog.OverlayDialogs

class GesturePermissionGuideActivity : AppCompatActivity() {
    private companion object {
        private const val REQUEST_POST_NOTIFICATIONS = 7301
    }

    private lateinit var summaryText: TextView
    private lateinit var listContainer: LinearLayout

    private data class StatusStyle(
        val label: String,
        val textColor: Int,
        val bgColor: Int,
        val verified: Boolean
    )

    private data class GuideItem(
        val title: String,
        val desc: String,
        val status: StatusStyle,
        val actionText: String,
        val required: Boolean,
        val autoDetectable: Boolean,
        val onAction: () -> Unit
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContentView())
    }

    override fun onResume() {
        super.onResume()
        renderGuideItems()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_POST_NOTIFICATIONS) {
            renderGuideItems()
        }
    }

    private fun buildContentView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F7FA"))
        }

        val header = FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
            elevation = dp(4).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(56)
            )
        }
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_chevron_left)
            setColorFilter(Color.parseColor("#333333"))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = selectableBorderless()
            setOnClickListener { finish() }
        }, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER_VERTICAL or Gravity.START).apply {
            marginStart = dp(8)
        })
        header.addView(TextView(this).apply {
            text = "\u5feb\u6377\u8bb0\u8d26\u51c6\u5907"
            setTextColor(Color.parseColor("#333333"))
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ))
        root.addView(header)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }

        content.addView(CardView(this).apply {
            radius = dp(16).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(Color.parseColor("#FFFFFF"))
            useCompatPadding = false
            addView(LinearLayout(this@GesturePermissionGuideActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                addView(TextView(this@GesturePermissionGuideActivity).apply {
                    text = "\u628a\u5feb\u6377\u52a8\u4f5c\u51c6\u5907\u597d"
                    setTextColor(Color.parseColor("#1A2744"))
                    textSize = 16f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
                summaryText = TextView(this@GesturePermissionGuideActivity).apply {
                    setTextColor(Color.parseColor("#6D7785"))
                    textSize = 12f
                    setLineSpacing(dp(3).toFloat(), 1f)
                    setPadding(0, dp(6), 0, 0)
                }
                addView(summaryText)
            })
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(12)
        })

        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(listContainer)
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))
        return root
    }

    private fun renderGuideItems() {
        val items = buildGuideItems()
        val verifiedRequired = items.count { it.required && it.autoDetectable && it.status.verified }
        val requiredCount = items.count { it.required && it.autoDetectable }
        summaryText.text =
            "\u5df2\u51c6\u5907 $verifiedRequired/$requiredCount \u4e2a\u5173\u952e\u9879\u3002\u4e0d\u540c\u624b\u673a\u5165\u53e3\u540d\u5b57\u4e0d\u4e00\u6837\uff0c\u8fd9\u91cc\u53ea\u4fdd\u7559\u771f\u6b63\u9700\u8981\u4f60\u5904\u7406\u7684\u51e0\u6b65\u3002"

        listContainer.removeAllViews()
        items.forEachIndexed { index, item ->
            listContainer.addView(createGuideRow(item), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = if (index == items.lastIndex) 0 else dp(10)
            })
        }
    }

    private fun buildGuideItems(): List<GuideItem> {
        val overlayReady = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
        val batteryReady = isIgnoringBatteryOptimizations()
        val backgroundRestricted = isBackgroundRestricted()
        val notificationReady = isNotificationPermissionReady()
        val backgroundReady = batteryReady && !backgroundRestricted

        return listOf(
            GuideItem(
                title = "\u60ac\u6d6e\u7a97\u6743\u9650",
                desc = "\u8ba9\u7ffb\u8f6c\u6216\u6572\u51fb\u540e\u80fd\u76f4\u63a5\u5f39\u51fa\u8bb0\u8d26\u9762\u677f\u3002",
                status = if (overlayReady) ready("\u5df2\u51c6\u5907") else actionNeeded("\u5f85\u5f00\u542f"),
                actionText = if (overlayReady) "\u67e5\u770b\u8bbe\u7f6e" else "\u53bb\u5f00\u542f",
                required = true,
                autoDetectable = true,
                onAction = ::openOverlaySettings
            ),
            GuideItem(
                title = "\u540e\u53f0\u8fd0\u884c",
                desc = "\u5141\u8bb8\u5e94\u7528\u5728\u540e\u53f0\u4e0d\u53d7\u9650\u5236\u5730\u8fd0\u884c\uff0c\u907f\u514d\u624b\u52bf\u670d\u52a1\u88ab\u7cfb\u7edf\u4f11\u7720\u4e2d\u65ad\u3002",
                status = when {
                    backgroundReady -> ready("\u5df2\u51c6\u5907")
                    backgroundRestricted -> actionNeeded("\u53d7\u9650")
                    else -> actionNeeded("\u5f85\u8bbe\u7f6e")
                },
                actionText = if (backgroundReady) "\u67e5\u770b\u8bbe\u7f6e" else "\u53bb\u8bbe\u7f6e",
                required = true,
                autoDetectable = true,
                onAction = ::openAppDetailsForBattery
            ),
            GuideItem(
                title = "\u81ea\u542f\u52a8\u4e0e\u540e\u53f0\u9501\u5b9a",
                desc = "\u91cd\u542f\u540e\u81ea\u52a8\u6062\u590d\u624b\u52bf\u670d\u52a1\uff1b\u5728\u6700\u8fd1\u4efb\u52a1\u91cc\u9501\u5b9a\u5e94\u7528\uff0c\u53ef\u51cf\u5c11\u88ab\u7cfb\u7edf\u6e05\u7406\u3002",
                status = manual("\u5efa\u8bae\u786e\u8ba4"),
                actionText = "\u67e5\u770b\u6b65\u9aa4",
                required = true,
                autoDetectable = false,
                onAction = ::showStartupAndLockGuide
            ),
            GuideItem(
                title = "\u901a\u77e5\u6743\u9650\uff08\u53ef\u9009\uff09",
                desc = "\u7528\u4e8e\u663e\u793a\u670d\u52a1\u72b6\u6001\u548c\u5f55\u97f3\u72b6\u6001\u3002\u4e0d\u5f71\u54cd\u57fa\u7840\u8bb0\u8d26\u3002",
                status = if (notificationReady) ready("\u5df2\u5f00\u542f") else optional("\u53ef\u9009"),
                actionText = if (notificationReady) "\u67e5\u770b\u8bbe\u7f6e" else "\u53bb\u5f00\u542f",
                required = false,
                autoDetectable = true,
                onAction = ::requestNotificationPermissionOrOpenSettings
            )
        )
    }

    private fun createGuideRow(item: GuideItem): View {
        return CardView(this).apply {
            radius = dp(16).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(Color.WHITE)
            useCompatPadding = false
            addView(LinearLayout(this@GesturePermissionGuideActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))

                val titleRow = LinearLayout(this@GesturePermissionGuideActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                titleRow.addView(TextView(this@GesturePermissionGuideActivity).apply {
                    text = item.title
                    setTextColor(Color.parseColor("#1A2744"))
                    textSize = 15f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                titleRow.addView(TextView(this@GesturePermissionGuideActivity).apply {
                    text = item.status.label
                    setTextColor(item.status.textColor)
                    textSize = 11f
                    gravity = Gravity.CENTER
                    setPadding(dp(9), dp(4), dp(9), dp(4))
                    background = roundedBg(item.status.bgColor, dp(10).toFloat())
                })
                addView(titleRow)

                addView(TextView(this@GesturePermissionGuideActivity).apply {
                    text = item.desc
                    setTextColor(Color.parseColor("#7B8794"))
                    textSize = 12f
                    setLineSpacing(dp(3).toFloat(), 1f)
                    setPadding(0, dp(6), 0, dp(10))
                })

                addView(MaterialButton(this@GesturePermissionGuideActivity).apply {
                    text = item.actionText
                    textSize = 13f
                    setTextColor(Color.WHITE)
                    cornerRadius = dp(10)
                    backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#5C6BC0"))
                    setOnClickListener { item.onAction() }
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(40)
                ))
            })
        }
    }

    private fun ready(label: String) =
        StatusStyle(label, Color.parseColor("#1B7F4B"), Color.parseColor("#E8F7EF"), true)

    private fun actionNeeded(label: String) =
        StatusStyle(label, Color.parseColor("#C24132"), Color.parseColor("#FDECE9"), false)

    private fun manual(label: String) =
        StatusStyle(label, Color.parseColor("#9A6500"), Color.parseColor("#FFF5D8"), false)

    private fun optional(label: String) =
        StatusStyle(label, Color.parseColor("#607D8B"), Color.parseColor("#EDF4F7"), true)

    private fun isIgnoringBatteryOptimizations(): Boolean {
        return runCatching {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(packageName)
        }.getOrDefault(false)
    }

    private fun isBackgroundRestricted(): Boolean {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
                am.isBackgroundRestricted
            } else {
                false
            }
        }.getOrDefault(false)
    }

    private fun openOverlaySettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            }
        } else {
            appDetailsIntent()
        }
        startSettingsActivity(intent)
    }

    private fun openAppDetailsForBattery() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                runCatching {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:$packageName"))
                    startActivity(intent)
                    return
                }
            }
        }
        startSettingsActivity(appDetailsIntent())
    }

    private fun openAutoStartSettings() {
        val intents = listOf(
            Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
            Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")),
            Intent().setComponent(ComponentName("com.hihonor.systemmanager", "com.hihonor.systemmanager.optimize.process.ProtectActivity")),
            Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
            Intent().setComponent(ComponentName("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity")),
            Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")),
            Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")),
            Intent().setComponent(ComponentName("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity"))
        )
        val started = intents.any { intent ->
            runCatching {
                startActivity(intent)
                true
            }.getOrDefault(false)
        }
        if (!started) {
            Utils.toast(this, "\u672a\u627e\u5230\u4e13\u7528\u5165\u53e3\uff0c\u8bf7\u5728\u5e94\u7528\u8be6\u60c5\u6216\u7cfb\u7edf\u7ba1\u5bb6\u91cc\u5f00\u542f\u81ea\u542f\u52a8")
            startSettingsActivity(appDetailsIntent())
        }
    }

    private fun showStartupAndLockGuide() {
        val dialog = AlertDialog.Builder(this)
            .setTitle("\u81ea\u542f\u52a8\u4e0e\u540e\u53f0\u9501\u5b9a")
            .setMessage(
                "\u81ea\u542f\u52a8\uff1a\u5728\u7cfb\u7edf\u7ba1\u5bb6\u6216\u5e94\u7528\u8be6\u60c5\u91cc\uff0c\u6253\u5f00\u201c\u81ea\u542f\u52a8\u201d\u201c\u540e\u53f0\u542f\u52a8\u201d\u6216\u201c\u5f00\u673a\u542f\u52a8\u201d\u3002\n\n" +
                    "\u540e\u53f0\u9501\u5b9a\uff1a\u6253\u5f00\u6700\u8fd1\u4efb\u52a1\uff0c\u627e\u5230\u6572\u6572\u8bb0\u8d26\uff0c\u957f\u6309\u3001\u4e0b\u62c9\u6216\u70b9\u83dc\u5355\uff0c\u9009\u62e9\u201c\u9501\u5b9a\u201d\u201c\u52a0\u9501\u201d\u6216\u201c\u4fdd\u6301\u540e\u53f0\u201d\u3002\n\n" +
                    "\u5982\u679c\u4f60\u7684\u624b\u673a\u6ca1\u6709\u8fd9\u4e9b\u5165\u53e3\uff0c\u5b8c\u6210\u201c\u540e\u53f0\u8fd0\u884c\u201d\u5373\u53ef\u3002"
            )
            .setPositiveButton("\u6253\u5f00\u81ea\u542f\u52a8\u8bbe\u7f6e") { _, _ -> openAutoStartSettings() }
            .setNegativeButton("\u77e5\u9053\u4e86", null)
            .create()
        OverlayDialogs.showPageCenterDialog(
            dialog = dialog,
            ctx = this,
            widthRatio = 0.88f,
            cancelOnTouchOutside = true,
            useSolidPanelBackground = true
        )
    }

    private fun requestNotificationPermissionOrOpenSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_POST_NOTIFICATIONS
            )
            return
        }
        openNotificationSettings()
    }

    private fun openNotificationSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            }
        } else {
            appDetailsIntent()
        }
        startSettingsActivity(intent)
    }

    private fun isNotificationPermissionReady(): Boolean {
        val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val appNotificationsEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled()
        val channelEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.getNotificationChannel(OverlayService.CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
        } else {
            true
        }
        return permissionGranted && appNotificationsEnabled && channelEnabled
    }

    private fun appDetailsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        }

    private fun startSettingsActivity(intent: Intent) {
        runCatching { startActivity(intent) }
            .onFailure {
                Utils.toast(this, "\u65e0\u6cd5\u6253\u5f00\u7cfb\u7edf\u8bbe\u7f6e\uff0c\u8bf7\u624b\u52a8\u8fdb\u5165\u5e94\u7528\u8be6\u60c5")
                runCatching { startActivity(appDetailsIntent()) }
            }
    }

    private fun selectableBorderless(): android.graphics.drawable.Drawable? {
        val out = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, out, true)
        return ContextCompat.getDrawable(this, out.resourceId)
    }

    private fun roundedBg(color: Int, radius: Float): android.graphics.drawable.Drawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
