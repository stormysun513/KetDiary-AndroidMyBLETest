package com.ubicomp.bletest;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@SuppressLint("NewApi")
public class BluetoothLE {
	private static final String TAG = "BluetoothLE";

    // Base service UUID
    private static final UUID SERVICE3_UUID = UUID.fromString("713D0000-503E-4C75-BA94-3148F18D941E");
    // Write UUID
    public static final UUID SERVICE3_WRITE_CHAR_UUID = UUID.fromString("713D0003-503E-4C75-BA94-3148F18D941E");
	// Notification UUID
    private static final UUID SERVICE3_NOTIFICATION_CHAR_UUID = UUID.fromString("713D0002-503E-4C75-BA94-3148F18D941E");

    public final static byte BLE_REQUEST_DEVICE_ID = (byte)0x00;
    public final static byte BLE_CHANGE_DEVICE_ID = (byte)0x01;
    public final static byte BLE_REQUEST_SALIVA_VOLTAGE = (byte)0x02;
    public final static byte BLE_REQUEST_IMAGE_INFO = (byte)0x03;
    public final static byte BLE_REQUEST_IMAGE_BY_INDEX = (byte)0x04;
    public final static byte BLE_ACK = (byte)0x05;

    public final static byte BLE_REPLY_IMAGE_INFO = (byte)0x00;
    public final static byte BLE_REPLY_SALIVA_VOLTAGE = (byte)0x01;
    public final static byte BLE_REPLY_DEVICE_ID = (byte)0x02;
    public final static byte BLE_REPLY_IMAGE_PACKET = (byte)0x03;

    private final static String dirName = "TempPicDir";

    public enum AppState{
        APP_FETCH_INFO,
        APP_IMAGE_GET_HEADER,
        APP_IMAGE_RECEIVING,
        APP_IMAGE_RECEIVED,
    };

	// Intent request codes
    private static final int REQUEST_ENABLE_BT = 2;
	
	private Activity activity = null;
    private String mDeviceName = null;
    private String mDeviceAddress = null;

	private BluetoothAdapter mBluetoothAdapter;
    private BluetoothLeService mBluetoothLeService;
    private BluetoothDataParserService mBluetoothDataParserService;
    private boolean mConnected = false;
    private boolean deviceScanned = false;
    private boolean parserRunning = false;

    private BluetoothGattCharacteristic mNotifyCharacteristic;
    private BluetoothGattCharacteristic mWriteCharacteristic;

    private Handler mHandler;
    private Runnable mRunnable;

    // Stops scanning after 10 seconds.
    private static final long SCAN_PERIOD = 3000;

    public AppState mAppState = AppState.APP_FETCH_INFO;

    public BluetoothLE(Activity activity, String mDeviceName) {
        mHandler = new Handler();

        this.activity = activity;
        this.mDeviceName = mDeviceName;

        // Use this check to determine whether BLE is supported on the device.  Then you can
        // selectively disable BLE-related features.
        if (!activity.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            ((BluetoothListener) activity).bleNotSupported();
        }

        // Initializes a Bluetooth adapter.  For API level 18 and above, get a reference to
        // BluetoothAdapter through BluetoothManager.
        final BluetoothManager bluetoothManager =
                (BluetoothManager) activity.getSystemService(Context.BLUETOOTH_SERVICE);
        mBluetoothAdapter = bluetoothManager.getAdapter();

        // Checks if Bluetooth is supported on the device.
        if (mBluetoothAdapter == null) {
            ((BluetoothListener) activity).bleNotSupported();
            return;
        }
    }
     
	// Code to manage Service lifecycle.
	private final ServiceConnection mBluetoothLeServiceConnection = new ServiceConnection() {
        
	    @Override
	    public void onServiceConnected(ComponentName componentName, IBinder service) {
	        mBluetoothLeService = ((BluetoothLeService.LocalBinder) service).getService();

	        if (!mBluetoothLeService.initialize()) {
	            Log.e(TAG, "Unable to initialize Bluetooth Service.");
//	            activity.finish();
	        }
	        // Automatically connects to the device upon successful start-up initialization.
	        mBluetoothLeService.connect(mDeviceAddress);
	    }
	
	    @Override
	    public void onServiceDisconnected(ComponentName componentName) {
	        mBluetoothLeService = null;
            unbindBLEService();
	    }
	};

    // Code to manage Service lifecycle.
    private final ServiceConnection mDataParserServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            mBluetoothDataParserService = ((BluetoothDataParserService.LocalBinder) iBinder).getService();
            Log.i(TAG, "Successfully bound BluetoothDataParser Service.");
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mBluetoothDataParserService = null;
            activity.unbindService(mDataParserServiceConnection);
            Log.i(TAG, "Unbound BluetoothDataParser Service.");
        }
    };


    private final BroadcastReceiver mGattUpdateReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (BluetoothLeService.ACTION_GATT_CONNECTED.equals(action)) {
                mConnected = true;
                //Terminate the BLE connection timeout (10sec)
                mHandler.removeCallbacks(mRunnable);
                ((BluetoothListener) activity).bleConnected();

            } else if (BluetoothLeService.ACTION_GATT_DISCONNECTED.equals(action)) {
                mConnected = false;
                ((BluetoothListener) activity).bleDisconnected();
                unbindBLEService();

            } else if (BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED.equals(action)) {
                // Show all the supported services and characteristics on the user interface.
            	List<BluetoothGattService> gattServices = mBluetoothLeService.getSupportedGattServices();
            	mNotifyCharacteristic = gattServices.get(2).getCharacteristic(SERVICE3_NOTIFICATION_CHAR_UUID);
            	mBluetoothLeService.setCharacteristicNotification(mNotifyCharacteristic, true);

                /**** IMPORTANT added by Larry ****/
                // 0x2902 org.bluetooth.descriptor.gatt.client_characteristic_configuration.xml
                UUID uuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
                BluetoothGattDescriptor descriptor = mNotifyCharacteristic.getDescriptor(uuid);
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                mBluetoothLeService.writeDescriptor(descriptor);
                
                mWriteCharacteristic = gattServices.get(2).getCharacteristic(SERVICE3_WRITE_CHAR_UUID);
                Log.i(TAG, "BLE ACTION_GATT_SERVICES_DISCOVERED");

            } else if (BluetoothLeService.ACTION_DATA_WRITE_SUCCESS.equals(action)) {
                ((BluetoothListener) activity).bleWriteCharacteristic1Success();

            } else if (BluetoothLeService.ACTION_DATA_WRITE_FAIL.equals(action)) {
                ((BluetoothListener) activity).bleWriteStateFail();

            } else if (BluetoothLeService.ACTION_DATA_AVAILABLE.equals(action)) {
            	byte[] data = intent.getByteArrayExtra(BluetoothLeService.EXTRA_DATA);
            	
//            	StringBuffer stringBuffer = new StringBuffer("");
//            	for(int ii=0;ii<data.length;ii++){
//                    String s1 = String.format("%2s", Integer.toHexString(data[ii] & 0xFF)).replace(' ', '0');
//                    if( ii != data.length-1)
//                        stringBuffer.append(s1 + ":");
//                    else
//                        stringBuffer.append(s1);
//            	}
//            	Log.i(TAG, stringBuffer.toString());

                // DEBUG
                switch (mAppState){
                    case APP_IMAGE_RECEIVING:
                        if(data[0] != BLE_REPLY_IMAGE_PACKET){
//                            byte [] command = new byte[] {BluetoothLE.BLE_REQUEST_IMAGE_BY_INDEX, (byte)0x00};
//                            bleWriteCharacteristic1(command);
                        }
                        else{
                            if (mBluetoothDataParserService != null){
                                mBluetoothDataParserService.parseDataPacket(data);
                            }
                        }
                        break;
                    case APP_IMAGE_GET_HEADER:
                        terminatePacketParser();
                        if(data[0] == BLE_REPLY_IMAGE_INFO) {
                            Intent intentToParserService = new Intent(activity.getBaseContext(), BluetoothDataParserService.class);
                            intentToParserService.putExtra(BluetoothDataParserService.EXTRA_HEADER_DATA, data);
                            activity.startService(intentToParserService);
                            parserRunning = true;
                        }
                        break;
                    case APP_IMAGE_RECEIVED:
                    case APP_FETCH_INFO:
                    default:
                    {
                        switch(data[0]) { // Handling notification depending on types
                            case BLE_REPLY_DEVICE_ID:
                                int deviceId = ((data[1] & 0xFF) << 8) + (data[2] & 0xFF);
                                ((MainActivity) activity).updateTextViewInfo("ket_"+String.valueOf(deviceId));
                                break;
                            case BLE_REPLY_SALIVA_VOLTAGE:
                                int value = ((data[1] & 0xFF) << 8) + (data[2] & 0xFF);
                                ((MainActivity) activity).updateTextViewInfo("Voltage:"+String.valueOf(value));
                                break;
                        }
                        break;
                    }
                }
            }
            else if (BluetoothDataParserService.ACTION_IMAGE_RECEIVED_SUCCESS.equals(action)){
                mAppState = AppState.APP_IMAGE_RECEIVED;
                terminatePacketParser();
                byte[] data = intent.getByteArrayExtra(BluetoothDataParserService.EXTRA_IMAGE_DATA);
                new UpdataUIAsyncTask().execute(data);
                Log.i(TAG, "Received entire image.");
            }
            else if (BluetoothDataParserService.ACTION_IMAGE_HEADER_CHECKED.equals(action)){
                mAppState = AppState.APP_IMAGE_RECEIVING;

                Intent _Intent = new Intent(activity, BluetoothDataParserService.class);
                activity.bindService(_Intent, mDataParserServiceConnection, Context.BIND_AUTO_CREATE);

                byte [] command = new byte[] {BluetoothLE.BLE_REQUEST_IMAGE_BY_INDEX, (byte)0x00};
                bleWriteCharacteristic1(command);
                Log.i(TAG, "Received data header information.");
            }
            else if (BluetoothDataParserService.ACTION_ACK_LOST_PACKETS.equals(action)){

            }
            else{
                Log.i(TAG, "----BLE Can't handle data----");
            }

        }
    };

    private void unbindBLEService() {
        activity.unbindService(mBluetoothLeServiceConnection);
        activity.unregisterReceiver(mGattUpdateReceiver);
        deviceScanned = false;
    }

	public void bleConnect() {
		
		// Ensures Bluetooth is enabled on the device.  If Bluetooth is not currently enabled,
        // fire an intent to display a dialog asking the user to grant permission to enable it.
        if (!mBluetoothAdapter.isEnabled()) {
            if (!mBluetoothAdapter.isEnabled()) {
                Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                activity.startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
            }
        }
        else {
            bleScan();
        }
		return;
	}

    public void terminatePacketParser() {
        if(parserRunning) {
            Log.i(TAG, "Wait for stopping BluetoothDataParserService.");
            if(mBluetoothLeService != null)
                activity.unbindService(mDataParserServiceConnection);
            mBluetoothDataParserService = null;
            activity.stopService(new Intent(activity.getBaseContext(), BluetoothDataParserService.class));
            parserRunning = false;
        }
    }

    public void bleDisconnect() {
        if(mBluetoothLeService != null) {
            mBluetoothLeService.disconnect();
        }
        terminatePacketParser();

        if(!mConnected) {
            //Terminate the BLE connection timeout (10sec)
            mHandler.removeCallbacks(mRunnable);
            mBluetoothAdapter.stopLeScan(mLeScanCallback);
        }
    }

    public void bleWriteCharacteristic1(byte [] data) {

        if((mBluetoothLeService != null) && (mWriteCharacteristic != null)) {
            mWriteCharacteristic.setValue(data);
            mBluetoothLeService.writeCharacteristic(mWriteCharacteristic);
        }
        return;
    }

    /* Timeout implementation */
    private void bleScan() {
        mHandler.postDelayed(mRunnable = new Runnable() {

            @Override
            public void run() {
                mBluetoothAdapter.stopLeScan(mLeScanCallback);
                ((BluetoothListener) activity).bleConnectionTimeout();
//                    Log.i("BLE", "thread run");
            }
        }, SCAN_PERIOD);

        mBluetoothAdapter.startLeScan(mLeScanCallback);
    }
	
	private static IntentFilter makeGattUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        /* BluetoothLeService */
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_CONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED);
        intentFilter.addAction(BluetoothLeService.ACTION_DATA_AVAILABLE);
        intentFilter.addAction(BluetoothLeService.ACTION_DATA_WRITE_SUCCESS);
        intentFilter.addAction(BluetoothLeService.ACTION_DATA_WRITE_FAIL);

        /* BluetoothLeService */
        intentFilter.addAction(BluetoothDataParserService.ACTION_IMAGE_RECEIVED_SUCCESS);
        intentFilter.addAction(BluetoothDataParserService.ACTION_IMAGE_HEADER_CHECKED);
        intentFilter.addAction(BluetoothDataParserService.ACTION_ACK_LOST_PACKETS);

        return intentFilter;
    }

	
	public void onBLEActivityResult(int requestCode, int resultCode, Intent data) {
		switch (requestCode) {
            case REQUEST_ENABLE_BT:{
        	    // When the request to enable Bluetooth returns
                if (resultCode == Activity.RESULT_OK) {
                    // Bluetooth is now enabled, enable BLE scan
                    bleScan();
                } else{
                    // User did not enable Bluetooth or an error occured
                    Toast.makeText(activity, "Bluetooth did not enable!", Toast.LENGTH_SHORT).show();
//                activity.finish();
                }
        	    break;
            }
		}
	}

    // Device scan callback.
    private BluetoothAdapter.LeScanCallback mLeScanCallback =
            new BluetoothAdapter.LeScanCallback() {
                @Override
                public void onLeScan(final BluetoothDevice device, int rssi, byte[] scanRecord) {
                    // Do nothing if target device is scanned
                    if(deviceScanned)
                        return;

                    String scannedName = device.getName();
                    if(device.getName() == null){
                        BLEAdvertisedData badData = new BLEUtil().parseAdertisedData(scanRecord);
                        scannedName = badData.getName();
                    }
                    Log.d(TAG, "Device = " + scannedName + ", Address = " + device.getAddress());

                    if(mDeviceName.equals(scannedName)){
                        mDeviceAddress = device.getAddress();
                        deviceScanned = true;

                        Intent gattServiceIntent = new Intent(activity, BluetoothLeService.class);
                        activity.bindService(gattServiceIntent, mBluetoothLeServiceConnection, Context.BIND_AUTO_CREATE);
                        activity.registerReceiver(mGattUpdateReceiver, makeGattUpdateIntentFilter());
                        mBluetoothAdapter.stopLeScan(mLeScanCallback);
                    }
                }
            };

    final public class BLEUtil {

        public BLEAdvertisedData parseAdertisedData(byte[] advertisedData) {
            List<UUID> uuids = new ArrayList<UUID>();
            String name = null;
            if( advertisedData == null ){
                return new BLEAdvertisedData(uuids, name);
            }

            ByteBuffer buffer = ByteBuffer.wrap(advertisedData).order(ByteOrder.LITTLE_ENDIAN);
            while (buffer.remaining() > 2) {
                byte length = buffer.get();
                if (length == 0)
                    break;

                byte type = buffer.get();
                switch (type) {
                    case 0x02: // Partial list of 16-bit UUIDs
                    case 0x03: // Complete list of 16-bit UUIDs
                        while (length >= 2) {
                            uuids.add(UUID.fromString(String.format(
                                    "%08x-0000-1000-8000-00805f9b34fb", buffer.getShort())));
                            length -= 2;
                        }
                        break;
                    case 0x06: // Partial list of 128-bit UUIDs
                    case 0x07: // Complete list of 128-bit UUIDs
                        while (length >= 16) {
                            long lsb = buffer.getLong();
                            long msb = buffer.getLong();
                            uuids.add(new UUID(msb, lsb));
                            length -= 16;
                        }
                        break;
                    case 0x09:
                        byte[] nameBytes = new byte[length-1];
                        buffer.get(nameBytes);
                        try {
                            name = new String(nameBytes, "utf-8");
                        } catch (UnsupportedEncodingException e) {
                            e.printStackTrace();
                        }
                        break;
                    default:
                        buffer.position(buffer.position() + length - 1);
                        break;
                }
            }
            return new BLEAdvertisedData(uuids, name);
        }
    }


    public class BLEAdvertisedData {

        private List<UUID> mUuids;
        private String mName;
        public BLEAdvertisedData(List<UUID> uuids, String name){
            mUuids = uuids;
            mName = name;
        }

        public List<UUID> getUuids(){
            return mUuids;
        }

        public String getName(){
            return mName;
        }
    }

    public class UpdataUIAsyncTask extends AsyncTask<byte[], Void, byte[]>{

        @Override
        protected byte[] doInBackground(byte[]... bytes) {
            File mainStorage;
            File file;
            FileOutputStream fos;

            if (Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED))
                mainStorage = new File(Environment.getExternalStorageDirectory(), dirName);
            else
                mainStorage = new File(activity.getApplicationContext().getFilesDir(), dirName);

            if (!mainStorage.exists())
                mainStorage.mkdirs();

//            Timestamp timestamp = new Timestamp(System.currentTimeMillis());
            Long tsLong = System.currentTimeMillis()/1000;
            file = new File(mainStorage, "PIC_".concat(String.valueOf(tsLong.toString())).concat(".jpg"));

            try {
                fos = new FileOutputStream(file, true);
                fos.write(bytes[0]);
                fos.close();
            } catch (IOException e) {
                Log.d(TAG, "FAIL TO OPEN FILES: " + file.getAbsolutePath());

            }
            return bytes[0];
        }

        @Override
        protected void onPostExecute(byte[] result)
        {
            super.onPostExecute(result);
            //    將doInBackground方法返回的byte[]解碼成要給Bitmap
            Bitmap bitmap = BitmapFactory.decodeByteArray(result, 0, result.length);
            //    更新我們的ImageView控件
            if(bitmap == null){
                Log.i(TAG, "Image corrupted during dat transmission!");
            }
            else{
                Log.i(TAG, "Received image successfully.");
                ((MainActivity) activity).updateImageViewPreview(bitmap);
            }
        }
    }
}
