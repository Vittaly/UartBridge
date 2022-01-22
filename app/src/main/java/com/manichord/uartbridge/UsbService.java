package com.manichord.uartbridge;

import android.app.Activity;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;

import com.felhr.usbserial.CDCSerialDevice;
import com.felhr.usbserial.UsbSerialDevice;
import com.felhr.usbserial.UsbSerialInterface;

import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.Map;

import timber.log.Timber;
import android.os.Build;
import android.app.NotificationChannel;
import android.app.NotificationManager;



/**
 * Based on example from:
 * https://github.com/felHR85/SerialPortExample/blob/master/example/src/main/java/com/felhr/serialportexample/UsbService.java
 */

public class UsbService extends Service {

    private static final String ACTION_USB_PERMISSION = "com.android.example.USB_PERMISSION";
    private static final int ONGOING_NOTIFICATION_ID = UsbService.class.hashCode();

    public static final String ACTION_USB_READY = "com.felhr.connectivityservices.USB_READY";
    public static final String ACTION_USB_ATTACHED = "android.hardware.usb.action.USB_DEVICE_ATTACHED";
    public static final String ACTION_USB_DETACHED = "android.hardware.usb.action.USB_DEVICE_DETACHED";
    public static final String ACTION_USB_NOT_SUPPORTED = "com.felhr.usbservice.USB_NOT_SUPPORTED";
    public static final String ACTION_NO_USB = "com.felhr.usbservice.NO_USB";
    public static final String ACTION_USB_PERMISSION_GRANTED = "com.felhr.usbservice.USB_PERMISSION_GRANTED";
    public static final String ACTION_USB_PERMISSION_NOT_GRANTED = "com.felhr.usbservice.USB_PERMISSION_NOT_GRANTED";
    public static final String ACTION_USB_DISCONNECTED = "com.felhr.usbservice.USB_DISCONNECTED";
    public static final String ACTION_CDC_DRIVER_NOT_WORKING = "com.felhr.connectivityservices.ACTION_CDC_DRIVER_NOT_WORKING";
    public static final String ACTION_USB_DEVICE_NOT_WORKING = "com.felhr.connectivityservices.ACTION_USB_DEVICE_NOT_WORKING";
    public static final int MESSAGE_FROM_SERIAL_PORT = 0;
    public static boolean SERVICE_CONNECTED = false;

    public static final String NOTIFICATION_CHANNEL_ID_SERVICE = "com.manichord.uartbridge.UsbService";
    public static final String NOTIFICATION_CHANNEL_ID_TASK = "com.manichord.uartbridge.download_info";

    private IBinder binder = new UsbBinder();

    private Context context;
    private Handler mHandler;
    private UsbManager usbManager;
    private UsbDevice device;
    private UsbDeviceConnection connection;
    private UsbSerialDevice serialPort;
    private PrefHelper mPrefs;
    private boolean serialPortConnected;
    private SocketServer mSocketServer;

    private static final char[] HEX_ARRAY = "0123456789ABCDEF".toCharArray();
    public static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars);
    }


    private Notification getNotification (){
        
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, notificationIntent, 0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel(NOTIFICATION_CHANNEL_ID_SERVICE, "App Service", NotificationManager.IMPORTANCE_DEFAULT));
           // nm.createNotificationChannel(new NotificationChannel(NOTIFICATION_CHANNEL_ID_INFO, "Download Info", NotificationManager.IMPORTANCE_DEFAULT));
           return new Notification.Builder(context, NOTIFICATION_CHANNEL_ID_SERVICE)
           .setContentTitle(getText(R.string.app_name))
           .setContentText(getText(R.string.notification_message))
           .setSmallIcon(R.drawable.app_icon)
           .setContentIntent(pendingIntent)
           .build();
        
        } else
        {return new Notification.Builder(context)
                .setContentTitle(getText(R.string.app_name))
                .setContentText(getText(R.string.notification_message))
                .setSmallIcon(R.drawable.app_icon)
                .setContentIntent(pendingIntent)
                .build();
            
            
            }
    }



    /*
     *  Data received from serial port will be received here. Just populate onReceivedData with your code
     *  In this particular example. byte stream is converted to String and send to UI thread to
     *  be treated there.
     */
    private UsbSerialInterface.UsbReadCallback mCallback = new UsbSerialInterface.UsbReadCallback() {
        @Override
        public void onReceivedData(byte[] arg0) {
            try {
                String data = new String(arg0, "UTF-8");
                Timber.d("DATA: %s", data);
                if (mHandler != null) {
                    mHandler.obtainMessage(MESSAGE_FROM_SERIAL_PORT, data).sendToTarget();
                }
                mSocketServer.sendData(arg0);
            } catch (UnsupportedEncodingException e) {
                Timber.e(e, "");
            }
        }
    };
    private void onPermissionGranted(Context arg0){
        connection = usbManager.openDevice(device);
        Timber.i("usbManager.openDevice ok");
        serialPortConnected = true;
        new ConnectionThread().run();
        Timber.i("ConnectionThread().run() ok");

    }

    /*
     * Different notifications from OS will be received here (USB attached, detached, permission responses...)
     */
    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context arg0, Intent arg1) {
            if (arg1.getAction().equals(ACTION_USB_PERMISSION_GRANTED)) {
                onPermissionGranted(arg0); 
            }
            else if (arg1.getAction().equals(ACTION_USB_PERMISSION) ) {
            
                if (arg1.getExtras().getBoolean(UsbManager.EXTRA_PERMISSION_GRANTED)) // User accepted our USB connection. Try to open the device as a serial port
                {
                   if (device != null) onPermissionGranted(arg0);
                } else // User not accepted our USB connection. Send an Intent to the Main Activity
                {
                    Intent intent = new Intent(ACTION_USB_PERMISSION_NOT_GRANTED);
                    arg0.sendBroadcast(intent);
                }
            } else if (arg1.getAction().equals(ACTION_USB_ATTACHED)) {
                Timber.i("ACTION_USB_ATTACHED received");
                if (!serialPortConnected){
                    Timber.i("ACTION_USB_ATTACHED: serialPortConnected = false");
                    UsbDevice dev = (UsbDevice)arg1.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    Timber.i("ACTION_USB_ATTACHED: EXTRA_DEVICE complete");
                    findSerialPortDevice(dev); // A USB device has been attached. Try to open it as a Serial port
                }
            } else if (arg1.getAction().equals(ACTION_USB_DETACHED)) {
                // Usb device was disconnected. send an intent to the Main Activity
                UsbDevice dev = (UsbDevice)arg1.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                Timber.i("ACTION_USB_DETACHED: EXTRA_DEVICE complete");
                if (dev == device) {
                Intent intent = new Intent(ACTION_USB_DISCONNECTED);
                arg0.sendBroadcast(intent);
                serialPortConnected = false;
                if (serialPort != null) serialPort.close();
                device = null;
                }
            }
        }
    };


    /**
     * Intent to be used to start this service
     *
     * @param context
     * @return
     */
    public static Intent getIntent(Context context) {
        return new Intent(context, UsbService.class);
    }

    /*
     * Register for USB related broadcasts (USB ATTACHED, USB DETACHED...) and try to open USB port.
     */
    @Override
    public void onCreate() {
        this.context = this;
        serialPortConnected = false;
        mPrefs = (PrefHelper) getApplicationContext().getSystemService(PrefHelper.class.getName());

        UsbService.SERVICE_CONNECTED = true;
        registerReceiver(usbReceiver, getFilter());
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        

       

        findSerialPortDevice(null);
        
        mSocketServer = new SocketServer(mPrefs.getNetworkPort(), this);
        makeForeground();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return Service.START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        UsbService.SERVICE_CONNECTED = false;
        mSocketServer.stopServer();
        unregisterReceiver(usbReceiver);
    }

    /*
     * Write data through Serial Port
     */
    public void write(byte[] data) {
        if (serialPort != null)
            serialPort.write(data);

        // write to socket as well, useful for debugging
       // mSocketServer.sendData(new String(data));

    }

    public void setHandler(Handler mHandler) {
        this.mHandler = mHandler;
    }


    private void findSerialPortDevice(UsbDevice dev) {
        String devNameFilter = mPrefs.getUsbDevFilter();
            Timber.d("device filter: %s", devNameFilter);
            String DevName = "";
            int deviceVID,devicePID;
        if (dev != null){
            DevName = dev.getProductName();
            deviceVID = dev.getVendorId();
            devicePID = dev.getProductId();
            Timber.d("Check device  from event. Name: %s ",DevName );
            if (UsbSerialDevice.isSupported(dev) && (devNameFilter == "" || DevName.toLowerCase().contains(devNameFilter.toLowerCase()))) {
                // There is a device connected to our Android device. Try to open it as a Serial Port.
                if (usbManager.hasPermission(dev)){
                    Timber.i("Has permission on device %s", dev.getProductName());
                    //onPermissionGranted(getApplicationContext());
                    device = dev;
                    Intent intent = new Intent(ACTION_USB_PERMISSION_GRANTED);
                    getApplicationContext().sendBroadcast(intent);

                } else{
                    Timber.i("No permission on device %s. Request...", dev.getProductName());
                requestUserPermission(dev);
                }
            }
         return;
        }
        // This snippet will try to open the first encountered usb device connected, excluding usb root hubs
        HashMap<String, UsbDevice> usbDevices = null;
        boolean deviceFound = false;
        try {
            usbDevices = usbManager.getDeviceList();
        } catch (Exception e) {
            Timber.e(e, "Error opening USB");
            return;
        }
        if (!usbDevices.isEmpty()) {
            
            for (Map.Entry<String, UsbDevice> entry : usbDevices.entrySet()) {
                dev = entry.getValue();
                deviceVID = dev.getVendorId();
                devicePID = dev.getProductId();
                DevName = dev.getProductName();
                
                   Timber.d("Check device name: %s ",DevName );

                if (UsbSerialDevice.isSupported(dev) && (devNameFilter == "" || DevName.toLowerCase().contains(devNameFilter.toLowerCase()))) {
                    // There is a device connected to our Android device. Try to open it as a Serial Port.
                    deviceFound = true;
                    device = dev;
                    if (usbManager.hasPermission(dev)){
                        Timber.i("Has permission on device");
                        //onPermissionGranted(getApplicationContext());
                        Intent intent = new Intent(ACTION_USB_PERMISSION_GRANTED);
                        getApplicationContext().sendBroadcast(intent);

                    } else{
                        Timber.i("No permission on device. Request...");
                    requestUserPermission(dev);
                    }
                    break;
                }
            }
            if (!deviceFound) {
                // There is no USB devices connected (but usb host were listed). Send an intent to MainActivity.
                Intent intent = new Intent(ACTION_NO_USB);
                sendBroadcast(intent);
            }
        } else {
            // There is no USB devices connected. Send an intent to MainActivity
            Intent intent = new Intent(ACTION_NO_USB);
            sendBroadcast(intent);
        }
    }
    /*
     * Return using usb device name
     */
    public String getDeviceName() {
     if (device == null) return "";
     return device.getProductName();

    }
    /*
     * Return using Port
     */
    public int getPort() {
        if (device == null) return 0;
        return mSocketServer.getPort();   
       }


    private IntentFilter getFilter() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(ACTION_USB_PERMISSION_GRANTED);
        filter.addAction(ACTION_USB_DETACHED);
        filter.addAction(ACTION_USB_ATTACHED);
        return filter;
    }

    /*
     * Request user permission. The response will be received in the BroadcastReceiver
     */
    private void requestUserPermission(UsbDevice dev) {
        device = dev;
        PendingIntent mPendingIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), 0);
        usbManager.requestPermission(dev, mPendingIntent);
    }


    /**
     * Make this service run as "foreground service" so that OS knows its doing something
     * important and on going for the user
     *
     */
    private void makeForeground() {

        Notification notification = getNotification();



        startForeground(ONGOING_NOTIFICATION_ID, notification);
    }

    public class UsbBinder extends Binder {
        public UsbService getService() {
            return UsbService.this;
        }
    }

    

    /*
     * A simple thread to open a serial port.
     * Although it should be a fast operation. moving usb operations away from UI thread is a good thing.
     */
    private class ConnectionThread extends Thread {
        @Override
        public void run() {
            serialPort = UsbSerialDevice.createUsbSerialDevice(device, connection);
            if (serialPort != null) {
                if (serialPort.open()) {
                    serialPort.setBaudRate(mPrefs.getUsbSpeed());
                    serialPort.setDataBits(UsbSerialInterface.DATA_BITS_8);
                    serialPort.setStopBits(UsbSerialInterface.STOP_BITS_1);
                    serialPort.setParity(UsbSerialInterface.PARITY_NONE);
                    serialPort.setFlowControl(UsbSerialInterface.FLOW_CONTROL_OFF);
                    serialPort.read(mCallback);

                    // Everything went as expected. Send an intent to MainActivity
                    Intent intent = new Intent(ACTION_USB_READY);
                    context.sendBroadcast(intent);
                } else {
                    // Serial port could not be opened, maybe an I/O error or if CDC driver was chosen, it does not really fit
                    // Send an Intent to Main Activity
                    if (serialPort instanceof CDCSerialDevice) {
                        Intent intent = new Intent(ACTION_CDC_DRIVER_NOT_WORKING);
                        context.sendBroadcast(intent);
                    } else {
                        Intent intent = new Intent(ACTION_USB_DEVICE_NOT_WORKING);
                        context.sendBroadcast(intent);
                    }
                }
            } else {

                // No driver for given device, even generic CDC driver could not be loaded
                Intent intent = new Intent(ACTION_USB_NOT_SUPPORTED);
                context.sendBroadcast(intent);
            }
        }
    }
}
