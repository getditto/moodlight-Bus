package live.ditto.moodlight

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.TextView
import android.widget.ToggleButton
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.couchbase.lite.CouchbaseLite
import com.couchbase.lite.ListenerToken
import com.couchbase.lite.Query
import live.ditto.Ditto
import live.ditto.DittoIdentity
import live.ditto.DittoLiveQueryEvent
import live.ditto.DittoPendingCursorOperation
import live.ditto.android.DefaultAndroidDittoDependencies
import live.ditto.transports.DittoSyncPermissions
import kotlin.properties.Delegates
import kotlin.random.Random
import yuku.ambilwarna.AmbilWarnaDialog


class MainActivity : AppCompatActivity() {

    private var ditto: Ditto? = null
    private lateinit var dittoTaskManagerRepository: TaskManagerRepository
    private var dittoBridgeDataManager:DataManager? = null

    // View Variables
    lateinit var mLayout: ConstraintLayout
    var mDefaultColor by Delegates.notNull<Int>()
    private lateinit var mButton: Button
    private lateinit var mTextView: TextView
    private lateinit var mLightSwitch: ToggleButton
    private var isOff = false
    private var isLocalChange = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Ditto/CB setup
        setupLocalCB()
        setUpDittoSync()
        checkLocationPermission()
        checkDittoPermission()
        //Setup Repository
        setUpCBRepository()

        getPersisted()
        setupLiveQuery()

        this.mLayout = findViewById(R.id.layout)
        this.mLayout.setBackgroundColor(mDefaultColor)
        this.mLayout.setOnClickListener {
            val color = Color.rgb(
                Random.nextInt(0, 255),
                Random.nextInt(0, 255),
                Random.nextInt(0, 255)
            )
            val red = Color.red(color)
            val green = Color.green(color)
            val blue = Color.blue(color)
            this.updateColors(red, green, blue)
        }

        // View - light switch
        this.mTextView = findViewById(R.id.textview)
        this.mTextView.setTextColor(Color.WHITE)
        this.mTextView.textSize = 22.0F
        this.mTextView.gravity = Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL
        this.mButton = findViewById(R.id.button)
        this.mButton.setOnClickListener { openColorPicker(); }
        this.mLightSwitch = findViewById(R.id.toggleButton)
        this.mLightSwitch.setOnClickListener {
            dittoTaskManagerRepository.updateIsOFF("5", !this.isOff)
        }
    }

    private fun setupLiveQuery() {
        val  database = dittoTaskManagerRepository.getDatabaseLights()
        database.addChangeListener {
            val doc = database.getDocument("5")
            if(doc != null) {
                val red = doc.getDouble("red")
                val green = doc.getDouble("green")
                val blue = doc.getDouble("blue")
                val isOff = doc.getBoolean("isOff")
                this.mDefaultColor = Color.rgb(red.toInt(), green.toInt(), blue.toInt())
                this.isOff = isOff
                this.mLayout.setBackgroundColor(mDefaultColor)
                toggleLight(this.isOff)
            }
        }
    }

    private fun getPersisted() {
        this.mDefaultColor = this.dittoTaskManagerRepository.getInitialColors("5")
    }

    private fun updateColors (red: Int, green: Int, blue: Int) {
        this.isLocalChange = true
        dittoTaskManagerRepository.updateColors("5", red.toDouble(), green.toDouble(), blue.toDouble())
    }

    private fun toggleLight(isOff: Boolean) {
        if(isOff) {
            this.mLayout.setBackgroundColor(Color.BLACK)
            this.mButton.setTextColor(Color.BLACK)
            this.mButton.setBackgroundColor(Color.BLACK)
            this.mButton.isClickable = false
            this.mTextView.setTextColor(Color.BLACK)
            this.mLayout.setOnClickListener { null }
        }
        else {
            this.mLayout.setBackgroundColor(mDefaultColor)
            this.mButton.setTextColor(Color.WHITE)
            this.mButton.setBackgroundColor(Color.BLUE)
            this.mButton.isClickable = true
            this.mTextView.setTextColor(Color.WHITE)

            this.mLayout.setOnClickListener {
                val color = Color.rgb(
                    Random.nextInt(0, 255),
                    Random.nextInt(0, 255),
                    Random.nextInt(0, 255)
                )
                val red = Color.red(color)
                val green = Color.green(color)
                val blue = Color.blue(color)
                this.updateColors(red, green, blue)
            }
        }
        this.isOff = isOff
    }

    private fun openColorPicker() {
        var self = this
        val ambilWarnaListenerObj = object : AmbilWarnaDialog.OnAmbilWarnaListener {
            override fun onCancel(dialog: AmbilWarnaDialog?) {
                mLayout.setBackgroundColor(mDefaultColor)
            }

            override fun onOk(dialog: AmbilWarnaDialog?, color: Int) {
                val red = Color.red(color)
                val green = Color.green(color)
                val blue = Color.blue(color)
                updateColors(red, green, blue)
            }
        }

        val colorPicker = AmbilWarnaDialog(this, this.mDefaultColor, ambilWarnaListenerObj)
        colorPicker.show()
    }

    private fun setupLocalCB() {
        CouchbaseLite.init(baseContext)
    }

    private fun setUpCBRepository() {
        val repository = CBTaskManagerLocalRepositoryImpl(baseContext.filesDir.absolutePath)
        dittoBridgeDataManager = DataManager(ditto!!,repository.database)
        dittoTaskManagerRepository = repository
    }

    private fun setUpDittoSync() {
        // Create an instance of DittoSyncKit
        val androidDependencies = DefaultAndroidDittoDependencies(applicationContext)
        val ditto = Ditto(
            androidDependencies, DittoIdentity.OnlinePlayground(
                androidDependencies, appID = "4086b076-3288-4d2b-a454-724ce3319fe5", token = "46bbf631-5465-4390-a0d9-48c71d1c59a9", enableDittoCloudSync = true
            )
        )
        this.ditto = ditto

        // This starts DittoSyncKit's background synchronization
        this.ditto?.startSync()
    }

    fun checkDittoPermission() {
        val missing = DittoSyncPermissions(this).missingPermissions()
        if (missing.isNotEmpty()) {
            this.requestPermissions(missing, 0)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Regardless of the outcome, tell Ditto that permissions maybe changed
        ditto?.refreshPermissions()
    }

    fun checkLocationPermission() {
        // On Android, parts of Bluetooth LE and WiFi Direct require location permission
        // Ditto will operate without it but data sync may be impossible in certain scenarios
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // For this app we will prompt the user for this permission every time if it is missing
            // We ignore the result - Ditto will automatically notice when the permission is granted
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                0
            )
        }
    }
}