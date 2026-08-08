package com.jarvis.launcher

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val requiredPermissions = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CALL_PHONE
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        buildLanguageRow()
        checkPermissionsAndStart()
        loadInstalledApps()

        findViewById<ImageView>(R.id.micButton).setOnClickListener {
            startService(Intent(this, ListenService::class.java).apply {
                action = ListenService.ACTION_MANUAL_TRIGGER
            })
        }
    }

    private fun buildLanguageRow() {
        val row = findViewById<LinearLayout>(R.id.langRow)
        val currentCode = LanguageManager.getSelected(this).code

        LanguageManager.LANGUAGES.forEach { profile ->
            val chip = TextView(this).apply {
                text = profile.displayName
                val selected = profile.code == currentCode
                setTextColor(if (selected) Color.parseColor("#0B1418") else Color.parseColor("#4FD8E8"))

                val bg = GradientDrawable()
                bg.cornerRadius = 40f
                if (selected) {
                    bg.setColor(Color.parseColor("#4FD8E8"))
                } else {
                    bg.setColor(Color.TRANSPARENT)
                    bg.setStroke(2, Color.parseColor("#4FD8E8"))
                }
                background = bg

                gravity = Gravity.CENTER
                textSize = 12f
                setPadding(28, 14, 28, 14)
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params.marginEnd = 10
                layoutParams = params

                setOnClickListener {
                    LanguageManager.setSelected(this@MainActivity, profile.code)
                    Toast.makeText(this@MainActivity, "${profile.displayName} saylandy", Toast.LENGTH_SHORT).show()
                    restartListenServiceWithNewLanguage()
                    recreate()
                }
            }
            row.addView(chip)
        }
    }

    private fun restartListenServiceWithNewLanguage() {
        stopService(Intent(this, ListenService::class.java))
        startListenService()
    }

    private fun checkPermissionsAndStart() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
        } else {
            startListenService()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startListenService()
        } else {
            Toast.makeText(this, "Dinlemek uchin mikrofon rugsady gerek", Toast.LENGTH_LONG).show()
        }
    }

    private fun startListenService() {
        val intent = Intent(this, ListenService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun loadInstalledApps() {
        val grid = findViewById<GridLayout>(R.id.appGrid)
        val pm = packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val apps = pm.queryIntentActivities(mainIntent, 0)
            .sortedBy { it.loadLabel(pm).toString() }

        for (appInfo in apps) {
            val itemView = layoutInflater.inflate(R.layout.item_app, grid, false)
            val icon = itemView.findViewById<ImageView>(R.id.appIcon)
            val label = itemView.findViewById<TextView>(R.id.appLabel)

            icon.setImageDrawable(appInfo.loadIcon(pm))
            label.text = appInfo.loadLabel(pm)

            itemView.setOnClickListener {
                val launchIntent = pm.getLaunchIntentForPackage(appInfo.activityInfo.packageName)
                if (launchIntent != null) startActivity(launchIntent)
            }

            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = LinearLayout.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            }
            itemView.layoutParams = params
            grid.addView(itemView)
        }
    }
}
