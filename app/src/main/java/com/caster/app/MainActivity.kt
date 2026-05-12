package com.caster.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.*
import android.util.TypedValue
import android.view.*
import android.widget.*
import com.caster.app.adapter.DeviceAdapter
import com.caster.app.cast.AndroidAutoConnector
import com.caster.app.cast.ChromecastCaster
import com.caster.app.cast.DlnaCaster
import com.caster.app.discovery.BluetoothDiscovery
import com.caster.app.discovery.DeviceDiscovery
import com.caster.app.model.CastDevice
import com.caster.app.model.DeviceType
import com.caster.app.screen.ScreenMirrorService

class MainActivity : Activity() {

    private val TAB_DEVICES = 0
    private val TAB_VEHICLE = 1
    private val TAB_MEDIA   = 2
    private val TAB_SCREEN  = 3

    private val REQ_SCREEN_CAPTURE = 1001
    private val REQ_PERMISSIONS    = 1002

    // Views
    private lateinit var toolbar: Toolbar
    private lateinit var statusBar: TextView
    private lateinit var tabs: Array<TextView>
    private lateinit var panels: Array<View>

    // Devices panel
    private lateinit var deviceList: ListView
    private lateinit var emptyDevicesText: TextView

    // Vehicle panel
    private lateinit var vehicleList: ListView
    private lateinit var emptyVehiclesText: TextView
    private lateinit var btScanButton: Button
    private lateinit var wifiScanButton: Button
    private lateinit var vehicleGuideText: TextView
    private lateinit var connectAAButton: Button
    private lateinit var vehicleStatusText: TextView

    // Media panel
    private lateinit var mediaUrlField: EditText
    private lateinit var mimeSpinner: Spinner
    private lateinit var selectedDeviceText: TextView
    private lateinit var castButton: Button
    private lateinit var stopCastButton: Button

    // Screen panel
    private lateinit var mirrorButton: Button
    private lateinit var stopMirrorButton: Button
    private lateinit var mirrorStatusText: TextView

    // Logic
    private val dlnaCaster = DlnaCaster()
    private val chromeCaster = ChromecastCaster()
    private val deviceAdapter by lazy { DeviceAdapter(this) }
    private val vehicleAdapter by lazy { DeviceAdapter(this) }
    private lateinit var androidAutoConnector: AndroidAutoConnector
    private var discovery: DeviceDiscovery? = null
    private var btDiscovery: BluetoothDiscovery? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var selectedVehicle: CastDevice? = null

    // Screen mirror service
    private var mirrorService: ScreenMirrorService? = null
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

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidAutoConnector = AndroidAutoConnector(this)
        acquireMulticastLock()
        val root = buildUI()
        setContentView(root)
        showTab(TAB_DEVICES)
        requestNeededPermissions()
    }

    override fun onDestroy() {
        discovery?.stop()
        btDiscovery?.stop()
        multicastLock?.release()
        chromeCaster.disconnect()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        if (requestCode == REQ_PERMISSIONS) {
            startDiscovery()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_SCREEN_CAPTURE && resultCode == RESULT_OK && data != null) {
            val intent = Intent(this, ScreenMirrorService::class.java).apply {
                putExtra(ScreenMirrorService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenMirrorService.EXTRA_DATA, data)
            }
            startService(intent)
            bindService(Intent(this, ScreenMirrorService::class.java), mirrorConnection, 0)
        }
    }

    // ─── Permission handling ──────────────────────────────────────────────────

    private fun requestNeededPermissions() {
        val needed = mutableListOf<String>()
        val toCheck = listOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.RECORD_AUDIO
        )
        for (perm in toCheck) {
            if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
                needed.add(perm)
            }
        }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), REQ_PERMISSIONS)
        } else {
            startDiscovery()
        }
    }

    // ─── UI construction ─────────────────────────────────────────────────────

    private fun buildUI(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        toolbar = Toolbar(this).apply {
            setBackgroundColor(0xFF1565C0.toInt())
            setTitleTextColor(Color.WHITE)
            title = "LowKick Android Auto Caster"
        }
        root.addView(toolbar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(56)))

        statusBar = TextView(this).apply {
            text = "Initialisation…"
            setBackgroundColor(0xFF0D47A1.toInt())
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(dpToPx(12), dpToPx(4), dpToPx(12), dpToPx(4))
        }
        root.addView(statusBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val content = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val pDevices = buildDevicesPanel()
        val pVehicle = buildVehiclePanel()
        val pMedia   = buildMediaPanel()
        val pScreen  = buildScreenPanel()
        panels = arrayOf(pDevices, pVehicle, pMedia, pScreen)
        panels.forEach { content.addView(it) }
        root.addView(content)

        val tabBar = buildTabBar()
        root.addView(tabBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(58)))

        return root
    }

    private fun buildTabBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
            elevation = 8f
        }
        tabs = arrayOf(
            buildTabItem("Appareils",  android.R.drawable.ic_menu_search)     { showTab(TAB_DEVICES) },
            buildTabItem("Voiture",    android.R.drawable.ic_media_ff)         { showTab(TAB_VEHICLE) },
            buildTabItem("Média",      android.R.drawable.ic_media_play)       { showTab(TAB_MEDIA) },
            buildTabItem("Écran",      android.R.drawable.ic_menu_slideshow)   { showTab(TAB_SCREEN) }
        )
        tabs.forEach { bar.addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)) }
        return bar
    }

    private fun buildTabItem(label: String, iconRes: Int, onClick: () -> Unit) = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = 11f
        setCompoundDrawablesWithIntrinsicBounds(0, iconRes, 0, 0)
        compoundDrawablePadding = dpToPx(2)
        setOnClickListener { onClick() }
        setTextColor(0xFF757575.toInt())
        setPadding(0, dpToPx(6), 0, dpToPx(6))
    }

    // ─── Devices panel ────────────────────────────────────────────────────────

    private fun buildDevicesPanel(): LinearLayout {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = fullFrame()
        }

        val scanBtn = Button(this).apply {
            text = "Rechercher (Wi-Fi)"
            setBackgroundColor(0xFF1565C0.toInt()); setTextColor(Color.WHITE)
            setOnClickListener { startDiscovery() }
        }
        layout.addView(scanBtn, margins(12, 12, 12, 8))

        emptyDevicesText = TextView(this).apply {
            text = "Aucun appareil Chromecast/DLNA trouvé.\nVérifiez le Wi-Fi."
            gravity = Gravity.CENTER; setTextColor(Color.GRAY); textSize = 14f
            setPadding(dpToPx(24), dpToPx(32), dpToPx(24), dpToPx(32))
        }
        layout.addView(emptyDevicesText)

        deviceList = ListView(this).apply {
            adapter = deviceAdapter; dividerHeight = 1; visibility = View.GONE
            setOnItemClickListener { _, _, position, _ ->
                val d = deviceAdapter.getItem(position)
                deviceAdapter.setSelected(d.id)
                updateSelectedDeviceDisplay()
                showToast("${d.name} sélectionné")
            }
        }
        layout.addView(deviceList, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        layout.addView(footerText("Chromecast · Smart TV DLNA/UPnP"))
        return layout
    }

    // ─── Vehicle panel ────────────────────────────────────────────────────────

    private fun buildVehiclePanel(): LinearLayout {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutParams = fullFrame()
            setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12))
        }

        val title = TextView(this).apply {
            text = "Véhicule — Android Auto"
            textSize = 18f; setTextColor(0xFF1565C0.toInt())
            setPadding(0, 0, 0, dpToPx(8))
        }
        layout.addView(title)

        val scanRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        btScanButton = Button(this).apply {
            text = "Scan Bluetooth"
            setBackgroundColor(0xFF1565C0.toInt()); setTextColor(Color.WHITE); textSize = 13f
            setOnClickListener { startBluetoothScan() }
        }
        scanRow.addView(btScanButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dpToPx(6) })

        wifiScanButton = Button(this).apply {
            text = "Scan Wi-Fi"
            setBackgroundColor(0xFF0288D1.toInt()); setTextColor(Color.WHITE); textSize = 13f
            setOnClickListener { startWifiScan() }
        }
        scanRow.addView(wifiScanButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        layout.addView(scanRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dpToPx(8) })

        emptyVehiclesText = TextView(this).apply {
            text = "Aucun véhicule détecté.\nActivez le Bluetooth et/ou connectez-vous au Wi-Fi de votre voiture."
            gravity = Gravity.CENTER; setTextColor(Color.GRAY); textSize = 13f
            setPadding(dpToPx(12), dpToPx(16), dpToPx(12), dpToPx(16))
        }
        layout.addView(emptyVehiclesText)

        vehicleList = ListView(this).apply {
            adapter = vehicleAdapter; dividerHeight = 1; visibility = View.GONE
            setOnItemClickListener { _, _, pos, _ ->
                selectedVehicle = vehicleAdapter.getItem(pos)
                vehicleAdapter.setSelected(selectedVehicle!!.id)
                updateVehicleGuide()
            }
        }
        layout.addView(vehicleList, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(160)))

        vehicleStatusText = TextView(this).apply {
            text = ""; textSize = 12f; setTextColor(0xFF388E3C.toInt())
        }
        layout.addView(vehicleStatusText)

        val divider = View(this).apply { setBackgroundColor(0xFFE0E0E0.toInt()) }
        layout.addView(divider, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { topMargin = dpToPx(8); bottomMargin = dpToPx(8) })

        vehicleGuideText = TextView(this).apply {
            text = "Sélectionnez un véhicule pour voir le guide de connexion."
            textSize = 12f; setTextColor(Color.DKGRAY)
        }
        layout.addView(vehicleGuideText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        connectAAButton = Button(this).apply {
            text = "Ouvrir Android Auto"
            setBackgroundColor(0xFF388E3C.toInt()); setTextColor(Color.WHITE)
            setOnClickListener {
                if (!androidAutoConnector.launchAndroidAuto()) {
                    showAlert("Android Auto absent",
                        "Installez l'application Android Auto depuis le Play Store.")
                }
            }
            visibility = View.GONE
        }
        layout.addView(connectAAButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dpToPx(8) })

        return layout
    }

    // ─── Media panel ─────────────────────────────────────────────────────────

    private fun buildMediaPanel(): LinearLayout {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutParams = fullFrame()
            setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))
        }

        layout.addView(panelTitle("Diffuser un média"))

        layout.addView(label("URL du contenu (MP4, HLS, DASH…)"))

        mediaUrlField = EditText(this).apply {
            hint = "https://exemple.com/video.mp4"
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI or
                    android.text.InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        layout.addView(mediaUrlField, margins(0, 0, 0, 12))

        layout.addView(label("Type de contenu"))

        mimeSpinner = Spinner(this)
        val mimeTypes = arrayOf("video/mp4", "video/x-matroska", "video/webm",
            "application/x-mpegURL", "audio/mpeg", "image/jpeg")
        mimeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, mimeTypes).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        layout.addView(mimeSpinner, margins(0, 0, 0, 12))

        selectedDeviceText = TextView(this).apply {
            text = "Aucun périphérique sélectionné"
            textSize = 13f; setTextColor(Color.GRAY)
            setPadding(0, dpToPx(4), 0, dpToPx(12))
        }
        layout.addView(selectedDeviceText)

        castButton = Button(this).apply {
            text = "Diffuser"
            setBackgroundColor(0xFF1565C0.toInt()); setTextColor(Color.WHITE)
            setOnClickListener { startCasting() }
        }
        layout.addView(castButton, margins(0, 0, 0, 8))

        stopCastButton = Button(this).apply {
            text = "Arrêter la diffusion"
            setBackgroundColor(0xFFD32F2F.toInt()); setTextColor(Color.WHITE)
            setOnClickListener { stopCasting() }
            visibility = View.GONE
        }
        layout.addView(stopCastButton, margins(0, 0, 0, 16))

        layout.addView(panelSubtitle("YouTube · Netflix · Prime Video"))
        layout.addView(noteText("Pour ces services, utilisez le bouton Cast intégré dans leurs applications officielles. La diffusion d'écran (onglet Écran) fonctionne pour tout contenu."))

        return layout
    }

    // ─── Screen panel ─────────────────────────────────────────────────────────

    private fun buildScreenPanel(): LinearLayout {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutParams = fullFrame()
            setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))
        }

        layout.addView(panelTitle("Diffusion d'écran"))

        layout.addView(noteText("Partagez l'intégralité de votre écran vers un téléviseur ou la voiture (Miracast, Chromecast, DLNA)."))

        mirrorStatusText = TextView(this).apply {
            text = "État : Inactif"; textSize = 14f; setTextColor(Color.DKGRAY)
            setPadding(0, dpToPx(8), 0, dpToPx(16))
        }
        layout.addView(mirrorStatusText)

        mirrorButton = Button(this).apply {
            text = "Démarrer la diffusion d'écran"
            setBackgroundColor(0xFF388E3C.toInt()); setTextColor(Color.WHITE)
            setOnClickListener { requestScreenCapture() }
        }
        layout.addView(mirrorButton, margins(0, 0, 0, 8))

        stopMirrorButton = Button(this).apply {
            text = "Arrêter la diffusion d'écran"
            setBackgroundColor(0xFFD32F2F.toInt()); setTextColor(Color.WHITE)
            setOnClickListener { mirrorService?.stopMirroring(); updateMirrorUI() }
            visibility = View.GONE
        }
        layout.addView(stopMirrorButton, margins(0, 0, 0, 16))

        val divider = View(this).apply { setBackgroundColor(0xFFE0E0E0.toInt()) }
        layout.addView(divider, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { bottomMargin = dpToPx(16) })

        layout.addView(panelSubtitle("Affichage sans fil système"))
        val castSettingsBtn = Button(this).apply {
            text = "Paramètres d'affichage"
            setBackgroundColor(0xFF0288D1.toInt()); setTextColor(Color.WHITE)
            setOnClickListener { openCastSettings() }
        }
        layout.addView(castSettingsBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        return layout
    }

    // ─── Tab switching ────────────────────────────────────────────────────────

    private fun showTab(tab: Int) {
        currentTab = tab
        panels.forEachIndexed { i, p -> p.visibility = if (i == tab) View.VISIBLE else View.GONE }
        val active = 0xFF1565C0.toInt()
        val inactive = 0xFF757575.toInt()
        tabs.forEachIndexed { i, t -> t.setTextColor(if (i == tab) active else inactive) }
    }

    // ─── Discovery ────────────────────────────────────────────────────────────

    private fun startDiscovery() {
        setStatus("Recherche Wi-Fi en cours…")
        discovery?.stop()
        discovery = DeviceDiscovery(object : DeviceDiscovery.DiscoveryListener {
            override fun onDeviceFound(device: CastDevice) {
                mainHandler.post {
                    deviceAdapter.addDevice(device)
                    updateDeviceListVisibility()
                    setStatus("${deviceAdapter.count} appareil(s) trouvé(s)")
                }
            }
            override fun onDeviceLost(deviceId: String) {
                mainHandler.post { deviceAdapter.removeDevice(deviceId); updateDeviceListVisibility() }
            }
            override fun onDiscoveryError(message: String) {
                mainHandler.post { setStatus("Erreur: $message") }
            }
        })
        discovery?.start()
        mainHandler.postDelayed({
            if (deviceAdapter.count == 0) setStatus("Aucun appareil Wi-Fi trouvé.")
        }, 8000)
    }

    private fun startBluetoothScan() {
        btDiscovery?.stop()
        btScanButton.isEnabled = false
        setStatus("Scan Bluetooth en cours…")

        btDiscovery = BluetoothDiscovery(this, object : BluetoothDiscovery.BluetoothListener {
            override fun onVehicleFound(device: CastDevice) {
                mainHandler.post {
                    vehicleAdapter.addDevice(device)
                    updateVehicleListVisibility()
                    setStatus("Véhicule trouvé : ${device.name}")
                }
            }
            override fun onDiscoveryFinished() {
                mainHandler.post {
                    btScanButton.isEnabled = true
                    if (vehicleAdapter.count == 0) setStatus("Aucun véhicule Bluetooth trouvé.")
                    else setStatus("Scan Bluetooth terminé — ${vehicleAdapter.count} véhicule(s)")
                }
            }
            override fun onBluetoothUnavailable(reason: String) {
                mainHandler.post {
                    btScanButton.isEnabled = true
                    setStatus(reason)
                    showToast(reason)
                }
            }
        })
        btDiscovery?.start()
    }

    private fun startWifiScan() {
        wifiScanButton.isEnabled = false
        setStatus("Scan Wi-Fi pour Android Auto (port 5277)…")
        androidAutoConnector.scanSubnetForAndroidAuto(
            onDeviceFound = { host, label ->
                mainHandler.post {
                    val d = CastDevice(
                        id = "aa:$host",
                        name = label,
                        host = host,
                        port = 5277,
                        type = DeviceType.VEHICLE,
                        connectionType = com.caster.app.model.ConnectionType.WIFI,
                        androidAutoSupported = true
                    )
                    vehicleAdapter.addDevice(d)
                    updateVehicleListVisibility()
                }
            },
            onScanDone = {
                mainHandler.post {
                    wifiScanButton.isEnabled = true
                    if (vehicleAdapter.count == 0) setStatus("Aucun dongle Android Auto trouvé.")
                    else setStatus("Scan Wi-Fi terminé — ${vehicleAdapter.count} appareil(s)")
                }
            }
        )
    }

    private fun updateVehicleGuide() {
        val v = selectedVehicle ?: return
        vehicleGuideText.text = androidAutoConnector.connectionGuide(v)
        connectAAButton.visibility = View.VISIBLE

        // Probe WiFi port in background
        if (v.host.contains(".")) {
            androidAutoConnector.probeAndroidAutoPort(v.host,
                onReachable = { mainHandler.post { vehicleStatusText.text = "✓ Port Android Auto (5277) joignable" } },
                onUnreachable = { msg -> mainHandler.post { vehicleStatusText.text = msg } }
            )
        }
    }

    // ─── Casting ─────────────────────────────────────────────────────────────

    private fun startCasting() {
        val device = deviceAdapter.getSelectedDevice()
        if (device == null) { showTab(TAB_DEVICES); showToast("Sélectionnez d'abord un appareil"); return }
        val url = mediaUrlField.text.toString().trim()
        if (url.isEmpty()) { showToast("Entrez une URL valide"); return }
        val mime = mimeSpinner.selectedItem.toString()
        setStatus("Diffusion vers ${device.name}…")
        castButton.isEnabled = false

        when (device.type) {
            DeviceType.DLNA -> dlnaCaster.castUrl(device, url, mime, {
                mainHandler.post { castButton.isEnabled = true; stopCastButton.visibility = View.VISIBLE; setStatus("Diffusion en cours sur ${device.name}") }
            }, { err ->
                mainHandler.post { castButton.isEnabled = true; showAlert("Erreur DLNA", err); setStatus("Erreur de diffusion") }
            })
            DeviceType.CHROMECAST -> chromeCaster.connect(device, {
                chromeCaster.launchApp()
                Thread.sleep(1000)
                chromeCaster.castMedia(url, mime, url.substringAfterLast('/'))
                mainHandler.post { castButton.isEnabled = true; stopCastButton.visibility = View.VISIBLE; setStatus("Diffusion Chromecast vers ${device.name}") }
            }, { err ->
                mainHandler.post { castButton.isEnabled = true; showAlert("Erreur Chromecast", err); setStatus("Erreur de connexion") }
            })
            else -> { castButton.isEnabled = true; showToast("Type non supporté pour la diffusion de médias") }
        }
    }

    private fun stopCasting() {
        val device = deviceAdapter.getSelectedDevice()
        if (device?.type == DeviceType.DLNA) {
            dlnaCaster.stop(device) { mainHandler.post { stopCastButton.visibility = View.GONE; setStatus("Diffusion arrêtée") } }
        } else {
            chromeCaster.disconnect(); stopCastButton.visibility = View.GONE; setStatus("Diffusion arrêtée")
        }
    }

    private fun requestScreenCapture() {
        val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(pm.createScreenCaptureIntent(), REQ_SCREEN_CAPTURE)
    }

    private fun openCastSettings() {
        val actions = listOf("android.settings.WIFI_DISPLAY_SETTINGS", "android.settings.CAST_SETTINGS")
        for (a in actions) try { startActivity(Intent(a)); return } catch (e: Exception) { }
        showToast("Paramètres d'affichage non disponibles")
    }

    // ─── UI helpers ──────────────────────────────────────────────────────────

    private fun updateDeviceListVisibility() {
        val has = deviceAdapter.count > 0
        deviceList.visibility = if (has) View.VISIBLE else View.GONE
        emptyDevicesText.visibility = if (has) View.GONE else View.VISIBLE
    }

    private fun updateVehicleListVisibility() {
        val has = vehicleAdapter.count > 0
        vehicleList.visibility = if (has) View.VISIBLE else View.GONE
        emptyVehiclesText.visibility = if (has) View.GONE else View.VISIBLE
    }

    private fun updateSelectedDeviceDisplay() {
        val d = deviceAdapter.getSelectedDevice()
        selectedDeviceText.text = if (d != null) "Appareil : ${d.displayName}" else "Aucun périphérique sélectionné"
    }

    private fun updateMirrorUI() {
        val running = mirrorService?.isMirroring == true
        mirrorButton.visibility = if (running) View.GONE else View.VISIBLE
        stopMirrorButton.visibility = if (running) View.VISIBLE else View.GONE
        mirrorStatusText.text = if (running) "État : Diffusion active" else "État : Inactif"
        mirrorStatusText.setTextColor(if (running) 0xFF388E3C.toInt() else Color.DKGRAY)
    }

    private fun acquireMulticastLock() {
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wm.createMulticastLock("lowkick_mdns").apply {
                setReferenceCounted(true); acquire()
            }
        } catch (e: Exception) { /* non-fatal */ }
    }

    private fun setStatus(text: String) { statusBar.text = text }
    private fun showToast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun showAlert(title: String, message: String) =
        AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null).show()
    private fun dpToPx(dp: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics).toInt()

    private fun fullFrame() = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
    private fun margins(l: Int, t: Int, r: Int, b: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        setMargins(dpToPx(l), dpToPx(t), dpToPx(r), dpToPx(b))
    }
    private fun panelTitle(text: String) = TextView(this).apply {
        this.text = text; textSize = 18f; setTextColor(0xFF1565C0.toInt())
        setPadding(0, 0, 0, dpToPx(8))
    }
    private fun panelSubtitle(text: String) = TextView(this).apply {
        this.text = text; textSize = 14f; setTextColor(0xFF1565C0.toInt())
        setPadding(0, 0, 0, dpToPx(4))
    }
    private fun label(text: String) = TextView(this).apply {
        this.text = text; textSize = 13f; setTextColor(Color.DKGRAY)
    }
    private fun noteText(text: String) = TextView(this).apply {
        this.text = text; textSize = 12f; setTextColor(Color.DKGRAY)
    }
    private fun footerText(text: String) = TextView(this).apply {
        this.text = text; textSize = 11f; setTextColor(Color.GRAY)
        gravity = Gravity.CENTER; setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
    }
}
