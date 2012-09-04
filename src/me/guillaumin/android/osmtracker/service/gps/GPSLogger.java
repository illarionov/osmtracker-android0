package me.guillaumin.android.osmtracker.service.gps;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Date;

import me.guillaumin.android.osmtracker.OSMTracker;
import me.guillaumin.android.osmtracker.R;
import me.guillaumin.android.osmtracker.activity.TrackLogger;
import me.guillaumin.android.osmtracker.db.DataHelper;
import me.guillaumin.android.osmtracker.db.TrackContentProvider;
import me.guillaumin.android.osmtracker.db.TrackContentProvider.Schema;
import me.guillaumin.android.osmtracker.gps.GpsSatellite;
import me.guillaumin.android.osmtracker.gps.GpsStatus;
import me.guillaumin.android.osmtracker.gps.GpsStatus.RawDataListener;
import me.guillaumin.android.osmtracker.gps.Receiver;
import me.guillaumin.android.osmtracker.gps.UsbReceiver;
import me.guillaumin.android.osmtracker.gps.ReceiverInterfaces;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationProvider;
import android.os.Binder;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.widget.Toast;

/**
 * GPS logging service.
 * 
 * @author Nicolas Guillaumin
 *
 */
public class GPSLogger extends Service implements
   SharedPreferences.OnSharedPreferenceChangeListener {

	/**
	 *  GPS provider disabled by user
	 */
	public final static String INTENT_PROVIDER_DISABLED =  "Gpslogger.intent.PROVIDER_DISABLED";

	/**
	 *  GPS provider enabled by user
	 */
	public final static String INTENT_PROVIDER_ENABLED =  "Gpslogger.intent.PROVIDER_ENABLED";

	/**
	 *  Status of GPS provider  changed
	 */
	public final static String INTENT_PROVIDER_STATUS_CHANGED =  "Gpslogger.intent.PROVIDER_STATUS_CHANGED";

	/**
	 *  Status of GPS receiver changed
	 */
	public final static String INTENT_GPS_STATUS_CHANGED =  "Gpslogger.intent.GPS_STATUS_CHANGED";

	/**
	 * New location
	 */
	public final static String INTENT_LOCATION_CHANGED =  "Gpslogger.intent.LOCATION_CHANGED";


	private static final String TAG = GPSLogger.class.getSimpleName();

	/**
	 * Data helper.
	 */
	private DataHelper dataHelper;

	/**
	 * GPS REceiver
	 */
	private Receiver gpsReceiver;

	/**
	 * NMEA Logger
	 */
	private RawDataLogger rawDataLogger;

	/**
	 * Wake lock that ensures that the CPU is running.
	 */
	private PowerManager.WakeLock wakeLock;

	/**
	 * Keeps the SharedPreferences
	 */
	private SharedPreferences preferences;

	/**
	 * Send intents to main activity
	 */
	private LocalBroadcastManager localBroadcastSender;

	/**
	 * Are we currently tracking ?
	 */
	private boolean isTracking = false;

	/**
	 * System notification id.
	 */
	private static final int NOTIFICATION_ID = 1;

	/**
	 * Last known location
	 */
	private final Location lastLocation = new Location("");

	private boolean locationAvailable = false;

	/**
	 * Last number of satellites used in fix.
	 */
	private int lastNbSatellites;

	/**
	 * Current Track ID
	 */
	private long currentTrackId = -1;

	/**
	 * the timestamp of the last GPS fix we used
	 */
	private long lastGPSTimestamp = 0;
	
	/**
	 * the interval (in ms) to log GPS fixes defined in the preferences
	 */
	private long gpsLoggingInterval;

	/**
	 * Is NMEA logging enabled ?
	 */
	private boolean isRawDataLogEnabled;

	/**
	 * Receives Intent for way point tracking, and stop/start logging.
	 */
	@SuppressLint("NewApi")
	private final BroadcastReceiver receiver = new BroadcastReceiver() {
		
		@Override
		public void onReceive(Context context, Intent intent) {
			Log.v(TAG, "Received intent " + intent.getAction());
			
			if (OSMTracker.INTENT_TRACK_WP.equals(intent.getAction())) {
				// Track a way point
				Bundle extras = intent.getExtras();
				if (extras != null) {
					Location l;
					Bundle b;
					// because of the gps logging interval our last fix could be very old
					// so we'll request the last known location from the gps provider
					l = gpsReceiver.getLastKnownLocation();
					b = l.getExtras();
					if (b != null)
						lastNbSatellites = b.getInt("satellites", lastNbSatellites);
					if(l != null){
						Long trackId = extras.getLong(Schema.COL_TRACK_ID);
						String uuid = extras.getString(OSMTracker.INTENT_KEY_UUID);
						String name = extras.getString(OSMTracker.INTENT_KEY_NAME);
						String link = extras.getString(OSMTracker.INTENT_KEY_LINK);
						dataHelper.wayPoint(trackId, l, lastNbSatellites, name, link, uuid);
						lastLocation.set(l);
						locationAvailable = true;
					}
				}
			} else if (OSMTracker.INTENT_UPDATE_WP.equals(intent.getAction())) {
				// Update an existing waypoint
				Bundle extras = intent.getExtras();
				if (extras != null) {
					Long trackId = extras.getLong(Schema.COL_TRACK_ID);
					String uuid = extras.getString(OSMTracker.INTENT_KEY_UUID);
					String name = extras.getString(OSMTracker.INTENT_KEY_NAME);
					String link = extras.getString(OSMTracker.INTENT_KEY_LINK);
					dataHelper.updateWayPoint(trackId, uuid, name, link);
				}
			} else if (OSMTracker.INTENT_DELETE_WP.equals(intent.getAction())) {
				// Delete an existing waypoint
				Bundle extras = intent.getExtras();
				if (extras != null) {
					String uuid = extras.getString(OSMTracker.INTENT_KEY_UUID);
					dataHelper.deleteWayPoint(uuid);
				}
			} else if (OSMTracker.INTENT_START_TRACKING.equals(intent.getAction()) ) {
				Bundle extras = intent.getExtras();
				if (extras != null) {
					Long trackId = extras.getLong(Schema.COL_TRACK_ID);
					startTracking(trackId);
				}
			} else if (OSMTracker.INTENT_STOP_TRACKING.equals(intent.getAction()) ) {
				stopTrackingAndSave();
			}
		}
	};

	/**
	 * Binder for service interaction
	 */
	private final IBinder binder = new GPSLoggerBinder();

	private final LocationListener locationListener = new LocationListener()  {
		@Override
		public void onLocationChanged(Location location) {

			// first of all we check if the time from the last used fix to the current fix is greater than the logging interval
			if((lastGPSTimestamp + gpsLoggingInterval) < System.currentTimeMillis()){
				Bundle b;
				lastGPSTimestamp = System.currentTimeMillis(); // save the time of this fix

				lastLocation.set(location);
				locationAvailable = true;
				b = lastLocation.getExtras();
				if (b != null)
					lastNbSatellites = b.getInt("satellites", lastNbSatellites); 

				Intent intent = new Intent(GPSLogger.INTENT_LOCATION_CHANGED);
				intent.putExtra("location", location);
				localBroadcastSender.sendBroadcast(intent);

				if (isTracking) {
					dataHelper.track(currentTrackId, location);
				}
			}
		}

		@Override
		public void onProviderDisabled(String provider) {
			Intent intent = new Intent(GPSLogger.INTENT_PROVIDER_DISABLED);
			localBroadcastSender.sendBroadcast(intent);
			locationAvailable = false;
		}

		@Override
		public void onProviderEnabled(String provider) {
			Intent intent = new Intent(GPSLogger.INTENT_PROVIDER_ENABLED);
			localBroadcastSender.sendBroadcast(intent);
		}

		@Override
		public void onStatusChanged(String provider, int status, Bundle extras) {
			String toast;

			if (status == LocationProvider.OUT_OF_SERVICE
					|| status == LocationProvider.TEMPORARILY_UNAVAILABLE
					)
				locationAvailable = false;
			else {
				lastNbSatellites = extras.getInt("satellites", lastNbSatellites);
			}

			Intent intent = new Intent(GPSLogger.INTENT_PROVIDER_STATUS_CHANGED);
			intent.putExtra("status", status);
			intent.putExtras(extras);
			localBroadcastSender.sendBroadcast(intent);

			toast = extras.getString("toast");
			if (toast != null) {
				Toast t = Toast.makeText(GPSLogger.this, toast, Toast.LENGTH_SHORT);
				t.show();
			}
		}

	};

	private final GpsStatus.Listener gpsStatusListener = new GpsStatus.Listener() {

		private GpsStatus gpsStatus = null;

		@Override
		public void onGpsStatusChanged(int event) {
			Intent intent;

			intent = new Intent(GPSLogger.INTENT_GPS_STATUS_CHANGED);
			intent.putExtra("event", event);

			if (event == GpsStatus.GPS_EVENT_SATELLITE_STATUS) {
				int visible, usedInFix;

				if (gpsReceiver == null) return;

				gpsStatus = gpsReceiver.getGpsStatus(gpsStatus);

				visible = usedInFix = 0;
				for (GpsSatellite s: gpsStatus.getSatellites()) {
					if (s.getSnr() > 0)  visible += 1;
					if (s.usedInFix()) usedInFix += 1;
				}
				//Log.d(TAG, "Satellite status. Visible: " + visible
					//	+ " used in fix: " + usedInFix);

				lastNbSatellites = usedInFix;
				intent.putExtra("visible", visible);
				intent.putExtra("usedInFix", usedInFix);
			}
			localBroadcastSender.sendBroadcast(intent);
		}

	};

	private class RawDataLogger {

		/**
		 * Raw data listener
		 */

		private RawDataListener rawDataListener;

		/**
		 * RAW log file
		 */
		File rawLogFile;

		/**
		 * Raw data file writer
		 */
		private BufferedOutputStream rawLog;

		private boolean isActive = false;


		private BroadcastReceiver mExternalStorageReceiver;

		private boolean mExternalStorageWriteable = false;

		RawDataLogger() {
			rawDataListener = new RawDataListener() {
				public void onRawDataReceived(final byte data[]) {
					RawDataLogger.this.onRawDataReceived(data);
				}
			};
		}

		private boolean openLog()
		{
			if (!mExternalStorageWriteable)
				return false;

			if (rawLog != null)
				return true;

			// Query for current track directory
			File trackDir = DataHelper.getTrackDirectory(currentTrackId);

			// Create the track storage directory if it does not yet exist
			if (!trackDir.exists()) {
				if ( !trackDir.mkdirs() ) {
					Log.w(TAG, "Directory [" + trackDir.getAbsolutePath() + "] does not exist and cannot be created");
					return false;
				}
			}

			// Ensure that this location can be written to
			if (trackDir.exists() && trackDir.canWrite()) {
				rawLogFile = new File(trackDir,
						DataHelper.FILENAME_FORMATTER.format(new Date()) + DataHelper.EXTENSION_RAW);
			} else {
				Log.w(TAG, "The directory [" + trackDir.getAbsolutePath() + "] will not allow files to be created");
				return false;
			}

			try {
				rawLog = new BufferedOutputStream(new FileOutputStream(rawLogFile, true));
			}catch (IOException e) {
				Log.w(TAG, "Failed to open log file " + rawLog  + ": " + e);
			}


			if (rawLog != null)
				Log.i(TAG, "Opened RAW log file " + rawLogFile.getAbsolutePath());


			return rawLog != null;
		}

		private void closeLog()
		{
			if (rawLog == null)
				return;

			try {
				rawLog.close();
				Log.i(TAG, "Closed RAW log file " + rawLogFile.getAbsolutePath());
			} catch (IOException e) {
				Log.w(TAG, "closeLogfile() error " + e);
			}

			rawLog = null;
			rawLogFile = null;
		}

		private void onRawDataReceived(final byte data[]) {

			if (!mExternalStorageWriteable)
				return;

			if ((rawLog == null)
					&& (openLog() == false))
				return;

			try {
				rawLog.write(data);
			} catch (IOException e) {
				Log.e(TAG, "rawLog.write() error " + e);
				closeLog();
			}
		}

		private void startWatchingExternalStorage()
		{
			mExternalStorageReceiver = new BroadcastReceiver() {
		        @Override
		        public void onReceive(Context context, Intent intent) {
		        	String action = intent.getAction();
		        	if (action.equals(Intent.ACTION_MEDIA_EJECT)) {
		        		Log.i(TAG, "got an EJECT for " + intent.getData() );
		        		closeLog();
		        	}else
		        		Log.i(TAG, "Storage " + intent.getData());
		            updateExternalStorageState();
		        }
		    };
		    IntentFilter filter = new IntentFilter();
		    filter.addAction(Intent.ACTION_MEDIA_MOUNTED);
		    filter.addAction(Intent.ACTION_MEDIA_EJECT);
		    filter.addDataScheme("file");
		    registerReceiver(mExternalStorageReceiver, filter);
		    updateExternalStorageState();
		}

		private void stopWatchingExternalStorage()
		{
			unregisterReceiver(mExternalStorageReceiver);
		}

		private void updateExternalStorageState()
		{
			String state = Environment.getExternalStorageState();
			mExternalStorageWriteable = Environment.MEDIA_MOUNTED.equals(state);
		}

		public void activate() {
			if (isActive)
				return;
			if (gpsReceiver == null)
				return;
			isActive = gpsReceiver.addRawDataListener(rawDataListener);
			if (isActive)
				startWatchingExternalStorage();
		}

		public void deactivate() {
			if (!isActive)
				return;
			stopWatchingExternalStorage();
			if (gpsReceiver != null)
				gpsReceiver.removeRawDataListener(rawDataListener);
			closeLog();
			isActive = false;
		}
	}


	/**
	 * Bind interface for service interaction
	 */
	public class GPSLoggerBinder extends Binder {

		/**
		 * Called by the activity when binding.
		 * Returns itself.
		 * @return the GPS Logger service
		 */
		public GPSLogger getService() {
			return GPSLogger.this;
		}
	}

	@Override
	public IBinder onBind(Intent intent) {
		Log.v(TAG, "Service onBind()");
		return binder;
	}
	
	@Override
	public boolean onUnbind(Intent intent) {
		// If we aren't currently tracking we can
		// stop ourselves
		if (! isTracking ) {
			Log.v(TAG, "Service self-stopping");
			stopSelf();
		}

		// We don't want onRebind() to be called, so return false.
		return false;
	}



	@Override
	public void onCreate() {	
		Log.v(TAG, "Service onCreate()");
		dataHelper = new DataHelper(this);

		preferences = PreferenceManager.getDefaultSharedPreferences(
				this.getApplicationContext());

		//read the logging interval from preferences
		gpsLoggingInterval = Long.parseLong(preferences.getString(
				OSMTracker.Preferences.KEY_GPS_LOGGING_INTERVAL, OSMTracker.Preferences.VAL_GPS_LOGGING_INTERVAL)) * 1000;

		// read if raw  data log is enabled
		isRawDataLogEnabled = preferences.getBoolean(OSMTracker.Preferences.KEY_GPS_LOG_RAW_DATA,
				OSMTracker.Preferences.VAL_GPS_LOG_RAW_DATA);

		// Register our broadcast receiver
		IntentFilter filter = new IntentFilter();
		filter.addAction(OSMTracker.INTENT_TRACK_WP);
		filter.addAction(OSMTracker.INTENT_UPDATE_WP);
		filter.addAction(OSMTracker.INTENT_DELETE_WP);
		filter.addAction(OSMTracker.INTENT_START_TRACKING);
		filter.addAction(OSMTracker.INTENT_STOP_TRACKING);
		registerReceiver(receiver, filter);

		/* Broadcast sender */
		localBroadcastSender = LocalBroadcastManager.getInstance(this);

		// Register ourselves for preferences changes
		preferences.registerOnSharedPreferenceChangeListener(this);

		rawDataLogger = new RawDataLogger();

		PowerManager pm = (PowerManager)getSystemService(
                Context.POWER_SERVICE);
		wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OsmtrackerServiceLock");

		/* Try to activate GPS */
		activateGpsReceiver();

		super.onCreate();
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		Log.v(TAG, "Service onStartCommand(-,"+flags+","+startId+")");
		startForeground(NOTIFICATION_ID, getNotification());
		return Service.START_STICKY;
	}

	@Override
	public void onDestroy() {
		Log.v(TAG, "Service onDestroy()");
		if (isTracking) {
			// If we're currently tracking, save user data.
			stopTrackingAndSave();
		}

		deactivateGpsReceiver();

		// Unregister preference change listener
		preferences.unregisterOnSharedPreferenceChangeListener(this);

		// Unregister broadcast receiver
		unregisterReceiver(receiver);

		// Cancel any existing notification
		stopNotifyBackgroundService();

		super.onDestroy();
	}

	/**
	 * Start GPS tracking.
	 */
	private boolean startTracking(long trackId) {
		currentTrackId = trackId;
		Log.v(TAG, "Starting track logging for track #" + trackId);

		if (gpsReceiver == null) {
			if (!activateGpsReceiver()) {
				Log.e(TAG, "Unable to activate GPS receiver");
			}
		}
		assert(gpsReceiver != null);

		// Start NMEA logging
		if (isRawDataLogEnabled)
			rawDataLogger.activate();

		// Lock CPU power
		// wakeLock.acquire();

		NotificationManager nmgr = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
		nmgr.notify(NOTIFICATION_ID, getNotification());
		isTracking = true;

		return isTracking;
	}

	/**
	 * Stops GPS Logging
	 */
	private void stopTrackingAndSave() {
		isTracking = false;
		locationAvailable = false;
		dataHelper.stopTracking(currentTrackId);

		rawDataLogger.deactivate();
		// wakeLock.release();
		currentTrackId = -1;
		this.stopSelf();
	}

	private boolean activateGpsReceiver()
	{
		ReceiverInterfaces ifaces;
		String prefKey, prefVal;
		String addr;

		ifaces = ReceiverInterfaces.valueOf(
				preferences.getString(
						OSMTracker.Preferences.KEY_GPS_INTERFACE,
						OSMTracker.Preferences.VAL_GPS_INTERFACE
						)
				);

		// XXX
		switch (ifaces) {
		case BUILTIN:
			prefKey = OSMTracker.Preferences.KEY_GPS_BUILTIN_RECEIVER;
			prefVal = OSMTracker.Preferences.VAL_GPS_BUILTIN_RECEIVER;
			break;
		case BLUETOOTH:
			prefKey = OSMTracker.Preferences.KEY_GPS_BLUETOOTH_RECEIVER;
			prefVal = null;
			break;
		case USB:
			prefKey = OSMTracker.Preferences.KEY_GPS_USB_RECEIVER;
			prefVal = null;
			break;
		default:
			prefKey = prefVal = null;
			assert(false);
			break;
		}

		assert(prefKey != null);
		addr = preferences.getString(prefKey, prefVal);
		if (addr == null) {
			/* Receiver not selected */
			return false;
		}

		gpsReceiver = ifaces.getInterface(this).getReceiver(addr);
		if (gpsReceiver instanceof me.guillaumin.android.osmtracker.gps.UsbReceiver) {
			me.guillaumin.android.osmtracker.gps.UsbReceiver r = (me.guillaumin.android.osmtracker.gps.UsbReceiver)gpsReceiver;
			int baudrate = Integer.parseInt(preferences.getString(
					OSMTracker.Preferences.KEY_GPS_USB_BAUDRATE,
					OSMTracker.Preferences.VAL_GPS_USB_BAUDRATE));
			r.setBaudRate(baudrate);
		}

		// Register ourselves for location updates
		gpsReceiver.requestLocationUpdates(0, 0, locationListener);
		gpsReceiver.addGpsStatusListener(gpsStatusListener);
		return true;
	}


	private void deactivateGpsReceiver()
	{
		// Unregister listener
		gpsReceiver.removeUpdates(locationListener);
		gpsReceiver.removeGpsStatusListener(gpsStatusListener);
		gpsReceiver = null;
	}

	/**
	 * Builds the notification to display when tracking in background.
	 */
	private Notification getNotification() {
		Notification n = new Notification(R.drawable.icon_greyed_25x25, getResources().getString(R.string.notification_ticker_text), System.currentTimeMillis());
			
		Intent startTrackLogger = new Intent(this, TrackLogger.class);
		startTrackLogger.putExtra(TrackContentProvider.Schema.COL_TRACK_ID, currentTrackId);
		PendingIntent contentIntent = PendingIntent.getActivity(this, 0, startTrackLogger, PendingIntent.FLAG_UPDATE_CURRENT);
		n.flags = Notification.FLAG_FOREGROUND_SERVICE | Notification.FLAG_ONGOING_EVENT | Notification.FLAG_NO_CLEAR;
		n.setLatestEventInfo(
				getApplicationContext(),
				getResources().getString(R.string.notification_title).replace("{0}", (currentTrackId > -1) ? Long.toString(currentTrackId) : "?"),
				getResources().getString(R.string.notification_text),
				contentIntent);
		return n;
	}
	
	/**
	 * Stops notifying the user that we're tracking in the background
	 */
	private void stopNotifyBackgroundService() {
		NotificationManager nmgr = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
		nmgr.cancel(NOTIFICATION_ID);
	}

	@Override
	public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key)
	{
		if (key.equals(OSMTracker.Preferences.KEY_GPS_LOGGING_INTERVAL)) {
			gpsLoggingInterval = Long.parseLong(sharedPreferences.getString(
					OSMTracker.Preferences.KEY_GPS_LOGGING_INTERVAL, OSMTracker.Preferences.VAL_GPS_LOGGING_INTERVAL)) * 1000;
		}else if (key.equals(OSMTracker.Preferences.KEY_GPS_LOG_RAW_DATA)) {
			isRawDataLogEnabled = sharedPreferences.getBoolean(OSMTracker.Preferences.KEY_GPS_LOG_RAW_DATA,
					OSMTracker.Preferences.VAL_GPS_LOG_RAW_DATA);

			if (isTracking) {
				if (isRawDataLogEnabled)
					rawDataLogger.activate();
				else
					rawDataLogger.deactivate();
			}
		}else if (key.equals(OSMTracker.Preferences.KEY_GPS_USB_BAUDRATE)) {
			if (gpsReceiver instanceof me.guillaumin.android.osmtracker.gps.UsbReceiver) {
				me.guillaumin.android.osmtracker.gps.UsbReceiver r = (me.guillaumin.android.osmtracker.gps.UsbReceiver)gpsReceiver;
				int baudrate = Integer.parseInt(preferences.getString(
						OSMTracker.Preferences.KEY_GPS_USB_BAUDRATE,
						OSMTracker.Preferences.VAL_GPS_USB_BAUDRATE));
				r.setBaudRate(baudrate);
			}
		}else if(key.equals(OSMTracker.Preferences.KEY_GPS_BUILTIN_RECEIVER)) {
			// TODO
			/*
			String newProvider = preferences.getString(
					OSMTracker.Preferences.KEY_GPS_PREFERRED_LOCATION_PROVIDER,
					OSMTracker.Preferences.VAL_GPS_PREFERRED_LOCATION_PROVIDER
					);

			 if (!newProvider.equals(locationProvider)) {
				lmgr.removeUpdates(this);
				locationProvider = newProvider;
				lmgr.requestLocationUpdates(locationProvider, 0, 0, this);
			}*/
		}
	}

	public void onUsbDeviceAttached(Intent intent) {
		if (gpsReceiver instanceof UsbReceiver) {
			UsbReceiver  r = (UsbReceiver)gpsReceiver;
			r.onUsbDeviceAttached(intent);
		}
	}

	/**
	 * Setter for isTracking
	 * @return true if we're currently tracking, otherwise false.
	 */
	public boolean isTracking() {
		return isTracking;
	}

	public final Location getCurrentLocation() {
		return locationAvailable ? lastLocation : null;
	}
}
