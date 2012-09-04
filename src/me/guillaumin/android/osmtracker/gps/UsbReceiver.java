package me.guillaumin.android.osmtracker.gps;

import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.fail;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Iterator;

import android.annotation.TargetApi;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationProvider;
import android.util.Log;
import me.guillaumin.android.osmtracker.OSMTracker;
import me.guillaumin.android.osmtracker.gps.GpsStatus.RawDataListener;
import me.guillaumin.android.osmtracker.gps.UsbSerialController.UsbControllerException;

@TargetApi(12)
public class UsbReceiver extends Receiver {

	// Debugging
	private static final String TAG = UsbReceiver.class.getSimpleName();
    private static final boolean D = OSMTracker.DEBUG;

    public static final int STATE_CONNECTING = 1; // now initiating an outgoing connection
    public static final int STATE_CONNECTED = 2;  // now connected to a remote device

    public static final String DEVICE_ANY = "Any";

    private int mBaudrate = UsbSerialController.DEFAULT_BAUDRATE;
	private UsbManager mUsbManager;
	private Context mContext;
	private String requestedName;
	private ServiceThread mServiceThread;
	private UsbStateListener mUsbStateListener = new UsbStateListener();
	private RawDataTransporter rawDataTransporter = new RawDataTransporter();

    // Receiver internal state
    ReceiverInternalState internalState;

	protected UsbReceiver(Context pContext, UsbManager usbManager, String name) {
		this.mContext = pContext;
		this.mUsbManager = usbManager;
		this.requestedName = name;
		this.internalState = new ReceiverInternalState(this.getAddress());
	}

	@Override
	public String getName() { return requestedName; }

	@Override
	public String getAddress() { return requestedName; }

	@Override
	public Location getLastKnownLocation() {
		return internalState.getLastKnownLocation();
	}

	@Override
	public synchronized void requestLocationUpdates(long minTime, float minDistance, final LocationListener listener) {
		internalState.requestLocationUpdates(minTime, minDistance, listener);
		activateUsbService();
	}

	@Override
	public synchronized void removeUpdates(LocationListener listener) {
		internalState.removeUpdates(listener);
		deactivateUsbService();
	}

	@Override
	 public boolean addRawDataListener (RawDataListener listener) {
		rawDataTransporter.addRawDataListener(listener);
		activateUsbService();
		return true;
	 }

	@Override
	public void removeRawDataListener(RawDataListener listener) {
		if (listener == null)
			throw new IllegalArgumentException();

		rawDataTransporter.removeRawDataListener(listener);
		deactivateUsbService();
	}

	public boolean addGpsStatusListener(GpsStatus.Listener listener) {
		internalState.addGpsStatusListener(listener);
		activateUsbService();
		return true;
	}

	public void removeGpsStatusListener (GpsStatus.Listener listener) {
		internalState.removeGpsStatusListener(listener);
		deactivateUsbService();
	}

	public GpsStatus getGpsStatus(GpsStatus status) {
		return internalState.getGpsStatus(status);
	}

	private synchronized void activateUsbService() {

		if ( !internalState.hasListeners()
				&& !rawDataTransporter.hasListeners()) return;

		if (mServiceThread != null)
			return;

		// Start the thread to connect with the given device
		mServiceThread = new ServiceThread();
		mServiceThread.setPriority(Thread.MIN_PRIORITY);
		mServiceThread.start();

		mContext.registerReceiver(mUsbStateListener, mUsbStateListener.createIntentFilter());
	}

	private synchronized void deactivateUsbService() {

		if (internalState.hasListeners()
				|| rawDataTransporter.hasListeners()
				) return;

		/* Stop service thread */
		if (mServiceThread != null) {
			mContext.unregisterReceiver(mUsbStateListener);
			mServiceThread.cancel();
			mServiceThread = null;
		}
	}

    public synchronized void setBaudRate(int baudrate) {
    	this.mBaudrate = baudrate;
    	if (mServiceThread != null) {
    		mServiceThread.setBaudRate(baudrate);
    	}
    }

    public synchronized int getBaudRate() {
 	   return this.mBaudrate;
    }

    public synchronized void onUsbDeviceAttached(Intent intent) {
    	UsbDevice device;

    	device = (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

    	Log.d(TAG, "onUsbDeviceAttached() device=" + device);
    	if (device != null && (this.mServiceThread == null)) {
    		this.mServiceThread.onUsbDeviceAttached(device);
    	}
    }

	synchronized void onConnectionStateChanged(int oldState,
			   int newState, final String toast, final String statusMessage) {

		int newStatus = LocationProvider.OUT_OF_SERVICE;
		switch (newState) {
		case STATE_CONNECTING:
			newStatus = LocationProvider.OUT_OF_SERVICE;
			break;
		case STATE_CONNECTED:
			newStatus = LocationProvider.TEMPORARILY_UNAVAILABLE;
			break;
		default:
			if (D) fail();
		}
		internalState.transportStatusChanged(newStatus, toast, statusMessage);
	}

	private class UsbStateListener extends BroadcastReceiver {
		private static final String ACTION_USB_PERMISSION =
				"me.guillaumin.android.osmtracker.gps.UsbReceiver.USB_PERMISSION";

		private IntentFilter createIntentFilter() {
			IntentFilter f;
			f = new IntentFilter();
			f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
			f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
			f.addAction(ACTION_USB_PERMISSION);
			return f;
		}

		@Override
		public void onReceive(Context context, Intent intent) {
			UsbDevice device;
			String action = intent.getAction();
			Log.v(TAG, "Received intent " + action);

			if (action.equals(UsbManager.ACTION_USB_DEVICE_ATTACHED)) {
				UsbReceiver.this.onUsbDeviceAttached(intent);
			}else if (action.equals(UsbManager.ACTION_USB_DEVICE_DETACHED)) {
				device = (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
				synchronized(UsbReceiver.this) {
					if (device != null && (UsbReceiver.this.mServiceThread != null)) {
						UsbReceiver.this.mServiceThread.onUsbDeviceDetached(device);
					}
				}
			}else if (action.equals(ACTION_USB_PERMISSION)) {
				boolean granted;
				device = (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
				granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
				synchronized(UsbReceiver.this) {
					if (device != null && (UsbReceiver.this.mServiceThread != null)) {
						UsbReceiver.this.mServiceThread.onUsbDevicePermissionChanged(device, granted);
					}
				}
			}
		}
	}

    // Thread used to manage the communication with the USB GPS
	private class ServiceThread extends Thread {

	   public static final int RECONNECT_TIMEOUT_MS = 20000;

	   private final String TAG = ServiceThread.class.getSimpleName();

       private UsbSerialController serialController;
       private InputReader mInputReader = null;
       private OutputStream mOutputStream = null;
       private PendingIntent mPermissionIntent;

       private int mConnectionState;
       private volatile boolean cancelRequested = false;

       public void run() {
           Log.i(TAG, "BEGIN ServiceThread");
           setName("UsbServiceThread");

           mPermissionIntent = PendingIntent.getBroadcast(
				   UsbReceiver.this.mContext,
				   0,
				   new Intent(UsbStateListener.ACTION_USB_PERMISSION),
				   0
				   );

           mainloop: for (;!cancelRequested;) {

               /* Connect */
        	   setState(STATE_CONNECTING, null, "Connecting to " + UsbReceiver.this.getName() + "...");
        	   while ((mConnectionState == STATE_CONNECTING) && (!cancelRequested)) {
        		   try {
        			   synchronized(this) {
        				   if (this.serialController == null) {
        					   initSerialController();
        				   }
        				   if (this.serialController.hasPermission()) {
        					   attachSerialDevice();
        					   setState(STATE_CONNECTED, null, "USB connection established sucessfully");
        				   }else {
        					   this.serialController.requestPermission(mPermissionIntent);
        					   throw new UsbControllerException("waiting for permission");
        				   }
        			   }
    			   }catch (UsbControllerException e) {
    				   Log.e(TAG, "connect() failed: " + e.getLocalizedMessage());
    				   setState(STATE_CONNECTING, null, e.getLocalizedMessage());
    				   synchronized(this) {
    					   if (cancelRequested)
    						   break mainloop;
    					   try {
    						   wait(RECONNECT_TIMEOUT_MS);
    					   } catch(InterruptedException ie) {
    						   break mainloop;
    					   }
    					   if (cancelRequested)
    						   break mainloop;
    				   }
    			   };
        	   }

        	   /* Read and process data from USB GPS */
        	   try {
    			   mInputReader.loop();
        	   }  catch (IOException e) {
        		   if (!cancelRequested) {
        			   Log.e(TAG, "connection lost: " + e.getLocalizedMessage());
        			   synchronized(this) {
        				   if (serialController != null) {
        					   serialController.detach();
        					   serialController = null;
        				   }
        			   }
        		   }
        	   }
           } /* for(;!cancelRequested;) */
       } /* run() */


       private UsbSerialController probeSerialController(UsbManager usbManager, UsbDevice d) throws UsbControllerException {
    	   UsbSerialController s = null;

    	   if (UsbPl2303Controller.probe(d) == true) {
    		   s = new UsbPl2303Controller(usbManager, d, UsbReceiver.this.mContext.getResources());
    	   }else if (UsbAcmController.probe(d) == true) {
    		   s = new UsbAcmController(usbManager, d, UsbReceiver.this.mContext.getResources());
    	   }

    	   return s;
       }

       private void initSerialController() throws UsbControllerException {
    	   HashMap<String, UsbDevice> deviceList;
    	   UsbDevice d;
    	   UsbSerialController serial;

    	   deviceList = UsbReceiver.this.mUsbManager.getDeviceList();
    	   if (deviceList == null) throw new UsbControllerException("Device not connected");

    	   if (D) Log.v(TAG, "DeviceList size: " + deviceList.size());

    	   if ( ! UsbReceiver.DEVICE_ANY.equals(UsbReceiver.this.getAddress()) ) {
    		   d = deviceList.get(UsbReceiver.this.getAddress());
    		   if (d == null) throw new UsbControllerException("Device not connected");
    		   serial = probeSerialController(UsbReceiver.this.mUsbManager, d);
    		   if (serial == null) throw new UsbControllerException("Unknown device");
    	   }else {
    		   /* First available device */
    		   Iterator<UsbDevice> i;

    		   i = deviceList.values().iterator();
    		   do {
    			   if (i.hasNext() == false) throw new UsbControllerException("Device not connected");
    			   d = i.next();
    			   serial = probeSerialController(UsbReceiver.this.mUsbManager, d);
    		   }while (serial == null);
    	   }

    	   assertNotNull(serial);
    	   assertNotNull(d);

    	   serial.setBaudRate(UsbReceiver.this.mBaudrate);

    	   synchronized(this) {
    		   this.serialController = serial;
    	   }
       }

       private synchronized void attachSerialDevice() throws UsbControllerException {
    	   this.serialController.attach();
  		   this.mInputReader = new InputReader(serialController.getInputStream());
		   this.mOutputStream = serialController.getOutputStream();
       }

       synchronized void onUsbDeviceAttached(UsbDevice d) {
    	   Log.d(TAG, "onUsbDeviceAttached() device=" + d);
    	   if (this.serialController == null) {
    		   this.notify();
    	   }
       }

       void onUsbDeviceDetached(UsbDevice d) {
    	   UsbSerialController s = null;

    	   Log.d(TAG, "onUsbDeviceDetached() device=" + d);
    	   synchronized (this) {
    		   if (serialController != null
    				   && serialController.mUsbDevice.equals(d)
    				   ) {
    			   s = serialController;
    			   serialController = null;
    		   }
    	   }
    	   if (s != null) s.detach();
       }

       synchronized void onUsbDevicePermissionChanged(UsbDevice d, boolean granted) {
    	   if ((this.serialController != null)
    			   && (this.mInputReader == null)
    			   ) {
    		   this.notify();
    	   }
       }

       /**
        * Write to the connected OutStream.
        * @param buffer  The bytes to write
        */
       @SuppressWarnings("unused")
       public void write(byte[] buffer) {
    	   OutputStream os;
    	   synchronized(this) {
    		   if (mConnectionState != STATE_CONNECTED) {
    			   Log.e(TAG, "write() error: not connected");
    			   return;
    		   }
    		   os = mOutputStream;
    		   if (D) assertNotNull(os);
    	   }

    	   try {
    		   os.write(buffer);
    	   } catch (IOException e) {
    		   Log.e(TAG, "Exception during write", e);
    	   }
       }

       public void cancel() {
    	   UsbSerialController s;
    	   synchronized(this) {
    		   cancelRequested = true;
    		   s = serialController;
    		   serialController = null;
    		   this.notify();
    	   }
    	   if (s != null) s.detach();
       }

       public synchronized void setBaudRate(int baudrate) {
    	   if (serialController != null) serialController.setBaudRate(baudrate);
       }

  	   /**
       * Set the current state of the connection
       * @param state  An integer defining the current connection state
       */
       @SuppressWarnings("unused")
       private void setState(int state) {
    	   setState(state, null, null);
       }

       @SuppressWarnings("unused")
       private void setState(int state, final String toast) {
    	   setState(state, toast, null);
       }

   	    /**
        * Set the current state of the connection
        * @param state  An integer defining the current connection state
        * @param toast  Optional toast notification
        * @param statusMessage  Optional status notification
        */
    	private void setState(int state, final String toast, final String statusMessage) {
    		int oldState = mConnectionState;
    		mConnectionState = state;

    		if (D) Log.d(TAG, "setState() " + oldState + " -> " + state);
    		UsbReceiver.this.onConnectionStateChanged(oldState, state, toast, statusMessage);
    	}

    	private class InputReader extends GpsInputReader {

    		   public InputReader(InputStream s) { super(s); }

    		   @Override
    		   protected void onRawDataReceived(byte[] buf, int offset, int length) {
    			   rawDataTransporter.onRawDataReceived(buf, offset, length);
    		   }

    		   @Override
    		   protected void onNmeaReceived(String nmea) {
    			   if (D) Log.i(TAG, "NMEA: " + nmea.trim());
    			   internalState.putNmeaMessage(nmea);
    		   }

    		   @Override
    		   protected void onSirfReceived(byte[] buf, int offset, int length) {
    			   internalState.putSirfMessage(buf, offset, length);
    		   }

    		   @Override
    		   protected void onBufferFlushed() {
    			   if (D) Log.v(TAG, "onBufferFlushed()");
    		   }
    	   }
	}

}
