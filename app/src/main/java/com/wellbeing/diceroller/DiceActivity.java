package com.wellbeing.diceroller;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public class DiceActivity extends AppCompatActivity {

    private static final String TAG = "<---DiceActivity--->";
    private static final UUID APP_UUID = UUID.fromString("a60f35f0-b93a-11de-8a39-08002009c666");
    private static final int PERMISSION_REQUEST_CODE = 999;
    private static final int REQUEST_ENABLE_BT = 777;

    private SwipeRefreshLayout refreshLayout;
    private TextView txtDiceValue;
    private TextView txtDiceStatus;
    private Button btnRollDice;
    private Button btnToggleMode;

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothDevice pairedDevice;

    private boolean isSender = true;

    private ConnectedThread connectedThread;
    private AcceptThread acceptThread;
    private ConnectThread connectThread;

    private ProgressDialog progressDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dice);

        txtDiceValue = findViewById(R.id.txtDiceValue);
        txtDiceStatus = findViewById(R.id.txtDiceStatus);
        btnRollDice = findViewById(R.id.btnRollDice);
        btnToggleMode = findViewById(R.id.btnToggleMode);
        refreshLayout = findViewById(R.id.refreshLayout);

        checkAndRequestPermissions();

        proceedWithBluetoothConnection();

        btnRollDice.setOnClickListener(v -> {
            if (isSender && connectedThread != null) {
                int roll = new Random().nextInt(6) + 1;
                txtDiceValue.setText(String.valueOf(roll));
                txtDiceStatus.setText("Sent: " + roll);
                connectedThread.write(("DICE:" + roll).getBytes());
            } else {
                closeConnections();
                startCommunication();
            }
        });

        btnToggleMode.setOnClickListener(v -> {
            if (connectedThread != null) {
                connectedThread.write("MODE_SWITCH_REQUEST".getBytes());
            } else {
                switchMode();
                closeConnections();
                startCommunication();
            }
        });

        refreshLayout.setOnRefreshListener(this::proceedWithBluetoothConnection);


        startCommunication();
    }


    private void proceedWithBluetoothConnection() {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth not supported", Toast.LENGTH_LONG).show();
            refreshLayout.setRefreshing(false);
            return;
        } else
            Toast.makeText(this, "Bluetooth adapter supported", Toast.LENGTH_LONG).show();

        if (!bluetoothAdapter.isEnabled()) {
            Toast.makeText(this, "Bluetooth not enabled", Toast.LENGTH_LONG).show();
            refreshLayout.setRefreshing(false);
            return;
        } else {
            Toast.makeText(this, "Bluetooth enabled", Toast.LENGTH_LONG).show();
            showPairedDevicesDialog();
        }

        pairedDevice = getFirstPairedDevice();
        if (pairedDevice == null) {
            Toast.makeText(this, "No paired device found", Toast.LENGTH_LONG).show();
            refreshLayout.setRefreshing(false);
        } else
            Toast.makeText(this, "Paired device found: " + pairedDevice.getName(), Toast.LENGTH_LONG).show();

    }

    private void showPairedDevicesDialog() {
        Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
        List<String> deviceNames = new ArrayList<>();
        for (BluetoothDevice device : pairedDevices) {
            deviceNames.add(device.getName() + "\n" + device.getAddress());
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Select a device")
                .setItems(deviceNames.toArray(new String[0]), (dialog, which) -> {
                    String selectedDevice = deviceNames.get(which);
                    String deviceAddress = selectedDevice.split("\n")[1];
                    pairedDevice = bluetoothAdapter.getRemoteDevice(deviceAddress);
                    startCommunication();
                })
                .setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss())
                .show();
        refreshLayout.setRefreshing(false);
    }

    private void checkAndRequestPermissions() {
        List<String> permissionsNeeded = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(android.Manifest.permission.BLUETOOTH_SCAN);
            }
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(android.Manifest.permission.BLUETOOTH_CONNECT);
            }
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(android.Manifest.permission.BLUETOOTH_ADVERTISE);
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(android.Manifest.permission.BLUETOOTH);
            }
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_ADMIN) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(android.Manifest.permission.BLUETOOTH_ADMIN);
            }
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(android.Manifest.permission.ACCESS_FINE_LOCATION);
            }
        }

        if (!permissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    permissionsNeeded.toArray(new String[0]),
                    PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSION_REQUEST_CODE) {
            for (int i = 0; i < permissions.length; i++) {
                if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    proceedWithBluetoothConnection();
                } else {
                    Toast.makeText(this, "Some permissions are denied", Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode == RESULT_OK) {
                showPairedDevicesDialog();
            } else {
                Toast.makeText(this, "Bluetooth not enabled", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private BluetoothDevice getFirstPairedDevice() {
        Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
        for (BluetoothDevice device : pairedDevices) {
            return device;
        }
        return null;
    }

    private void startCommunication() {
        if (pairedDevice == null) {
            refreshLayout.setRefreshing(false);
            return;
        }
        showProgressDialog(isSender ? "Connecting to receiver..." : "Waiting for connection...");
        if (isSender) {
            connectThread = new ConnectThread(pairedDevice);
            connectThread.start();
        } else {
            acceptThread = new AcceptThread();
            acceptThread.start();
        }
        updateUI();
    }

    private void switchMode() {
        isSender = !isSender;
        updateUI();
    }

    private void closeConnections() {
        if (connectedThread != null) {
            connectedThread.cancel();
            connectedThread = null;
        }
        if (acceptThread != null) {
            acceptThread.cancel();
            acceptThread = null;
        }
        if (connectThread != null) {
            connectThread.cancel();
            connectThread = null;
        }
        Log.d(TAG, "Connections closed");
    }

    private void updateUI() {
        btnRollDice.setEnabled(isSender);
        btnToggleMode.setText("Switch to " + (isSender ? "Receiver" : "Sender"));
        txtDiceStatus.setText(isSender ? "Ready to roll" : "Waiting for roll...");
    }

    private void showProgressDialog(String message) {
        runOnUiThread(() -> {
            if (progressDialog == null) {
                progressDialog = new ProgressDialog(DiceActivity.this);
                progressDialog.setCancelable(true);
            }
            progressDialog.setMessage(message);
            progressDialog.show();
        });
    }

    private void hideProgressDialog() {
        runOnUiThread(() -> {
            if (progressDialog != null && progressDialog.isShowing()) {
                progressDialog.dismiss();
            }
        });
    }

    private class AcceptThread extends Thread {
        private final BluetoothServerSocket serverSocket;

        public AcceptThread() {
            BluetoothServerSocket tmp = null;
            try {
                tmp = bluetoothAdapter.listenUsingRfcommWithServiceRecord("DiceGame", APP_UUID);
            } catch (IOException e) {
                Log.e(TAG, "AcceptThread: ", e);
            }
            serverSocket = tmp;
        }

        public void run() {
            try {
                BluetoothSocket socket = serverSocket.accept();
                if (socket != null) {
                    Log.d(TAG, "AcceptThread: connection accepted from " + socket.getRemoteDevice().getName());
                    connectedThread = new ConnectedThread(socket);
                    connectedThread.start();
                    serverSocket.close();
                    hideProgressDialog();
                }
            } catch (IOException e) {
                Log.e(TAG, "AcceptThread run: ", e);
            }
        }

        public void cancel() {
            try {
                serverSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "AcceptThread cancel: ", e);
            }
        }
    }

    private class ConnectThread extends Thread {
        private final BluetoothSocket threadSocket;

        public ConnectThread(BluetoothDevice device) {
            BluetoothSocket tmp = null;
            try {
                tmp = device.createRfcommSocketToServiceRecord(APP_UUID);
            } catch (IOException e) {
                Log.e(TAG, "ConnectThread: ", e);
            }
            threadSocket = tmp;
        }

        public void run() {
            bluetoothAdapter.cancelDiscovery();
            try {
                Log.d(TAG, "ConnectThread: Attempting connection to " + threadSocket.getRemoteDevice().getName());
                threadSocket.connect();
                Log.d(TAG, "ConnectThread: Connection successful");
                connectedThread = new ConnectedThread(threadSocket);
                connectedThread.start();
                connectedThread.write(("ROLE:" + (isSender ? "SENDER" : "RECEIVER")).getBytes());
                hideProgressDialog();
            } catch (IOException e) {
                Log.e(TAG, "ConnectThread run: Connection failed", e);
                try {
                    threadSocket.close();
                } catch (IOException closeException) {
                    Log.e(TAG, "ConnectThread close failed: ", closeException);
                }
                hideProgressDialog();
            }
        }

        public void cancel() {
            try {
                threadSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "ConnectThread cancel: ", e);
            }
        }
    }

    private class ConnectedThread extends Thread {
        private final BluetoothSocket threadSocket;
        private final InputStream inStream;
        private final OutputStream outStream;

        public ConnectedThread(BluetoothSocket socket) {
            threadSocket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;
            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
                Log.e(TAG, "ConnectedThread init: ", e);
            }
            inStream = tmpIn;
            outStream = tmpOut;
        }

        public void run() {
            byte[] buffer = new byte[1024];
            int bytes;

            while (true) {
                try {
                    bytes = inStream.read(buffer);
                    String message = new String(buffer, 0, bytes);
                    Log.d(TAG, "Received: " + message);
                    handleMessage(message);
                } catch (IOException e) {
                    Log.e(TAG, "Disconnected", e);
                    break;
                }
            }
        }

        @SuppressLint("SetTextI18n")
        private void handleMessage(String message) {
            runOnUiThread(() -> {
                if (message.startsWith("DICE:")) {
                    txtDiceValue.setText(message.substring(5));
                    txtDiceStatus.setText("Received: " + message.substring(5));
                } else if (message.startsWith("ROLE:")) {
                    isSender = message.substring(5).equals("RECEIVER");
                    updateUI();
                } else if (message.equals("MODE_SWITCH_REQUEST")) {
                    switchMode();
                    write("MODE_SWITCH_ACK".getBytes());
                    closeConnections();
                    startCommunication();
                } else if (message.equals("MODE_SWITCH_ACK")) {
                    switchMode();
                    closeConnections();
                    startCommunication();
                }
            });
        }

        public void write(byte[] bytes) {
            if (outStream == null) {
                Log.e(TAG, "Write failed: Output stream is null");
                return;
            }
            try {
                outStream.write(bytes);
            } catch (IOException e) {
                Log.e(TAG, "Write failed", e);
            }
        }

        public void cancel() {
            try {
                threadSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "Cancel failed", e);
            }
        }
    }
}

