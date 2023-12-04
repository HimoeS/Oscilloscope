package com.example.esp32bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.jjoe64.graphview.GraphView
import com.jjoe64.graphview.helper.StaticLabelsFormatter
import com.jjoe64.graphview.series.DataPoint
import com.jjoe64.graphview.series.LineGraphSeries
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID


class MainActivity : AppCompatActivity() {

    private val graph: GraphView by lazy {
        findViewById(R.id.graphView)
    }

    private val dataTextView: TextView by lazy {
        findViewById(R.id.dataTextView)
    }

    private val maxDataTextView: TextView by lazy {
        findViewById(R.id.maxDataTextView)
    }

    private val startButton: TextView by lazy {
        findViewById(R.id.startButton)
    }

    private val stopButton: TextView by lazy {
        findViewById(R.id.stopButton)
    }

    private val scaleButton: TextView by lazy {
        findViewById(R.id.scaleButton)
    }

    private val resetButton: TextView by lazy {
        findViewById(R.id.resetButton)
    }

    private var isUpdatingGraph = false
    private var currentData = 0.0
    private var Vmax = 0.0

    private val serviceUuid = UUID.fromString("000000ff-0000-1000-8000-00805f9b34fb")
    private val characteristicUuid = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")

    private val bluetoothManager by lazy { getSystemService(BLUETOOTH_SERVICE) as BluetoothManager }
    private val bluetoothAdapter: BluetoothAdapter by lazy { bluetoothManager.adapter }
    private val bluetoothLeScanner by lazy { bluetoothAdapter.bluetoothLeScanner }

    private val bluetoothDataHandlerThread = HandlerThread("BluetoothDataHandlerThread")
    private lateinit var bluetoothDataHandler: Handler

    private var gatt: BluetoothGatt? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.device?.let { device ->
                checkPermission()
                Log.d("Bluetooth Scan", "Found device: ${device.name} with address: ${device.address}")
                if (device.name == "ESP32") {
                    Log.d("Bluetooth Scan", "Connecting to ESP32")
                    connectToDevice(device)
                }
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            checkPermission()
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d("Bluetooth GATT", "Connected")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d("Bluetooth", "Disconnected")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val service = gatt.getService(serviceUuid)
            val characteristic = service.getCharacteristic(characteristicUuid)
            checkPermission()

            gatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(UUID.fromString("000000ff-0000-1000-8000-00805f9b34fb"))
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
            Log.d("Bluetooth GATT", "Characteristic enabled")
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            bluetoothDataHandler.post {
                val data = characteristic.value
                val receivedDouble = bytesToDouble(data)
                val formattedDouble = String.format("%.3f", receivedDouble)

                runOnUiThread {
                    currentData = receivedDouble
                    if(receivedDouble>Vmax){
                        Vmax = receivedDouble
                        maxDataTextView.text = String.format("%.3f", Vmax)
                    }
                    dataTextView.text = formattedDouble
                }
            }
        }
    }

    private fun bytesToDouble(bytes: ByteArray): Double {
        try {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            return buffer.double
        } catch (e: Exception) {
            e.printStackTrace()
            return 0.0
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bluetoothDataHandlerThread.start()
        bluetoothDataHandler = Handler(bluetoothDataHandlerThread.looper)

        checkPermission()
        startScan()

        val staticLabelsFormatter = StaticLabelsFormatter(graph)
        staticLabelsFormatter.setHorizontalLabels(arrayOf("", "", "", "", ""))
//        staticLabelsFormatter.setVerticalLabels(arrayOf("", "", "", "", ""))
        graph.gridLabelRenderer.labelFormatter = staticLabelsFormatter

        setupButton()
    }

    val series = LineGraphSeries<DataPoint>()
    var reset = false

    private fun updateGraphWithBluetoothData() {
        if (isUpdatingGraph) {
            val mHandler = Handler(Looper.getMainLooper())
            val mTimer: Runnable

            var x = series.highestValueX + 0.1



            series.color = Color.WHITE
            graph.addSeries(series)
            //var count = 0

            mTimer = object : Runnable {
                override fun run() {
                    if (isUpdatingGraph) {
                        if (x >= 50.0) {
                            x = 0.0
                            series.resetData(arrayOf<DataPoint>())
                        }
                        if (reset){
                            Log.d("reset", "reset to 0")
                            x=0.0
                            series.resetData(arrayOf<DataPoint>())
                            reset = false
                        }

                        val y = currentData

                        val dataPoint = DataPoint(x, y)

                        series.appendData(dataPoint, false, 500)

                        if (scale) {
                            Log.d("scale", "Scale = true")
                            graph.viewport.setMinX(0.0)
                            graph.viewport.setMaxX(50.0)
                            graph.viewport.setMinY(5.0)
                            graph.viewport.setMaxY(0.0)
                        }

                        x += 0.1

                    }
                    mHandler.postDelayed(this, 25)
                }
            }
            mHandler.postDelayed(mTimer, 25)
        } else {
            Toast.makeText(this, "already running", Toast.LENGTH_SHORT).show()
        }
    }


    private fun setupButton() {
        startButton.setSafeOnClickListener{
            Toast.makeText(this,"start update",Toast.LENGTH_SHORT).show()
            isUpdatingGraph = true
            updateGraphWithBluetoothData()
        }

        stopButton.setSafeOnClickListener{
            Toast.makeText(this,"stop update",Toast.LENGTH_SHORT).show()
            isUpdatingGraph = false
            Log.d("stopbtn", "Stop button clicked")
        }

        scaleButton.setSafeOnClickListener {
            scale = scale != true
        }

        resetButton.setSafeOnClickListener{
            Toast.makeText(this,"reset data",Toast.LENGTH_SHORT).show()
            isUpdatingGraph = true
            reset =true
            series.resetData(arrayOf<DataPoint>())
        }

    }

    var scale = false

    override fun onResume() {
        super.onResume()
        checkPermission()
        //startScan()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        isUpdatingGraph = true
    }

    override fun onPause() {
        super.onPause()
        isUpdatingGraph = false
    }

    override fun onDestroy() {
        super.onDestroy()
        checkPermission()
        bluetoothLeScanner.stopScan(scanCallback)
        gatt?.disconnect()
        gatt?.close()
        isUpdatingGraph = false
        bluetoothDataHandlerThread.quitSafely()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }


    private fun startScan() {
        checkPermission()
        val scanFilters = mutableListOf<android.bluetooth.le.ScanFilter>()
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val uuid = UUID.fromString("000000ff-0000-1000-8000-00805f9b34fb") // UUID dịch vụ BLE cần quét
        val parcelUuid = ParcelUuid(uuid)
        val scanFilter = android.bluetooth.le.ScanFilter.Builder()
            .setServiceUuid(parcelUuid)
            .build()
        scanFilters.add(scanFilter)

        bluetoothLeScanner.startScan(scanFilters, scanSettings, scanCallback)
        Log.d("Bluetooth Scan", "Scanning started")
    }

    private fun connectToDevice(device: BluetoothDevice) {
        checkPermission()
        gatt = device.connectGatt(this, true, gattCallback)
        Log.d("Bluetooth GATT", "Connecting to GATT server")
    }

    fun View.setSafeOnClickListener(onSafeClick: (View) -> Unit) {

        val safeClickListener = SafeClickListener {
            onSafeClick(it)
        }
        setOnClickListener(safeClickListener)
    }

    class SafeClickListener(
        private var defaultInterval: Int = 1000,
        private val onSafeCLick: (View) -> Unit
    ) : View.OnClickListener {

        private var lastTimeClicked: Long = 0

        override fun onClick(v: View) {
            if (SystemClock.elapsedRealtime() - lastTimeClicked < defaultInterval) {
                return
            }
            lastTimeClicked = SystemClock.elapsedRealtime()
            onSafeCLick(v)
        }
    }

    private fun checkPermission(): Boolean {
        val permissions = arrayOf(
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        return if (ContextCompat.checkSelfPermission(this.applicationContext, permissions[0]) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this.applicationContext, permissions[1]) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this.applicationContext, permissions[2]) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this.applicationContext, permissions[3]) == PackageManager.PERMISSION_GRANTED
        ) {
            Log.d("permission", "Permission checked")
            true
        } else {
            ActivityCompat.requestPermissions(this, permissions, 1)
            false
        }
    }
}