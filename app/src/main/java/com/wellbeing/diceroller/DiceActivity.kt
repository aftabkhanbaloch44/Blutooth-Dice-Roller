package com.wellbeing.diceroller

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.ProgressDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout.OnRefreshListener
import com.wellbeing.diceroller.databinding.ActivityDiceBinding
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Random
import java.util.UUID

class DiceActivity : AppCompatActivity() {

    private val binding by lazy { ActivityDiceBinding.inflate(layoutInflater) }

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var pairedDevice: BluetoothDevice? = null

    private var isSender = true

    private var connectedThread: ConnectedThread? = null
    private var acceptThread: AcceptThread? = null
    private var connectThread: ConnectThread? = null

    private var progressDialog: ProgressDialog? = null

    private var isAppInForeground = true

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        createNotificationChannel()

        checkAndRequestPermissions()

        proceedWithBluetoothConnection()

        binding.btnRollDice.setOnClickListener(View.OnClickListener { v: View? ->
            if (isSender && connectedThread != null) {
                val roll = Random().nextInt(6) + 1
                binding.txtDiceValue.text = roll.toString()
                binding.txtDiceStatus.text = "Sent: $roll"
                connectedThread?.write(("DICE:$roll").toByteArray())
            } else {
                closeConnections()
                startCommunication()
            }
        })

        binding.btnToggleMode.setOnClickListener(View.OnClickListener { v: View? ->
            if (connectedThread != null) {
                connectedThread?.write("MODE_SWITCH_REQUEST".toByteArray())
            } else {
                switchMode()
                closeConnections()
                startCommunication()
            }
        })

        binding.refreshLayout.setOnRefreshListener(OnRefreshListener {
            setConnectionStatus("Refreshing devices...")
            proceedWithBluetoothConnection()
        })

        updateConnectionStatusNoDevice()
    }

    private fun setConnectionStatus(status: String?) {
        runOnUiThread(Runnable {
            binding.txtViewConnectedDevice.text = status
        })
    }


    override fun onResume() {
        super.onResume()
        isAppInForeground = true
    }

    override fun onPause() {
        super.onPause()
        isAppInForeground = false
    }


    private fun createNotificationChannel() {
        val name: CharSequence = "Dice Roll Channel"
        val description = "Notifications for dice roll received"
        val importance = NotificationManager.IMPORTANCE_DEFAULT
        val channel = NotificationChannel(CHANNEL_ID, name, importance)
        channel.description = description
        val notificationManager =
            getSystemService<NotificationManager?>(NotificationManager::class.java)
        notificationManager?.createNotificationChannel(channel)
    }

    private fun showDiceRollNotification(rollValue: String?) {
        val intent = Intent(this, DiceActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder: NotificationCompat.Builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.dice, NotificationCompat.PRIORITY_MAX)
            .setContentTitle("Dice Rolled: $rollValue")
            .setContentText("Your partner rolled the dice.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager?
        notificationManager?.notify(NOTIFICATION_ID, builder.build())
    }

    private fun proceedWithBluetoothConnection() {

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            checkAndRequestPermissions()
            return
        }

        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth not supported", Toast.LENGTH_LONG).show()
            binding.refreshLayout.isRefreshing = false
            updateConnectionStatusNoDevice()
            return
        }

        if (bluetoothAdapter?.isEnabled == false) {
            Toast.makeText(this, "Bluetooth not enabled", Toast.LENGTH_LONG).show()
            binding.refreshLayout.isRefreshing = false
            updateConnectionStatusNoDevice()
            return
        } else {
            Toast.makeText(this, "Bluetooth enabled", Toast.LENGTH_LONG).show()
            showPairedDevicesDialog()
        }

        pairedDevice = this.firstPairedDevice
        if (pairedDevice == null) {
            Toast.makeText(this, "No paired device found", Toast.LENGTH_LONG).show()
            binding.refreshLayout.isRefreshing = false
            updateConnectionStatusNoDevice()
        } else {
            Toast.makeText(
                this,
                "Paired device found: " + pairedDevice?.name,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun showPairedDevicesDialog() {

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            checkAndRequestPermissions()
            return
        }

        val pairedDevices = bluetoothAdapter?.bondedDevices
        val deviceNames: MutableList<String> = ArrayList<String>()
        for (device in pairedDevices ?: arrayListOf()) {
            deviceNames.add(device.name + "\n" + device.address)
        }
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Select a device")
            .setItems(
                deviceNames.toTypedArray<String?>(),
                DialogInterface.OnClickListener { dialog: DialogInterface?, which: Int ->
                    val selectedDevice = deviceNames[which]
                    val deviceAddress: String? =
                        selectedDevice.split("\n".toRegex()).dropLastWhile { it.isEmpty() }
                            .toTypedArray()[1]
                    pairedDevice = bluetoothAdapter?.getRemoteDevice(deviceAddress)
                    startCommunication()
                })
            .setNegativeButton(
                "Cancel",
                DialogInterface.OnClickListener { dialog: DialogInterface?, which: Int ->
                    dialog?.dismiss()
                    binding.refreshLayout.isRefreshing = false
                    updateConnectionStatusNoDevice()
                })
            .show()
        binding.refreshLayout.isRefreshing = false
    }

    private fun checkAndRequestPermissions() {
        val permissionsNeeded: MutableList<String?> = ArrayList<String?>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_SCAN
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_ADVERTISE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
        } else {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH)
            }
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_ADMIN
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_ADMIN)
            }
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        if (!permissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsNeeded.toTypedArray<String?>(),
                PERMISSION_REQUEST_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_REQUEST_CODE) {
            for (i in permissions.indices) {
                if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    proceedWithBluetoothConnection()
                } else {
                    Toast.makeText(this, "Some permissions are denied", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode == RESULT_OK) {
                showPairedDevicesDialog()
            } else {
                Toast.makeText(this, "Bluetooth not enabled", Toast.LENGTH_SHORT).show()
                updateConnectionStatusNoDevice()
            }
        }
    }

    private val firstPairedDevice: BluetoothDevice?
        get() {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
            ) {

                val pairedDevices = bluetoothAdapter?.bondedDevices
                for (device in pairedDevices ?: arrayListOf()) {
                    return device
                }
            }
            return null
        }

    private fun startCommunication() {
        if (pairedDevice == null) {
            binding.refreshLayout.isRefreshing = false
            updateConnectionStatusNoDevice()
            return
        }
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            checkAndRequestPermissions()
            return
        }
        setConnectionStatus("Connecting to device: " + pairedDevice?.name + " ...")
        showProgressDialog(if (isSender) "Connecting to receiver..." else "Waiting for connection...")
        if (isSender) {
            connectThread = pairedDevice?.let { ConnectThread(it) }
            connectThread?.start()
        } else {
            acceptThread = AcceptThread()
            acceptThread?.start()
        }
        updateUI()
    }

    private fun switchMode() {
        isSender = !isSender
        updateUI()
    }

    private fun closeConnections() {
        if (connectedThread != null) {
            connectedThread?.cancel()
            connectedThread = null
        }
        if (acceptThread != null) {
            acceptThread?.cancel()
            acceptThread = null
        }
        if (connectThread != null) {
            connectThread?.cancel()
            connectThread = null
        }
        Log.d(TAG, "Connections closed")
        updateConnectionStatusNoDevice()
    }

    @SuppressLint("SetTextI18n")
    private fun updateUI() {
        binding.btnRollDice.isEnabled = isSender
        binding.btnToggleMode.text = "Switch to " + (if (isSender) "Receiver" else "Sender")
        binding.txtDiceStatus.text = if (isSender) "Ready to roll" else "Waiting for roll..."
    }

    private fun showProgressDialog(message: String?) {
        runOnUiThread(Runnable {
            if (progressDialog == null) {
                progressDialog = ProgressDialog(this@DiceActivity)
                progressDialog?.setCancelable(true)
            }
            progressDialog?.setMessage(message)
            progressDialog?.show()
        })
    }

    private fun hideProgressDialog() {
        runOnUiThread(Runnable {
            if (progressDialog != null) {
                progressDialog?.dismiss()
            }
        })
    }

    private fun updateConnectionStatus(status: String?) {
        runOnUiThread(Runnable { binding.txtViewConnectedDevice.text = status })
    }

    @SuppressLint("SetTextI18n")
    private fun updateConnectionStatusNoDevice() {
        runOnUiThread(Runnable {
            binding.txtViewConnectedDevice.text = "No device connected. Pull to refresh."
        })
    }

    private inner class AcceptThread : Thread() {
        private val serverSocket: BluetoothServerSocket?

        init {
            var tmp: BluetoothServerSocket? = null
            try {
                if (ActivityCompat.checkSelfPermission(
                        applicationContext,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    tmp = bluetoothAdapter?.listenUsingRfcommWithServiceRecord("DiceGame", APP_UUID)
                }

            } catch (e: IOException) {
                Log.e(TAG, "AcceptThread: ", e)
            }
            serverSocket = tmp
        }

        override fun run() {
            try {
                val socket = serverSocket?.accept()
                if (socket != null) {
                    if (ActivityCompat.checkSelfPermission(
                            applicationContext,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        checkAndRequestPermissions()
                        return
                    }
                    Log.d(
                        TAG,
                        "AcceptThread: connection accepted from " + socket.remoteDevice.name
                    )
                    updateConnectionStatus("Connected to: " + socket.remoteDevice.name)
                    connectedThread = ConnectedThread(socket)
                    connectedThread?.start()
                    serverSocket.close()
                    hideProgressDialog()
                    runOnUiThread(Runnable {
                        if (binding.refreshLayout.isRefreshing) binding.refreshLayout.isRefreshing =
                            false
                    })
                }
            } catch (e: IOException) {
                Log.e(TAG, "AcceptThread run: ", e)
                updateConnectionStatusNoDevice()
                runOnUiThread(Runnable {
                    if (binding.refreshLayout.isRefreshing) binding.refreshLayout.isRefreshing =
                        false
                })
            }
        }

        fun cancel() {
            try {
                serverSocket?.close()
            } catch (e: IOException) {
                Log.e(TAG, "AcceptThread cancel: ", e)
            }
        }
    }

    private inner class ConnectThread(device: BluetoothDevice) : Thread() {
        private val threadSocket: BluetoothSocket?

        init {
            var tmp: BluetoothSocket? = null
            try {
                if (ActivityCompat.checkSelfPermission(
                        applicationContext,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    tmp = device.createRfcommSocketToServiceRecord(APP_UUID)
                }

            } catch (e: IOException) {
                Log.e(TAG, "ConnectThread: ", e)
            }
            threadSocket = tmp
        }

        override fun run() {
            if (ActivityCompat.checkSelfPermission(
                    applicationContext,
                    Manifest.permission.BLUETOOTH_SCAN
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                checkAndRequestPermissions()
                return
            }
            bluetoothAdapter?.cancelDiscovery()
            try {
                Log.d(
                    TAG,
                    "ConnectThread: Attempting connection to " + threadSocket?.remoteDevice
                        ?.name
                )
                updateConnectionStatus(
                    "Connecting to: " + threadSocket?.remoteDevice?.name
                )
                threadSocket?.connect()
                Log.d(TAG, "ConnectThread: Connection successful")
                updateConnectionStatus(
                    "Connected to: " + threadSocket?.remoteDevice?.name
                )
                connectedThread = threadSocket?.let { ConnectedThread(it) }
                connectedThread?.start()
                connectedThread?.write(("ROLE:" + (if (isSender) "SENDER" else "RECEIVER")).toByteArray())
                hideProgressDialog()
                runOnUiThread(Runnable {
                    if (binding.refreshLayout.isRefreshing) binding.refreshLayout.isRefreshing =
                        false
                })
            } catch (e: IOException) {
                Log.e(TAG, "ConnectThread run: Connection failed", e)
                updateConnectionStatusNoDevice()
                runOnUiThread(Runnable {
                    if (binding.refreshLayout.isRefreshing) binding.refreshLayout.isRefreshing =
                        false
                })
                try {
                    threadSocket?.close()
                } catch (closeException: IOException) {
                    Log.e(TAG, "ConnectThread close failed: ", closeException)
                }
                hideProgressDialog()
            }
        }

        fun cancel() {
            try {
                threadSocket?.close()
            } catch (e: IOException) {
                Log.e(TAG, "ConnectThread cancel: ", e)
            }
        }
    }

    private inner class ConnectedThread(private val threadSocket: BluetoothSocket) : Thread() {
        private val inStream: InputStream?
        private val outStream: OutputStream?

        init {
            var tmpIn: InputStream? = null
            var tmpOut: OutputStream? = null
            try {
                tmpIn = threadSocket.inputStream
                tmpOut = threadSocket.outputStream
            } catch (e: IOException) {
                Log.e(TAG, "ConnectedThread init: ", e)
            }
            inStream = tmpIn
            outStream = tmpOut
        }

        override fun run() {
            val buffer = ByteArray(1024)
            var bytes: Int?

            while (true) {
                try {
                    bytes = inStream?.read(buffer)
                    bytes?.let {
                        val message = String(buffer, 0, it)
                        Log.d(TAG, "Received: $message")
                        handleMessage(message)
                    }

                } catch (e: IOException) {
                    Log.e(TAG, "Disconnected", e)
                    updateConnectionStatusNoDevice()
                    break
                }
            }
        }

        @SuppressLint("SetTextI18n")
        fun handleMessage(message: String) {
            runOnUiThread(Runnable {
                if (message.startsWith("DICE:")) {
                    hideProgressDialog()
                    val rollValue = message.substring(5)
                    binding.txtDiceValue.text = rollValue
                    binding.txtDiceStatus.text = "Received: $rollValue"
                    // Show notification if app in background
                    if (!isAppInForeground) {
                        showDiceRollNotification(rollValue)
                    }
                } else if (message.startsWith("ROLE:")) {
                    hideProgressDialog()
                    // Note: The role messaging is reversed intentionally (the other end sends its role)
                    isSender = message.substring(5) == "RECEIVER"
                    updateUI()
                } else if (message == "MODE_SWITCH_REQUEST") {
                    switchMode()
                    write("MODE_SWITCH_ACK".toByteArray())
                    closeConnections()
                    startCommunication()
                } else if (message == "MODE_SWITCH_ACK") {
                    switchMode()
                    closeConnections()
                    startCommunication()
                }
            })
        }

        fun write(bytes: ByteArray?) {
            if (outStream == null) {
                Log.e(TAG, "Write failed: Output stream is null")
                return
            }
            try {
                outStream.write(bytes)
            } catch (e: IOException) {
                Log.e(TAG, "Write failed", e)
            }
        }

        fun cancel() {
            try {
                threadSocket.close()
            } catch (e: IOException) {
                Log.e(TAG, "Cancel failed", e)
            }
        }
    }

    companion object {
        private const val TAG = "<---DiceActivity--->"
        private val APP_UUID: UUID? = UUID.fromString("a60f35f0-b93a-11de-8a39-08002009c666")
        private const val PERMISSION_REQUEST_CODE = 999
        private const val REQUEST_ENABLE_BT = 777
        private const val CHANNEL_ID = "dice_roll_channel"
        private const val NOTIFICATION_ID = 1
    }
}