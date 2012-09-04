package me.guillaumin.android.osmtracker.gps;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import me.guillaumin.android.osmtracker.OSMTracker;
import me.guillaumin.android.osmtracker.gps.GpsStatus.RawDataListener;

import static junit.framework.Assert.*;

import android.annotation.TargetApi;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationProvider;
import android.util.Log;

/**
 * @author Alexey Illarionov
 *
 */
@TargetApi(10)
public class BluetoothReceiver extends Receiver {
	// Debugging
    private static final String TAG = BluetoothReceiver.class.getSimpleName();
    private static final boolean D = OSMTracker.DEBUG;

    // Constants that indicate the current connection state
    public static final int STATE_CONNECTING = 1; // now initiating an outgoing connection
    public static final int STATE_CONNECTED = 2;  // now connected to a remote device
    public static final int STATE_RECONNECTING = 4;

	//  Standard UUID for the Serial Port Profile
	private static final java.util.UUID UUID_SPP = java.util.UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    // Member fields
	private Context mContext;
    private BluetoothAdapter mBtAdapter;
    private BluetoothDevice mBtDevice;

    // Receiver internal state
    ReceiverInternalState internalState;

    private BluetoothStateListener mBtStateListener;
    private boolean mIsBtActive;
    private ServiceThread mServiceThread;
    private RawDataTransporter rawDataTransporter;

	protected BluetoothReceiver(Context pContext, BluetoothAdapter adapter, BluetoothDevice device) {
		this.mContext = pContext;
		this.mBtAdapter = adapter;
		this.mBtDevice = device;
		this.internalState = new ReceiverInternalState(this.getAddress());
		this.rawDataTransporter = new RawDataTransporter();
	}

	/* (non-Javadoc)
	 * @see me.guillaumin.android.osmtracker.gps.Receiver#getName()
	 */
	@Override
	public String getName() {
		return mBtDevice.getName();
	}

	/* (non-Javadoc)
	 * @see me.guillaumin.android.osmtracker.gps.Receiver#getAddress()
	 */
	@Override
	public String getAddress() {
		return mBtDevice.getAddress();
	}

	@Override
	public String toString() {
		return mBtDevice.toString();
	}

	@Override
	public Location getLastKnownLocation() {
		return internalState.getLastKnownLocation();
	}

	@Override
	public void requestLocationUpdates(long minTime, float minDistance, final LocationListener listener) {
		internalState.requestLocationUpdates(minTime, minDistance, listener);
		activateBtService();
	}

	@Override
	public synchronized void removeUpdates(LocationListener listener) {
		internalState.removeUpdates(listener);
		deactivateBtService();
	}

	@Override
	 public boolean addRawDataListener (RawDataListener listener) {
		rawDataTransporter.addRawDataListener(listener);
		activateBtService();
		return true;
	 }

	@Override
	public void removeRawDataListener (RawDataListener listener) {
		rawDataTransporter.removeRawDataListener(listener);
		deactivateBtService();
	}

	public boolean addGpsStatusListener(GpsStatus.Listener listener) {
		internalState.addGpsStatusListener(listener);
		activateBtService();
		return true;
	}

	public void removeGpsStatusListener (GpsStatus.Listener listener) {
		internalState.removeGpsStatusListener(listener);
		deactivateBtService();
	}

	public GpsStatus getGpsStatus(GpsStatus status) {
		return internalState.getGpsStatus(status);
	}

	private synchronized void activateBtService() {

		if ( !internalState.hasListeners()
				&& !this.rawDataTransporter.hasListeners()) return;


		/* Activate bluetooth state listener */
		if (mBtStateListener == null) {
			mBtStateListener = new BluetoothStateListener();
			this.mContext.getApplicationContext().registerReceiver(
					mBtStateListener, mBtStateListener.createIntentFilter());
			mIsBtActive = (mBtAdapter.getState() == BluetoothAdapter.STATE_ON);
		}

		if (!mIsBtActive)
			return;

		if (mServiceThread != null)
			return;

		// Start the thread to connect with the given device
		mServiceThread = new ServiceThread();
		mServiceThread.setPriority(Thread.MIN_PRIORITY);
		mServiceThread.start();
	}

	private synchronized void deactivateBtService() {

		if (internalState.hasListeners()
				|| rawDataTransporter.hasListeners()
				) return;

		/* Deactivate bluetooth state listener */
		if (mBtStateListener != null) {
			this.mContext.getApplicationContext().unregisterReceiver(mBtStateListener);
			mBtStateListener = null;
		}

		/* Stop service thread */
		if (mServiceThread != null) {
			mServiceThread.cancel();
			mServiceThread = null;
		}
	}

	synchronized void onBluetoothStateChanged(int newState) {
		boolean newIsBtActive = (newState == BluetoothAdapter.STATE_ON);

		if (newIsBtActive == this.mIsBtActive) return;

		this.mIsBtActive = newIsBtActive;
		if (mIsBtActive) {
			/* Start service thread if we have listeners */
			activateBtService();
			internalState.providerEnabled();
		}else {
			internalState.providerDisabled();
			/* Stop service thread even we have listeners*/
			if (mServiceThread != null) {
				mServiceThread.cancel();
				mServiceThread = null;
			}
		}
	}

	synchronized void onConnectionStateChanged(int oldState,
			   int newState, final String toast, final String statusMessage) {

		int newStatus = LocationProvider.OUT_OF_SERVICE;

		switch (newState) {
		case STATE_CONNECTING:
			newStatus = LocationProvider.OUT_OF_SERVICE;
			break;
		case STATE_RECONNECTING:
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



   /**
    * Write to the ConnectedThread in an unsynchronized manner
    * @param out The bytes to write
    * @see ConnectedThread#write(byte[])
    */
   private void write(byte[] out) {
	   // Create temporary object
	   ServiceThread r;
	   // Synchronize a copy of the ConnectedThread
	   synchronized (this) {
		   r = mServiceThread;
		   if (r == null)
			   return;
	   }
	   // Perform the write unsynchronized
	   r.write(out);
   }

   private class BluetoothStateListener extends BroadcastReceiver {

		private IntentFilter createIntentFilter() {
			return new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
		}

		@Override
		public void onReceive(Context context, Intent intent) {

			Log.v(TAG, "Received intent " + intent.getAction());

			if ((BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction()))) {
				int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_ON);
				BluetoothReceiver.this.onBluetoothStateChanged(state);
			}
		}

   }

    // Thread used to manage the communication with the bluetooth GPS
   private class ServiceThread extends Thread {

	   public static final int RECONNECT_TIMEOUT_MS = 2000;

	   private final String TAG = ServiceThread.class.getSimpleName();

       private BluetoothSocket mSocket;
       private InputReader mInputReader = null;
       private OutputStream mOutputStream = null;

       private int mConnectionState;
       private volatile boolean cancelRequested = false;

       public void run() {

           Log.i(TAG, "BEGIN ServiceThread");
           setName("BluetoothServiceThread");

           /* Connect */
           setState(STATE_CONNECTING, null, "Connecting to " + BluetoothReceiver.this.getName() + "...");
           mConnectionState = STATE_CONNECTING;
           try {
			   connect();
			   setState(STATE_CONNECTED, "Bluetooth connection established sucessfully");
		   }catch (IOException e) {
			   synchronized(this) {
				   if (cancelRequested) {
					   return;
				   }
				   setState(STATE_RECONNECTING, e.getLocalizedMessage());
				   try {
					   wait(RECONNECT_TIMEOUT_MS);
				   } catch(InterruptedException ie) {
					   return;
				   }
			   }
		   }

           mainloop: for (;!cancelRequested;) {

        	   /* Reconnect on error */
        	   while ((mConnectionState == STATE_RECONNECTING) && (!cancelRequested)) {
        		   setState(STATE_RECONNECTING, null, "Reconnecting to " + BluetoothReceiver.this.getName() + "...");
        		   try {
    				   connect();
    				   setState(STATE_CONNECTED, "Bluetooth connection reestablished sucessfully");
    			   }catch (IOException e) {
    				   Log.e(TAG, "reconnect() failed: " + e.getLocalizedMessage());
    				   setState(STATE_RECONNECTING, null, e.getLocalizedMessage());
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

        	   /* Read and process data from bluetooth GPS */
        	   try {
    			   mInputReader.loop();
        	   }  catch (IOException e) {
        		   if (!cancelRequested) {
        			   Log.e(TAG, "connection lost: " + e.getLocalizedMessage());
        			   try {
        	    		   mSocket.close();
        	    	   } catch (IOException e1) {
        	    		   Log.e(TAG, "close() failed", e1);
        	    	   }
        			   setState(STATE_RECONNECTING, "Bluetooth connection lost: " + e.getLocalizedMessage());
        		   }
        	   }
           } /* for(;!cancelRequested;) */
       } /* run() */

       private void connect() throws IOException {
    	   BluetoothSocket s;

    	   if (!mBtAdapter.isEnabled())
    		   throw(new IOException("Bluetooth disabled"));

    	   s = mBtDevice.createInsecureRfcommSocketToServiceRecord(UUID_SPP);
           try {
        	   s.connect();
        	   synchronized(this) {
        		   mSocket = s;
        		   mInputReader = new InputReader(s.getInputStream());
        		   mOutputStream = s.getOutputStream();
        	   }
           }catch (IOException e) {
        	   Log.e(TAG, "connect() failed: " +  e.getLocalizedMessage());
        	   try {
        		   s.close();
        	   }catch (IOException e2) {
        		   Log.e(TAG, "mSocket.close() failed: " + e2.getLocalizedMessage());
        	   }
        	   throw(e);
           }
       }

       /**
        * Write to the connected OutStream.
        * @param buffer  The bytes to write
        */
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
    	   BluetoothSocket s;
    	   synchronized(this) {
    		   cancelRequested = true;
    		   s = mSocket;
    		   this.notify();
    	   }
    	   if (s != null) {
    		   try {
    			   s.close();
    		   } catch (IOException e) {
    			   Log.e(TAG, "close() of connect socket failed", e);
    		   }
    	   }
       }

  	   /**
       * Set the current state of the connection
       * @param state  An integer defining the current connection state
       */
       @SuppressWarnings("unused")
       private void setState(int state) {
    	   setState(state, null, null);
       }

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
    		BluetoothReceiver.this.onConnectionStateChanged(oldState, state, toast, statusMessage);
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
