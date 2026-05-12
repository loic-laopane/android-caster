package com.caster.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
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

    // ─── Dark neon palette ────────────────────────────────────────────────────
    private val C_BG       = 0xFF08091A.toInt()   // deepest background
    private val C_SURFACE  = 0xFF111428.toInt()   // card surface
    private val C_SURFACE2 = 0xFF1C2040.toInt()   // elevated surface
    private val C_BORDER   = 0xFF2E3260.toInt()   // subtle border
    private val C_CYAN     = 0xFF00D4FF.toInt()   // neon primary
    private val C_BLUE     = 0xFF2979FF.toInt()   // deep blue
    private val C_GREEN    = 0xFF00E676.toInt()   // success / active
    private val C_RED      = 0xFFFF3D57.toInt()   // danger / stop
    private val C_ORANGE   = 0xFFFF9100.toInt()   // warning
    private val C_TEXT1    = 0xFFFFFFFF.toInt()   // primary text
    private val C_TEXT2    = 0xFFB8C0E8.toInt()   // secondary text — readable on dark navy
    private val C_TEXT3    = 0xFF7880AA.toInt()   // hint / inactive — visible but muted

    // ─── Tab indices ─────────────────────────────────────────────────────────
    private val TAB_DEVICES = 0; private val TAB_VEHICLE = 1
    private val TAB_MEDIA   = 2; private val TAB_SCREEN  = 3
    private val REQ_SCREEN_CAPTURE = 1001; private val REQ_PERMISSIONS = 1002

    // ─── Views ────────────────────────────────────────────────────────────────
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var tabs: Array<LinearLayout>
    private lateinit var tabTexts: Array<TextView>
    private lateinit var panels: Array<View>

    private lateinit var deviceList: ListView
    private lateinit var emptyDevicesText: TextView
    private lateinit var vehicleList: ListView
    private lateinit var emptyVehiclesText: TextView
    private lateinit var btScanBtn: Button
    private lateinit var wifiScanBtn: Button
    private lateinit var vehicleGuideText: TextView
    private lateinit var connectAABtn: Button
    private lateinit var vehicleStatusText: TextView
    private lateinit var mediaUrlField: EditText
    private lateinit var mimeSpinner: Spinner
    private lateinit var selectedDeviceLabel: TextView
    private lateinit var castBtn: Button
    private lateinit var stopCastBtn: Button
    private lateinit var mirrorBtn: Button
    private lateinit var stopMirrorBtn: Button
    private lateinit var mirrorStatusLabel: TextView

    // ─── Logic ────────────────────────────────────────────────────────────────
    private val dlnaCaster    = DlnaCaster()
    private val chromeCaster  = ChromecastCaster()
    private val deviceAdapter by lazy { DeviceAdapter(this) }
    private val vehicleAdapter by lazy { DeviceAdapter(this) }
    private lateinit var aaConnector: AndroidAutoConnector
    private var discovery: DeviceDiscovery? = null
    private var btDiscovery: BluetoothDiscovery? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var selectedVehicle: CastDevice? = null
    private var mirrorService: ScreenMirrorService? = null
    private var currentTab = TAB_DEVICES

    private val mirrorConn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName, b: IBinder) {
            mirrorService = (b as ScreenMirrorService.LocalBinder).getService(); updateMirrorUI()
        }
        override fun onServiceDisconnected(n: ComponentName) { mirrorService = null; updateMirrorUI() }
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        aaConnector = AndroidAutoConnector(this)
        acquireMulticastLock()
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        window.statusBarColor = C_BG
        setContentView(buildRoot())
        showTab(TAB_DEVICES)
        requestNeededPermissions()
    }

    override fun onDestroy() {
        discovery?.stop(); btDiscovery?.stop()
        multicastLock?.release(); chromeCaster.disconnect()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<String>, res: IntArray) {
        if (rc == REQ_PERMISSIONS) startDiscovery()
    }

    override fun onActivityResult(rc: Int, result: Int, data: Intent?) {
        if (rc == REQ_SCREEN_CAPTURE && result == RESULT_OK && data != null) {
            val svcIntent = Intent(this, ScreenMirrorService::class.java).apply {
                putExtra(ScreenMirrorService.EXTRA_RESULT_CODE, result)
                putExtra(ScreenMirrorService.EXTRA_DATA, data)
            }
            // API 26+: must use startForegroundService() — call via reflection (android.jar is API 23)
            if (Build.VERSION.SDK_INT >= 26) {
                try {
                    javaClass.superclass?.getMethod("startForegroundService", Intent::class.java)
                        ?.invoke(this, svcIntent) ?: startService(svcIntent)
                } catch (e: Exception) { startService(svcIntent) }
            } else startService(svcIntent)
            bindService(Intent(this, ScreenMirrorService::class.java), mirrorConn, 0)
        }
    }

    // ─── Permissions ─────────────────────────────────────────────────────────

    private fun requestNeededPermissions() {
        val needed = mutableListOf<String>()
        listOf(android.Manifest.permission.ACCESS_FINE_LOCATION,
               android.Manifest.permission.RECORD_AUDIO).forEach {
            if (checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED) needed.add(it)
        }
        if (Build.VERSION.SDK_INT >= 31) {
            listOf("android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT").forEach {
                if (checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED) needed.add(it)
            }
        }
        if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), REQ_PERMISSIONS)
        else startDiscovery()
    }

    // ─── Root layout ─────────────────────────────────────────────────────────

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C_BG)
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }
        root.addView(buildHeader())
        root.addView(buildStatusStrip())
        val frame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        }
        val pDevices = buildDevicesPanel()
        val pVehicle = buildVehiclePanel()
        val pMedia   = buildMediaPanel()
        val pScreen  = buildScreenPanel()
        panels = arrayOf(pDevices, pVehicle, pMedia, pScreen)
        panels.forEach { frame.addView(it) }
        root.addView(frame)
        root.addView(buildBottomNav())
        return root
    }

    // ─── Header ──────────────────────────────────────────────────────────────

    private fun buildHeader(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(C_BG)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setGravity(Gravity.CENTER_VERTICAL)
        }
        val icon = ImageView(this).apply {
            setImageResource(resources.getIdentifier("ic_launcher", "mipmap", packageName))
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(10) }
        }
        bar.addView(icon)

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        textCol.addView(TextView(this).apply {
            text = "LowKick"
            textSize = 18f; setTypeface(null, Typeface.BOLD)
            setTextColor(C_CYAN)
        })
        textCol.addView(TextView(this).apply {
            text = "Android Auto Caster"
            textSize = 11f; setTextColor(C_TEXT2)
        })
        bar.addView(textCol)
        return bar
    }

    // ─── Status strip ─────────────────────────────────────────────────────────

    private fun buildStatusStrip(): View {
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(C_SURFACE)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setGravity(Gravity.CENTER_VERTICAL)
        }
        statusDot = View(this).apply {
            background = GradientDrawable().apply {
                setShape(GradientDrawable.OVAL); setColor(C_TEXT3)
            }
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(8) }
        }
        strip.addView(statusDot)
        statusText = TextView(this).apply {
            text = "Initialisation…"; textSize = 12f; setTextColor(C_TEXT2)
        }
        strip.addView(statusText)
        return strip
    }

    // ─── Bottom nav ───────────────────────────────────────────────────────────

    private fun buildBottomNav(): View {
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(C_SURFACE)
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(60))
        }
        val tabDefs = listOf(
            Triple("Appareils", android.R.drawable.ic_menu_search, TAB_DEVICES),
            Triple("Voiture",   android.R.drawable.ic_media_ff,    TAB_VEHICLE),
            Triple("Média",     android.R.drawable.ic_media_play,  TAB_MEDIA),
            Triple("Écran",     android.R.drawable.ic_menu_slideshow, TAB_SCREEN)
        )
        tabs = Array(4) { LinearLayout(this) }
        tabTexts = Array(4) { TextView(this) }

        tabDefs.forEachIndexed { i, (label, icon, tabId) ->
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setGravity(Gravity.CENTER)
                layoutParams = LinearLayout.LayoutParams(0, MATCH, 1f)
                setOnClickListener { showTab(tabId) }
                setPadding(0, dp(6), 0, dp(6))
            }
            val img = ImageView(this).apply {
                setImageResource(icon)
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
                setColorFilter(C_TEXT3)
            }
            val txt = TextView(this).apply {
                text = label; textSize = 10f; setGravity(Gravity.CENTER)
                setTextColor(C_TEXT3)
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(3) }
            }
            col.addView(img); col.addView(txt)
            tabs[i] = col; tabTexts[i] = txt
            nav.addView(col)
        }
        return nav
    }

    // ─── Devices panel ────────────────────────────────────────────────────────

    private fun buildDevicesPanel(): View {
        val scroll = scrollPanel(); val root = scroll.content()

        root.addView(sectionTitle("Diffusion réseau"))
        root.addView(card().also { c ->
            c.addView(bodyText("Chromecasts, Smart TV et appareils DLNA/UPnP sur votre réseau Wi-Fi."))
            c.addView(spacer(12))
            val btn = neonBtn("Rechercher des appareils") { startDiscovery() }
            c.addView(btn)
        })
        root.addView(spacer(12))

        emptyDevicesText = bodyText("Aucun appareil trouvé.\nVérifiez que vous êtes sur le même Wi-Fi.").apply {
            setGravity(Gravity.CENTER); setPadding(dp(24), dp(32), dp(24), dp(32))
        }
        root.addView(emptyDevicesText)

        deviceList = ListView(this).apply {
            adapter = deviceAdapter; dividerHeight = 0; visibility = View.GONE
            setBackgroundColor(Color.TRANSPARENT)
            setOnItemClickListener { _, _, pos, _ ->
                deviceAdapter.setSelected(deviceAdapter.getItem(pos).id)
                updateSelectedDeviceLabel()
                showToast("${deviceAdapter.getItem(pos).name} sélectionné")
            }
        }
        root.addView(deviceList, LinearLayout.LayoutParams(MATCH, WRAP))
        return scroll
    }

    // ─── Vehicle panel ────────────────────────────────────────────────────────

    private fun buildVehiclePanel(): View {
        val scroll = scrollPanel(); val root = scroll.content()
        root.addView(sectionTitle("Véhicule & Android Auto"))

        root.addView(card().also { c ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            btScanBtn = neonBtn("Bluetooth", color = C_CYAN) { startBluetoothScan() }.apply {
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(8) }
            }
            wifiScanBtn = neonBtn("Wi-Fi AA", color = C_BLUE) { startWifiScan() }.apply {
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            }
            row.addView(btScanBtn); row.addView(wifiScanBtn)
            c.addView(row)
        })
        root.addView(spacer(10))

        emptyVehiclesText = bodyText("Aucun véhicule détecté.\nActivez le Bluetooth ou connectez-vous au Wi-Fi de votre voiture.").apply {
            setGravity(Gravity.CENTER); setPadding(dp(24), dp(20), dp(24), dp(20))
        }
        root.addView(emptyVehiclesText)

        vehicleList = ListView(this).apply {
            adapter = vehicleAdapter; dividerHeight = 0; visibility = View.GONE
            setBackgroundColor(Color.TRANSPARENT)
            setOnItemClickListener { _, _, pos, _ ->
                selectedVehicle = vehicleAdapter.getItem(pos)
                vehicleAdapter.setSelected(selectedVehicle!!.id)
                updateVehicleGuide()
            }
        }
        root.addView(vehicleList, LinearLayout.LayoutParams(MATCH, dp(200)))

        vehicleStatusText = TextView(this).apply {
            textSize = 12f; setTextColor(C_GREEN)
            setPadding(dp(4), dp(6), dp(4), 0)
        }
        root.addView(vehicleStatusText)
        root.addView(spacer(10))

        root.addView(card(C_SURFACE2).also { c ->
            vehicleGuideText = bodyText("Sélectionnez un véhicule ci-dessus pour voir le guide de connexion.")
            c.addView(vehicleGuideText)
        })
        root.addView(spacer(12))

        connectAABtn = neonBtn("Ouvrir Android Auto", color = C_GREEN) {
            if (!aaConnector.launchAndroidAuto())
                showAlert("Android Auto absent", "Installez Android Auto depuis le Play Store.")
        }.apply { visibility = View.GONE }
        root.addView(connectAABtn)

        root.addView(spacer(8))
        root.addView(neonBtn("Diffusion d'écran → voiture", color = C_BLUE) {
            showTab(TAB_SCREEN)
        })

        root.addView(spacer(12))
        root.addView(card(C_SURFACE2).also { c ->
            c.addView(labelText("Comment ça marche sur la Kia ?"))
            c.addView(spacer(6))
            c.addView(bodyText(
                "1. Connectez le téléphone à la Kia via Android Auto (USB ou sans fil).\n" +
                "2. LowKick apparaît dans la liste des apps média d'Android Auto sur l'écran de la voiture.\n" +
                "3. Pour diffuser l'écran complet du téléphone vers la voiture : utilisez l'onglet Écran — la diffusion continue même écran éteint.\n\n" +
                "Kia Sportage 2024 sans fil : Réglages › Général › Connexion › Android Auto › Sans fil."
            ))
        })
        return scroll
    }

    // ─── Media panel ─────────────────────────────────────────────────────────

    private fun buildMediaPanel(): View {
        val scroll = scrollPanel(); val root = scroll.content()
        root.addView(sectionTitle("Diffuser un média"))

        root.addView(card().also { c ->
            c.addView(labelText("URL du contenu"))
            c.addView(spacer(6))
            mediaUrlField = EditText(this).apply {
                hint = "https://exemple.com/video.mp4"
                setHintTextColor(C_TEXT3)
                setTextColor(C_TEXT1)
                textSize = 14f
                setSingleLine(true)
                background = GradientDrawable().apply {
                    setColor(C_SURFACE2); setCornerRadius(dp(10).toFloat())
                    setStroke(dp(1), C_BORDER)
                }
                setPadding(dp(14), dp(12), dp(14), dp(12))
                inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI or
                        android.text.InputType.TYPE_CLASS_TEXT
            }
            c.addView(mediaUrlField, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) })

            c.addView(labelText("Type de contenu"))
            c.addView(spacer(6))
            mimeSpinner = Spinner(this).apply {
                background = GradientDrawable().apply {
                    setColor(C_SURFACE2); setCornerRadius(dp(10).toFloat())
                    setStroke(dp(1), C_BORDER)
                }
                adapter = ArrayAdapter(this@MainActivity,
                    android.R.layout.simple_spinner_item,
                    arrayOf("video/mp4","video/x-matroska","video/webm",
                            "application/x-mpegURL","audio/mpeg","image/jpeg")).also {
                    it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                }
            }
            c.addView(mimeSpinner, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(16) })

            selectedDeviceLabel = bodyText("Aucun appareil sélectionné — allez dans l'onglet Appareils").apply {
                setTextColor(C_TEXT3)
            }
            c.addView(selectedDeviceLabel)
        })
        root.addView(spacer(12))

        castBtn = neonBtn("Diffuser") { startCasting() }
        root.addView(castBtn)
        root.addView(spacer(8))
        stopCastBtn = dangerBtn("Arrêter la diffusion") { stopCasting() }.apply { visibility = View.GONE }
        root.addView(stopCastBtn)
        root.addView(spacer(16))

        root.addView(card(C_SURFACE2).also { c ->
            c.addView(labelText("YouTube · Netflix · Prime Video"))
            c.addView(spacer(6))
            c.addView(bodyText("Ces services utilisent du contenu DRM. Utilisez le bouton Cast intégré dans leurs apps, ou la diffusion d'écran (onglet Écran) pour tout contenu."))
        })
        return scroll
    }

    // ─── Screen panel ─────────────────────────────────────────────────────────

    private fun buildScreenPanel(): View {
        val scroll = scrollPanel(); val root = scroll.content()
        root.addView(sectionTitle("Diffusion d'écran"))

        root.addView(card().also { c ->
            mirrorStatusLabel = TextView(this).apply {
                text = "Inactif"; textSize = 24f
                setTypeface(null, Typeface.BOLD)
                setTextColor(C_TEXT3); setGravity(Gravity.CENTER)
                setPadding(0, dp(16), 0, dp(16))
            }
            c.addView(mirrorStatusLabel)
            c.addView(bodyText("Capturez l'intégralité de votre écran et diffusez-le vers un Chromecast ou une TV.\nFonctionne aussi écran éteint — un service en arrière-plan maintient la diffusion active.").apply {
                setGravity(Gravity.CENTER)
            })
        })
        root.addView(spacer(16))

        mirrorBtn = neonBtn("Démarrer la diffusion d'écran", color = C_GREEN) { requestScreenCapture() }
        root.addView(mirrorBtn)
        root.addView(spacer(8))
        stopMirrorBtn = dangerBtn("Arrêter la diffusion d'écran") {
            mirrorService?.stopMirroring(); updateMirrorUI()
        }.apply { visibility = View.GONE }
        root.addView(stopMirrorBtn)
        root.addView(spacer(24))

        root.addView(card(C_SURFACE2).also { c ->
            c.addView(labelText("Affichage sans fil système"))
            c.addView(spacer(6))
            c.addView(bodyText("Utilisez le Miracast / Cast natif d'Android pour les TV et dongles sans fil."))
            c.addView(spacer(10))
            c.addView(neonBtn("Ouvrir les paramètres d'affichage", color = C_BLUE) { openCastSettings() })
        })
        return scroll
    }

    // ─── Tab switching ────────────────────────────────────────────────────────

    private fun showTab(tab: Int) {
        currentTab = tab
        panels.forEachIndexed { i, p -> p.visibility = if (i == tab) View.VISIBLE else View.GONE }
        tabs.forEachIndexed { i, col ->
            val active = i == tab
            (col.getChildAt(0) as? ImageView)?.setColorFilter(if (active) C_CYAN else C_TEXT3)
            (col.getChildAt(1) as? TextView)?.setTextColor(if (active) C_CYAN else C_TEXT3)
            col.setBackgroundColor(if (active) 0x1400D4FF else Color.TRANSPARENT)
        }
    }

    // ─── Discovery ────────────────────────────────────────────────────────────

    private fun startDiscovery() {
        setStatus("Recherche Wi-Fi…", C_CYAN)
        discovery?.stop()
        discovery = DeviceDiscovery(object : DeviceDiscovery.DiscoveryListener {
            override fun onDeviceFound(d: CastDevice) { mainHandler.post {
                deviceAdapter.addDevice(d); updateDeviceListVisibility()
                setStatus("${deviceAdapter.count} appareil(s) trouvé(s)", C_GREEN)
            } }
            override fun onDeviceLost(id: String) { mainHandler.post {
                deviceAdapter.removeDevice(id); updateDeviceListVisibility()
            } }
            override fun onDiscoveryError(msg: String) { mainHandler.post { setStatus(msg, C_RED) } }
        })
        discovery?.start()
        mainHandler.postDelayed({
            if (deviceAdapter.count == 0) setStatus("Aucun appareil Wi-Fi trouvé.", C_TEXT3)
        }, 8000)
    }

    private fun startBluetoothScan() {
        btDiscovery?.stop(); btScanBtn.isEnabled = false
        setStatus("Scan Bluetooth…", C_CYAN)
        btDiscovery = BluetoothDiscovery(this, object : BluetoothDiscovery.BluetoothListener {
            override fun onVehicleFound(d: CastDevice) { mainHandler.post {
                vehicleAdapter.addDevice(d); updateVehicleListVisibility()
                setStatus("Véhicule : ${d.name}", C_GREEN)
            } }
            override fun onDiscoveryFinished() { mainHandler.post {
                btScanBtn.isEnabled = true
                if (vehicleAdapter.count == 0) setStatus("Aucun véhicule Bluetooth.", C_TEXT3)
                else setStatus("${vehicleAdapter.count} véhicule(s) trouvé(s)", C_GREEN)
            } }
            override fun onBluetoothUnavailable(reason: String) { mainHandler.post {
                btScanBtn.isEnabled = true; setStatus(reason, C_RED); showToast(reason)
            } }
        })
        btDiscovery?.start()
    }

    private fun startWifiScan() {
        wifiScanBtn.isEnabled = false; setStatus("Scan Wi-Fi port 5277…", C_CYAN)
        aaConnector.scanSubnetForAndroidAuto(
            onDeviceFound = { host, label ->
                mainHandler.post {
                    vehicleAdapter.addDevice(CastDevice(
                        id = "aa:$host", name = label, host = host, port = 5277,
                        type = DeviceType.VEHICLE,
                        connectionType = com.caster.app.model.ConnectionType.WIFI,
                        androidAutoSupported = true))
                    updateVehicleListVisibility()
                }
            },
            onScanDone = {
                mainHandler.post {
                    wifiScanBtn.isEnabled = true
                    if (vehicleAdapter.count == 0) setStatus("Aucun dongle AA trouvé.", C_TEXT3)
                    else setStatus("${vehicleAdapter.count} appareil(s) Android Auto", C_GREEN)
                }
            }
        )
    }

    private fun updateVehicleGuide() {
        val v = selectedVehicle ?: return
        vehicleGuideText.text = aaConnector.connectionGuide(v)
        connectAABtn.visibility = View.VISIBLE
        if (v.host.contains(".")) {
            aaConnector.probeAndroidAutoPort(v.host,
                onReachable   = { mainHandler.post { vehicleStatusText.text = "✓ Port Android Auto (5277) joignable" } },
                onUnreachable = { msg -> mainHandler.post { vehicleStatusText.text = msg } })
        }
    }

    // ─── Casting ─────────────────────────────────────────────────────────────

    private fun startCasting() {
        val device = deviceAdapter.getSelectedDevice()
        if (device == null) { showTab(TAB_DEVICES); showToast("Sélectionnez un appareil"); return }
        val url = mediaUrlField.text.toString().trim()
        if (url.isEmpty()) { showToast("Entrez une URL"); return }
        val mime = mimeSpinner.selectedItem.toString()
        setStatus("Diffusion vers ${device.name}…", C_CYAN); castBtn.isEnabled = false
        when (device.type) {
            DeviceType.DLNA -> dlnaCaster.castUrl(device, url, mime, {
                mainHandler.post { castBtn.isEnabled = true; stopCastBtn.visibility = View.VISIBLE; setStatus("En cours sur ${device.name}", C_GREEN) }
            }, { err ->
                mainHandler.post { castBtn.isEnabled = true; showAlert("Erreur DLNA", err); setStatus("Erreur", C_RED) }
            })
            DeviceType.CHROMECAST -> chromeCaster.connect(device, {
                chromeCaster.launchApp(); Thread.sleep(1000)
                chromeCaster.castMedia(url, mime, url.substringAfterLast('/'))
                mainHandler.post { castBtn.isEnabled = true; stopCastBtn.visibility = View.VISIBLE; setStatus("Chromecast: ${device.name}", C_GREEN) }
            }, { err ->
                mainHandler.post { castBtn.isEnabled = true; showAlert("Erreur Chromecast", err); setStatus("Erreur", C_RED) }
            })
            else -> { castBtn.isEnabled = true; showToast("Type non supporté pour la diffusion") }
        }
    }

    private fun stopCasting() {
        val d = deviceAdapter.getSelectedDevice()
        if (d?.type == DeviceType.DLNA) {
            dlnaCaster.stop(d) { mainHandler.post { stopCastBtn.visibility = View.GONE; setStatus("Arrêté", C_TEXT2) } }
        } else { chromeCaster.disconnect(); stopCastBtn.visibility = View.GONE; setStatus("Arrêté", C_TEXT2) }
    }

    private fun requestScreenCapture() {
        val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(pm.createScreenCaptureIntent(), REQ_SCREEN_CAPTURE)
    }

    private fun openCastSettings() {
        for (a in listOf("android.settings.WIFI_DISPLAY_SETTINGS", "android.settings.CAST_SETTINGS"))
            try { startActivity(Intent(a)); return } catch (e: Exception) {}
        showToast("Paramètres non disponibles sur cet appareil")
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

    private fun updateSelectedDeviceLabel() {
        val d = deviceAdapter.getSelectedDevice()
        selectedDeviceLabel.text = if (d != null) "${d.displayName}  ·  ${d.typeLabel}" else "Aucun appareil sélectionné"
        selectedDeviceLabel.setTextColor(if (d != null) C_CYAN else C_TEXT3)
    }

    private fun updateMirrorUI() {
        val on = mirrorService?.isMirroring == true
        mirrorBtn.visibility = if (on) View.GONE else View.VISIBLE
        stopMirrorBtn.visibility = if (on) View.VISIBLE else View.GONE
        mirrorStatusLabel.text = if (on) "ACTIF" else "Inactif"
        mirrorStatusLabel.setTextColor(if (on) C_GREEN else C_TEXT3)
    }

    private fun setStatus(text: String, dotColor: Int = C_TEXT3) {
        statusText.text = text
        (statusDot.background as? GradientDrawable)?.setColor(dotColor)
    }

    private fun acquireMulticastLock() = try {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wm.createMulticastLock("lowkick_mdns").apply { setReferenceCounted(true); acquire() }
    } catch (e: Exception) {}

    private fun showToast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun showAlert(title: String, msg: String) =
        AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show()

    // ─── Layout builders ─────────────────────────────────────────────────────

    /** Returns a [LinearLayout] scroller pair. Use [scrollContent] to get the inner container. */
    private fun scrollPanel(): ScrollView {
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(24))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(C_BG)
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            visibility = View.GONE
            addView(inner)
        }
        scroll.tag = inner
        return scroll
    }

    private fun ScrollView.content(): LinearLayout = tag as LinearLayout

    private fun card(bg: Int = C_SURFACE): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundRect(bg, 14, C_BORDER)
        setPadding(dp(16), dp(16), dp(16), dp(16))
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(6) }
    }

    private fun neonBtn(label: String, color: Int = C_CYAN, onClick: () -> Unit) = Button(this).apply {
        text = label; setTextColor(C_TEXT1); textSize = 14f; setAllCaps(false)
        background = roundRect(color, 24)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
    }

    private fun dangerBtn(label: String, onClick: () -> Unit) = neonBtn(label, C_RED, onClick)

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text; textSize = 20f; setTypeface(null, Typeface.BOLD)
        setTextColor(C_TEXT1)
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
            bottomMargin = dp(12); topMargin = dp(4)
        }
    }

    private fun labelText(text: String) = TextView(this).apply {
        this.text = text; textSize = 12f; setTypeface(null, Typeface.BOLD)
        setTextColor(C_CYAN)
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
    }

    private fun bodyText(text: String) = TextView(this).apply {
        this.text = text; textSize = 13f; setTextColor(C_TEXT2)
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
    }

    private fun spacer(dpH: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(dpH))
    }

    private fun roundRect(color: Int, cornerDp: Int, stroke: Int? = null) =
        GradientDrawable().apply {
            setColor(color)
            setCornerRadius(dp(cornerDp).toFloat())
            stroke?.let { setStroke(1, it) }
        }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP  = ViewGroup.LayoutParams.WRAP_CONTENT
}
