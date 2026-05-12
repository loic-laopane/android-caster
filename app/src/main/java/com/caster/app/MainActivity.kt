package com.caster.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.*
import android.util.TypedValue
import android.view.*
import android.widget.*
import com.caster.app.adapter.DeviceAdapter
import com.caster.app.cast.ChromecastCaster
import com.caster.app.cast.DlnaCaster
import com.caster.app.discovery.DeviceDiscovery
import com.caster.app.model.CastDevice
import com.caster.app.model.DeviceType
import com.caster.app.screen.ScreenMirrorService

class MainActivity : Activity() {

    // Tab indices
    private val TAB_DEVICES = 0
    private val TAB_MEDIA = 1
    private val TAB_SCREEN = 2

    // Views
    private lateinit var toolbar: Toolbar
    private lateinit var statusBar: TextView
    private lateinit var tabDevices: TextView
    private lateinit var tabMedia: TextView
    private lateinit var tabScreen: TextView
    private lateinit var panelDevices: View
    private lateinit var panelMedia: View
    private lateinit var panelScreen: View
    private lateinit var deviceList: ListView
    private lateinit var emptyDevicesText: TextView
    private lateinit var scanButton: Button
    private lateinit var mediaUrlField: EditText
    private lateinit var mimeSpinner: Spinner
    private lateinit var selectedDeviceText: TextView
    private lateinit var castButton: Button
    private lateinit var stopCastButton: Button
    private lateinit var mirrorButton: Button
    private lateinit var stopMirrorButton: Button
    private lateinit var mirrorStatusText: TextView

    // Cast components
    private val dlnaCaster = DlnaCaster()
    private val chromeCaster = ChromecastCaster()
    private val deviceAdapter by lazy { DeviceAdapter(this) }
    private var discovery: DeviceDiscovery? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Screen mirror
    private var mirrorService: ScreenMirrorService? = null
    private val SCREEN_CAPTURE_REQUEST = 1001
    private val mirrorConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            mirrorService = (binder as ScreenMirrorService.LocalBinder).getService()
            updateMirrorUI()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            mirrorService = null
            updateMirrorUI()
        }
    }

    private var currentTab = TAB_DEVICES

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        acquireMulticastLock()
        setContentView(buildUI())
        showTab(TAB_DEVICES)
        startDiscovery()
    }

    override fun onDestroy() {
        discovery?.stop()
        multicastLock?.release()
        chromeCaster.disconnect()
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == SCREEN_CAPTURE_REQUEST && resultCode == RESULT_OK && data != null) {
            val intent = Intent(this, ScreenMirrorService::class.java).apply {
                putExtra(ScreenMirrorService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenMirrorService.EXTRA_DATA, data)
            }
            startService(intent)
            bindService(Intent(this, ScreenMirrorService::class.java), mirrorConnection, 0)
        }
    }

    private fun buildUI(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // Toolbar
        toolbar = Toolbar(this).apply {
            setBackgroundColor(0xFF1976D2.toInt())
            setTitleTextColor(Color.WHITE)
            title = "Android Caster"
            elevation = 4f
        }
        root.addView(toolbar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(56)))

        // Status bar
        statusBar = TextView(this).apply {
            text = "Recherche de périphériques..."
            setBackgroundColor(0xFF0D47A1.toInt())
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(dpToPx(12), dpToPx(4), dpToPx(12), dpToPx(4))
        }
        root.addView(statusBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // Content area
        val content = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        panelDevices = buildDevicesPanel()
        panelMedia = buildMediaPanel()
        panelScreen = buildScreenPanel()
        content.addView(panelDevices)
        content.addView(panelMedia)
        content.addView(panelScreen)
        root.addView(content)

        // Bottom tab bar
        val tabBar = buildTabBar()
        root.addView(tabBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(56)))

        return root
    }

    private fun buildTabBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
            elevation = 8f
        }

        tabDevices = buildTab("Périphériques", android.R.drawable.ic_menu_search) {
            showTab(TAB_DEVICES)
        }
        tabMedia = buildTab("Média", android.R.drawable.ic_media_play) {
            showTab(TAB_MEDIA)
        }
        tabScreen = buildTab("Écran", android.R.drawable.ic_menu_slideshow) {
            showTab(TAB_SCREEN)
        }

        bar.addView(tabDevices, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        bar.addView(tabMedia, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        bar.addView(tabScreen, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        return bar
    }

    private fun buildTab(label: String, iconRes: Int, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 12f
            setCompoundDrawablesWithIntrinsicBounds(0, iconRes, 0, 0)
            compoundDrawablePadding = dpToPx(2)
            setOnClickListener { onClick() }
            setTextColor(0xFF757575.toInt())
            setPadding(0, dpToPx(8), 0, dpToPx(8))
        }
    }

    private fun buildDevicesPanel(): LinearLayout {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }

        scanButton = Button(this).apply {
            text = "Rechercher des périphériques"
            setOnClickListener { startDiscovery() }
            setBackgroundColor(0xFF1976D2.toInt())
            setTextColor(Color.WHITE)
        }
        layout.addView(scanButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(8))
        })

        emptyDevicesText = TextView(this).apply {
            text = "Aucun périphérique trouvé.\nAssurez-vous d'être sur le même réseau Wi-Fi."
            gravity = Gravity.CENTER
            setTextColor(Color.GRAY)
            textSize = 14f
            setPadding(dpToPx(24), dpToPx(40), dpToPx(24), dpToPx(40))
            visibility = View.VISIBLE
        }
        layout.addView(emptyDevicesText)

        deviceList = ListView(this).apply {
            adapter = deviceAdapter
            dividerHeight = 1
            setOnItemClickListener { _, _, position, _ ->
                val device = deviceAdapter.getItem(position)
                deviceAdapter.setSelected(device.id)
                updateSelectedDeviceDisplay()
                showToast("${device.name} sélectionné")
            }
            visibility = View.GONE
        }
        layout.addView(deviceList, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val infoText = TextView(this).apply {
            text = "Compatible : Chromecast · Smart TV (DLNA/UPnP)"
            textSize = 11f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
        }
        layout.addView(infoText)

        return layout
    }

    private fun buildMediaPanel(): LinearLayout {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))
        }

        val titleText = TextView(this).apply {
            text = "Diffuser un média"
            textSize = 20f
            setTextColor(0xFF1976D2.toInt())
            setPadding(0, 0, 0, dpToPx(8))
        }
        layout.addView(titleText)

        val urlLabel = TextView(this).apply {
            text = "URL du contenu (MP4, HLS, DASH…)"
            textSize = 13f
            setTextColor(Color.DKGRAY)
        }
        layout.addView(urlLabel)

        mediaUrlField = EditText(this).apply {
            hint = "https://exemple.com/video.mp4"
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI or
                    android.text.InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        layout.addView(mediaUrlField, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dpToPx(12)
        })

        val mimeLabel = TextView(this).apply {
            text = "Type de contenu"
            textSize = 13f
            setTextColor(Color.DKGRAY)
        }
        layout.addView(mimeLabel)

        mimeSpinner = Spinner(this)
        val mimeTypes = arrayOf("video/mp4", "video/x-matroska", "video/webm",
            "application/x-mpegURL", "audio/mpeg", "image/jpeg")
        mimeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, mimeTypes).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        layout.addView(mimeSpinner, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dpToPx(12)
        })

        selectedDeviceText = TextView(this).apply {
            text = "Aucun périphérique sélectionné"
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, dpToPx(4), 0, dpToPx(12))
        }
        layout.addView(selectedDeviceText)

        castButton = Button(this).apply {
            text = "Diffuser"
            setBackgroundColor(0xFF1976D2.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener { startCasting() }
        }
        layout.addView(castButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dpToPx(8)
        })

        stopCastButton = Button(this).apply {
            text = "Arrêter la diffusion"
            setBackgroundColor(0xFFD32F2F.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener { stopCasting() }
            visibility = View.GONE
        }
        layout.addView(stopCastButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dpToPx(16)
        })

        val noteTitle = TextView(this).apply {
            text = "YouTube / Netflix / Prime Video"
            textSize = 14f
            setTextColor(0xFF1976D2.toInt())
            setPadding(0, dpToPx(8), 0, dpToPx(4))
        }
        layout.addView(noteTitle)

        val noteText = TextView(this).apply {
            text = "Pour ces services, utilisez le bouton Cast intégré dans leurs applications officielles, ou utilisez la diffusion d'écran (onglet Écran) pour tout contenu."
            textSize = 12f
            setTextColor(Color.DKGRAY)
        }
        layout.addView(noteText)

        return layout
    }

    private fun buildScreenPanel(): LinearLayout {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))
        }

        val titleText = TextView(this).apply {
            text = "Diffusion d'écran"
            textSize = 20f
            setTextColor(0xFF1976D2.toInt())
            setPadding(0, 0, 0, dpToPx(8))
        }
        layout.addView(titleText)

        val descText = TextView(this).apply {
            text = "Partagez l'intégralité de votre écran vers un téléviseur compatible (Miracast, Chromecast avec Google Home, ou DLNA)."
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, 0, 0, dpToPx(16))
        }
        layout.addView(descText)

        mirrorStatusText = TextView(this).apply {
            text = "État : Inactif"
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, 0, 0, dpToPx(16))
        }
        layout.addView(mirrorStatusText)

        mirrorButton = Button(this).apply {
            text = "Démarrer la diffusion d'écran"
            setBackgroundColor(0xFF388E3C.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener { requestScreenCapture() }
        }
        layout.addView(mirrorButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dpToPx(8)
        })

        stopMirrorButton = Button(this).apply {
            text = "Arrêter la diffusion d'écran"
            setBackgroundColor(0xFFD32F2F.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener { stopMirroring() }
            visibility = View.GONE
        }
        layout.addView(stopMirrorButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dpToPx(16)
        })

        val divider = View(this).apply {
            setBackgroundColor(0xFFE0E0E0.toInt())
        }
        layout.addView(divider, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { bottomMargin = dpToPx(16) })

        val miracastTitle = TextView(this).apply {
            text = "Diffusion sans fil système"
            textSize = 14f
            setTextColor(0xFF1976D2.toInt())
            setPadding(0, 0, 0, dpToPx(4))
        }
        layout.addView(miracastTitle)

        val miracastButton = Button(this).apply {
            text = "Ouvrir les paramètres d'affichage"
            setBackgroundColor(0xFF0288D1.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener { openCastSettings() }
        }
        layout.addView(miracastButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        return layout
    }

    private fun showTab(tab: Int) {
        currentTab = tab
        panelDevices.visibility = if (tab == TAB_DEVICES) View.VISIBLE else View.GONE
        panelMedia.visibility = if (tab == TAB_MEDIA) View.VISIBLE else View.GONE
        panelScreen.visibility = if (tab == TAB_SCREEN) View.VISIBLE else View.GONE

        val activeColor = 0xFF1976D2.toInt()
        val inactiveColor = 0xFF757575.toInt()
        tabDevices.setTextColor(if (tab == TAB_DEVICES) activeColor else inactiveColor)
        tabMedia.setTextColor(if (tab == TAB_MEDIA) activeColor else inactiveColor)
        tabScreen.setTextColor(if (tab == TAB_SCREEN) activeColor else inactiveColor)
    }

    private fun startDiscovery() {
        setStatus("Recherche en cours...")
        discovery?.stop()

        discovery = DeviceDiscovery(object : DeviceDiscovery.DiscoveryListener {
            override fun onDeviceFound(device: CastDevice) {
                mainHandler.post {
                    deviceAdapter.addDevice(device)
                    updateDeviceListVisibility()
                    setStatus("${deviceAdapter.count} périphérique(s) trouvé(s)")
                }
            }
            override fun onDeviceLost(deviceId: String) {
                mainHandler.post {
                    deviceAdapter.removeDevice(deviceId)
                    updateDeviceListVisibility()
                }
            }
            override fun onDiscoveryError(message: String) {
                mainHandler.post { setStatus("Erreur: $message") }
            }
        })
        discovery?.start()

        mainHandler.postDelayed({
            if (deviceAdapter.count == 0) {
                setStatus("Aucun périphérique trouvé. Vérifiez votre réseau Wi-Fi.")
            }
        }, 8000)
    }

    private fun updateDeviceListVisibility() {
        val hasDevices = deviceAdapter.count > 0
        deviceList.visibility = if (hasDevices) View.VISIBLE else View.GONE
        emptyDevicesText.visibility = if (hasDevices) View.GONE else View.VISIBLE
    }

    private fun updateSelectedDeviceDisplay() {
        val d = deviceAdapter.getSelectedDevice()
        selectedDeviceText.text = if (d != null) "Périphérique : ${d.displayName}" else "Aucun périphérique sélectionné"
    }

    private fun startCasting() {
        val device = deviceAdapter.getSelectedDevice()
        if (device == null) {
            showTab(TAB_DEVICES)
            showToast("Sélectionnez d'abord un périphérique")
            return
        }
        val url = mediaUrlField.text.toString().trim()
        if (url.isEmpty()) {
            showToast("Entrez une URL valide")
            return
        }
        val mime = mimeSpinner.selectedItem.toString()
        setStatus("Diffusion vers ${device.name}…")
        castButton.isEnabled = false

        when (device.type) {
            DeviceType.DLNA -> {
                dlnaCaster.castUrl(device, url, mime, {
                    mainHandler.post {
                        castButton.isEnabled = true
                        stopCastButton.visibility = View.VISIBLE
                        setStatus("Diffusion en cours sur ${device.name}")
                    }
                }, { err ->
                    mainHandler.post {
                        castButton.isEnabled = true
                        showAlert("Erreur", err)
                        setStatus("Erreur de diffusion")
                    }
                })
            }
            DeviceType.CHROMECAST -> {
                chromeCaster.connect(device, {
                    // All on background thread — no Thread.sleep on main thread
                    chromeCaster.launchApp()
                    Thread.sleep(1000)
                    chromeCaster.castMedia(url, mime, url.substringAfterLast('/'))
                    mainHandler.post {
                        castButton.isEnabled = true
                        stopCastButton.visibility = View.VISIBLE
                        setStatus("Diffusion Chromecast vers ${device.name}")
                    }
                }, { err ->
                    mainHandler.post {
                        castButton.isEnabled = true
                        showAlert("Erreur Chromecast", err)
                        setStatus("Erreur de connexion")
                    }
                })
            }
            else -> {
                castButton.isEnabled = true
                showToast("Type de périphérique non supporté")
            }
        }
    }

    private fun stopCasting() {
        val device = deviceAdapter.getSelectedDevice()
        if (device != null && device.type == DeviceType.DLNA) {
            dlnaCaster.stop(device) {
                mainHandler.post {
                    stopCastButton.visibility = View.GONE
                    setStatus("Diffusion arrêtée")
                }
            }
        } else {
            chromeCaster.disconnect()
            stopCastButton.visibility = View.GONE
            setStatus("Diffusion arrêtée")
        }
    }

    private fun requestScreenCapture() {
        val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(pm.createScreenCaptureIntent(), SCREEN_CAPTURE_REQUEST)
    }

    private fun stopMirroring() {
        mirrorService?.stopMirroring()
        updateMirrorUI()
    }

    private fun updateMirrorUI() {
        val running = mirrorService?.isMirroring == true
        mirrorButton.visibility = if (running) View.GONE else View.VISIBLE
        stopMirrorButton.visibility = if (running) View.VISIBLE else View.GONE
        mirrorStatusText.text = if (running) "État : Diffusion active" else "État : Inactif"
        mirrorStatusText.setTextColor(if (running) 0xFF388E3C.toInt() else Color.DKGRAY)
    }

    private fun openCastSettings() {
        try {
            startActivity(Intent("android.settings.WIFI_DISPLAY_SETTINGS"))
        } catch (e: Exception) {
            try {
                startActivity(Intent("android.settings.CAST_SETTINGS"))
            } catch (e2: Exception) {
                showToast("Paramètres d'affichage non disponibles sur cet appareil")
            }
        }
    }

    private fun acquireMulticastLock() {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wm.createMulticastLock("caster_mdns").apply {
            setReferenceCounted(true)
            acquire()
        }
    }

    private fun setStatus(text: String) {
        statusBar.text = text
    }

    private fun showToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun showAlert(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }
}
