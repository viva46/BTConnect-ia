package com.niotron.btconnect;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import com.google.appinventor.components.annotations.DesignerComponent;
import com.google.appinventor.components.annotations.DesignerProperty;
import com.google.appinventor.components.annotations.PropertyCategory;
import com.google.appinventor.components.annotations.SimpleEvent;
import com.google.appinventor.components.annotations.SimpleFunction;
import com.google.appinventor.components.annotations.SimpleObject;
import com.google.appinventor.components.annotations.SimpleProperty;
import com.google.appinventor.components.annotations.UsesPermissions;
import com.google.appinventor.components.common.ComponentCategory;
import com.google.appinventor.components.common.PropertyTypeConstants;
import com.google.appinventor.components.runtime.AndroidNonvisibleComponent;
import com.google.appinventor.components.runtime.ComponentContainer;
import com.google.appinventor.components.runtime.EventDispatcher;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@SimpleObject
@DesignerComponent(
        version = 1,
        description = "Extensión para conectar dos móviles vía Bluetooth y enviar datos numéricos",
        category = ComponentCategory.EXTENSION,
        nonVisible = true,
        iconName = "aiwebres/icon.png")
@UsesPermissions(permissionNames = 
        "android.permission.BLUETOOTH, " +
        "android.permission.BLUETOOTH_ADMIN, " +
        "android.permission.ACCESS_FINE_LOCATION")
public class BTConnect extends AndroidNonvisibleComponent {

    private static final String TAG = "BTConnect";
    private static final String APP_NAME = "BTConnect";
    private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    
    private Context context;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket connectedSocket;
    private ConnectedThread connectedThread;
    private AcceptThread acceptThread;
    
    private boolean isConnected = false;
    private String myMobileNumber = "";
    private Map<String, String> deviceNumberMap = new HashMap<>();

    public BTConnect(ComponentContainer container) {
        super(container.$form());
        this.context = container.$context();
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        
        // Registrar receptor para eventos Bluetooth
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        context.registerReceiver(bluetoothReceiver, filter);
    }

    @SimpleFunction(description = "Inicializa la conexión Bluetooth")
    public void Initialize() {
        if (bluetoothAdapter == null) {
            Log.e(TAG, "El dispositivo no soporta Bluetooth");
            return;
        }
        
        if (bluetoothAdapter.isEnabled()) {
            BluetoothEnabled();
        } else {
            BluetoothDisabled();
        }
    }

    @SimpleFunction(description = "Establece el número de móvil para identificación")
    public void SetMobileNumber(String mobileNumber) {
        this.myMobileNumber = mobileNumber;
    }

    @SimpleProperty(category = PropertyCategory.BEHAVIOR)
    public String MyMobileNumber() {
        return myMobileNumber;
    }

    @DesignerProperty(editorType = PropertyTypeConstants.PROPERTY_TYPE_STRING, defaultValue = "")
    @SimpleProperty
    public void MyMobileNumber(String number) {
        this.myMobileNumber = number;
    }

    @SimpleProperty(category = PropertyCategory.BEHAVIOR)
    public boolean IsBluetoothEnabled() {
        return bluetoothAdapter != null && bluetoothAdapter.isEnabled();
    }

    @SimpleProperty(category = PropertyCategory.BEHAVIOR)
    public boolean IsConnected() {
        return isConnected;
    }

    @SimpleFunction(description = "Comienza a buscar dispositivos Bluetooth cercanos")
    public void StartScanning() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            return;
        }
        
        // Verificar permisos en Android 6.0+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "Se requiere permiso ACCESS_FINE_LOCATION para buscar dispositivos");
                return;
            }
        }
        
        // Iniciar la búsqueda
        if (bluetoothAdapter.isDiscovering()) {
            bluetoothAdapter.cancelDiscovery();
        }
        
        bluetoothAdapter.startDiscovery();
        
        // Iniciar hilo de aceptación
        if (acceptThread == null) {
            acceptThread = new AcceptThread();
            acceptThread.start();
        }
    }

    @SimpleFunction(description = "Detiene la búsqueda de dispositivos")
    public void StopScanning() {
        if (bluetoothAdapter != null && bluetoothAdapter.isDiscovering()) {
            bluetoothAdapter.cancelDiscovery();
        }
    }

    @SimpleFunction(description = "Conecta con un dispositivo específico")
    public void ConnectToDevice(String deviceAddress) {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            ConnectionFailed("Bluetooth no disponible");
            return;
        }
        
        // Detener descubrimiento si está activo
        if (bluetoothAdapter.isDiscovering()) {
            bluetoothAdapter.cancelDiscovery();
        }
        
        try {
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(deviceAddress);
            ConnectThread connectThread = new ConnectThread(device);
            connectThread.start();
        } catch (Exception e) {
            ConnectionFailed("Error al conectar: " + e.getMessage());
        }
    }

    @SimpleFunction(description = "Desconecta del dispositivo actual")
    public void DisconnectFromDevice() {
        if (connectedThread != null) {
            connectedThread.cancel();
            connectedThread = null;
        }
        
        if (connectedSocket != null) {
            try {
                connectedSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "Error al cerrar el socket", e);
            }
            connectedSocket = null;
        }
        
        isConnected = false;
        DisconnectionOccurred();
    }

    @SimpleFunction(description = "Envía datos numéricos al dispositivo conectado")
    public void SendNumericData(double number) {
        if (!isConnected || connectedThread == null) {
            return;
        }
        
        // Crear mensaje con número de móvil y dato numérico
        String message = myMobileNumber + ":" + number;
        connectedThread.write(message.getBytes());
    }

    @SimpleFunction(description = "Obtiene el número de móvil del dispositivo conectado")
    public String GetConnectedDeviceNumber() {
        if (!isConnected || connectedSocket == null) {
            return "";
        }
        
        String address = connectedSocket.getRemoteDevice().getAddress();
        return deviceNumberMap.getOrDefault(address, "");
    }

    @SimpleEvent(description = "Se activa cuando el Bluetooth está habilitado")
    public void BluetoothEnabled() {
        EventDispatcher.dispatchEvent(this, "BluetoothEnabled");
    }

    @SimpleEvent(description = "Se activa cuando el Bluetooth está deshabilitado")
    public void BluetoothDisabled() {
        EventDispatcher.dispatchEvent(this, "BluetoothDisabled");
    }

    @SimpleEvent(description = "Se activa cuando se descubre un dispositivo")
    public void DeviceDiscovered(String deviceName, String deviceAddress) {
        EventDispatcher.dispatchEvent(this, "DeviceDiscovered", deviceName, deviceAddress);
    }

    @SimpleEvent(description = "Se activa cuando se establece una conexión")
    public void ConnectionEstablished(String deviceAddress) {
        EventDispatcher.dispatchEvent(this, "ConnectionEstablished", deviceAddress);
    }

    @SimpleEvent(description = "Se activa cuando falla una conexión")
    public void ConnectionFailed(String errorMessage) {
        EventDispatcher.dispatchEvent(this, "ConnectionFailed", errorMessage);
    }

    @SimpleEvent(description = "Se activa cuando se reciben datos numéricos")
    public void DataReceived(String senderNumber, double numericData) {
        EventDispatcher.dispatchEvent(this, "DataReceived", senderNumber, numericData);
    }

    @SimpleEvent(description = "Se activa cuando se produce una desconexión")
    public void DisconnectionOccurred() {
        EventDispatcher.dispatchEvent(this, "DisconnectionOccurred");
    }

    // Receptor de transmisión para eventos Bluetooth
    private final BroadcastReceiver bluetoothReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null) {
                    DeviceDiscovered(device.getName() != null ? device.getName() : "Desconocido", device.getAddress());
                }
            } else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                
                if (state == BluetoothAdapter.STATE_ON) {
                    BluetoothEnabled();
                } else if (state == BluetoothAdapter.STATE_OFF) {
                    BluetoothDisabled();
                }
            }
        }
    };

    // Hilo para aceptar conexiones entrantes
    private class AcceptThread extends Thread {
        private final BluetoothServerSocket serverSocket;

        public AcceptThread() {
            BluetoothServerSocket tmp = null;
            try {
                tmp = bluetoothAdapter.listenUsingRfcommWithServiceRecord(APP_NAME, MY_UUID);
            } catch (IOException e) {
                Log.e(TAG, "Error al crear ServerSocket", e);
            }
            serverSocket = tmp;
        }

        public void run() {
            BluetoothSocket socket = null;
            
            while (true) {
                try {
                    socket = serverSocket.accept();
                } catch (IOException e) {
                    Log.e(TAG, "Error al aceptar conexión", e);
                    break;
                }
                
                if (socket != null) {
                    // Conexión aceptada
                    connectedSocket = socket;
                    
                    // Iniciar gestión de la conexión
                    manageConnectedSocket(socket);
                    
                    try {
                        serverSocket.close();
                    } catch (IOException e) {
                        Log.e(TAG, "Error al cerrar ServerSocket", e);
                    }
                    break;
                }
            }
        }

        public void cancel() {
            try {
                serverSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "Error al cerrar ServerSocket", e);
            }
        }
    }

    // Hilo para iniciar conexión saliente
    private class ConnectThread extends Thread {
        private final BluetoothSocket socket;
        private final BluetoothDevice device;

        public ConnectThread(BluetoothDevice device) {
            this.device = device;
            BluetoothSocket tmp = null;
            
            try {
                tmp = device.createRfcommSocketToServiceRecord(MY_UUID);
            } catch (IOException e) {
                Log.e(TAG, "Error al crear socket", e);
            }
            socket = tmp;
        }

        public void run() {
            try {
                socket.connect();
                
                // Conexión exitosa
                connectedSocket = socket;
                
                // Gestionar la conexión
                manageConnectedSocket(socket);
                
            } catch (IOException connectException) {
                try {
                    socket.close();
                } catch (IOException closeException) {
                    Log.e(TAG, "Error al cerrar socket", closeException);
                }
                
                // Notificar fallo de conexión
                form.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        ConnectionFailed("Error al conectar: " + connectException.getMessage());
                    }
                });
                return;
            }
            
            // Notificar conexión establecida
            form.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    ConnectionEstablished(device.getAddress());
                }
            });
        }

        public void cancel() {
            try {
                socket.close();
            } catch (IOException e) {
                Log.e(TAG, "Error al cerrar socket", e);
            }
        }
    }

    // Gestionar socket conectado
    private void manageConnectedSocket(BluetoothSocket socket) {
        isConnected = true;
        connectedThread = new ConnectedThread(socket);
        connectedThread.start();
        
        // Enviar número de móvil
        String initMessage = "MOBILE:" + myMobileNumber;
        connectedThread.write(initMessage.getBytes());
    }

    // Hilo para gestionar conexión establecida
    private class ConnectedThread extends Thread {
        private final BluetoothSocket socket;
        private final InputStream inputStream;
        private final OutputStream outputStream;

        public ConnectedThread(BluetoothSocket socket) {
            this.socket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;
            
            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
                Log.e(TAG, "Error al crear streams", e);
            }
            
            inputStream = tmpIn;
            outputStream = tmpOut;
        }

        public void run() {
            byte[] buffer = new byte[1024];
            int bytes;
            StringBuilder messageBuffer = new StringBuilder();
            
            while (true) {
                try {
                    bytes = inputStream.read(buffer);
                    String received = new String(buffer, 0, bytes);
                    messageBuffer.append(received);
                    
                    // Procesar mensajes completos terminados en nueva línea
                    processMessages(messageBuffer);
                    
                } catch (IOException e) {
                    // Conexión perdida
                    isConnected = false;
                    form.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            DisconnectionOccurred();
                        }
                    });
                    break;
                }
            }
        }

        // Procesar mensajes recibidos
        private void processMessages(StringBuilder buffer) {
            String content = buffer.toString();
            int newlinePos;
            
            while ((newlinePos = content.indexOf('\n')) != -1) {
                final String message = content.substring(0, newlinePos);
                content = content.substring(newlinePos + 1);
                
                // Procesar el mensaje
                form.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (message.startsWith("MOBILE:")) {
                            // Mensaje de identificación
                            String mobileNumber = message.substring(7);
                            deviceNumberMap.put(socket.getRemoteDevice().getAddress(), mobileNumber);
                        } else if (message.contains(":")) {
                            // Dato numérico
                            try {
                                String[] parts = message.split(":", 2);
                                final String senderNumber = parts[0];
                                final double numericData = Double.parseDouble(parts[1]);
                                DataReceived(senderNumber, numericData);
                            } catch (Exception e) {
                                Log.e(TAG, "Error al procesar datos: " + message, e);
                            }
                        }
                    }
                });
            }
            
            // Actualizar buffer con contenido restante
            buffer.setLength(0);
            buffer.append(content);
        }

        public void write(byte[] bytes) {
            try {
                // Añadir salto de línea para marcar fin de mensaje
                byte[] messageWithNewline = new byte[bytes.length + 1];
                System.arraycopy(bytes, 0, messageWithNewline, 0, bytes.length);
                messageWithNewline[bytes.length] = '\n';
                
                outputStream.write(messageWithNewline);
            } catch (IOException e) {
                Log.e(TAG, "Error al enviar datos", e);
            }
        }

        public void cancel() {
            try {
                socket.close();
            } catch (IOException e) {
                Log.e(TAG, "Error al cerrar socket", e);
            }
        }
    }
}
