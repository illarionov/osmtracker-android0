/**
 * 
 */
package me.guillaumin.android.osmtracker.activity;

import java.text.DecimalFormat;

import me.guillaumin.android.osmtracker.OSMTracker;
import me.guillaumin.android.osmtracker.R;
import me.guillaumin.android.osmtracker.service.gps.GPSLogger;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.GpsStatus;
import android.location.Location;
import android.location.LocationProvider;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.app.Fragment;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;


public class GpsStatusRecordFragment extends Fragment {

	private final static String TAG = GpsStatusRecordFragment.class.getSimpleName();

	/**
	 * Formatter for accuracy display.
	 */
	private final static DecimalFormat ACCURACY_FORMAT = new DecimalFormat("0");
	
	/**
	 * Keeps matching between satellite indicator bars to draw, and numbers
	 * of satellites for each bars;
	 */
	private final static int[] SAT_INDICATOR_TRESHOLD = {2, 3, 4, 6, 8};

	/**
	 * Handles notifications from GPS logger service
	 */
	private final GPSLoggerReceiver gpsLoggerReceiver = new GPSLoggerReceiver();

	/**
	 * the timestamp of the last GPS fix we used
	 */
	private long lastGPSTimestampStatus = 0;

	/**
	 * the timestamp of the last GPS fix we used for location updates
	 */
	private long lastGPSTimestampLocation = 0;
	
	/**
	 * the interval (in ms) to log GPS fixes defined in the preferences
	 */
	private long gpsLoggingInterval;


	public GpsStatusRecordFragment ()  {}

	@Override
	public void onCreate(Bundle savedInstanceState) {
		// TODO Auto-generated method stub
		super.onCreate(savedInstanceState);
	}

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container,
			Bundle savedInstanceState) {

		//read the logging interval from preferences
		gpsLoggingInterval = Long.parseLong(PreferenceManager.getDefaultSharedPreferences(this.getActivity()).getString(
				OSMTracker.Preferences.KEY_GPS_LOGGING_INTERVAL, OSMTracker.Preferences.VAL_GPS_LOGGING_INTERVAL)) * 1000;

		// Inflate the layout for this fragment
		return inflater.inflate(R.layout.gpsstatus_record, container, false);
		//return inflater.inflate(R.layout.gpsstatus_record, container, true);
	}

	@Override
	public void onResume() {
		super.onResume();
		gpsLoggerReceiver.register();
	}

	@Override
	public void onPause() {
		gpsLoggerReceiver.unregister();
		super.onPause();
	}


	private class GPSLoggerReceiver extends BroadcastReceiver {

		public IntentFilter createIntentFilter() {
			IntentFilter filter = new IntentFilter();
			filter.addAction(GPSLogger.INTENT_PROVIDER_ENABLED);
			filter.addAction(GPSLogger.INTENT_PROVIDER_DISABLED);
			filter.addAction(GPSLogger.INTENT_PROVIDER_STATUS_CHANGED);
			filter.addAction(GPSLogger.INTENT_GPS_STATUS_CHANGED);
			filter.addAction(GPSLogger.INTENT_LOCATION_CHANGED);
			filter.addAction(GPSLogger.INTENT_TRACKING_STATUS_CHANGED);
		    return filter;
		}

		public void register() {
			LocalBroadcastManager.getInstance(GpsStatusRecordFragment.this.getActivity()
					).registerReceiver(this, this.createIntentFilter());
		}

		public void unregister() {
			LocalBroadcastManager.getInstance(GpsStatusRecordFragment.this.getActivity()
					).unregisterReceiver(this);
		}

		final int satCount2ResourceId(int satCount) {
			int nbBars;
			if (satCount < 0) return R.drawable.sat_indicator_unknown;

			nbBars=0;

			for (int i=0; i<SAT_INDICATOR_TRESHOLD.length; i++) {
				if (satCount >= SAT_INDICATOR_TRESHOLD[i]) {
					nbBars = i;
				}
			}
			Log.v(TAG, "Found " + satCount + " satellites. Will draw " + nbBars + " bars.");
			return getResources().getIdentifier("drawable/sat_indicator_" + nbBars,
					null, OSMTracker.class.getPackage().getName());
		}
		
		/**
		 * Manages the state of the recording indicator, depending if we're tracking or not.
		 * @param isTracking true if the indicator must show that we're tracking, otherwise false
		 */
		void manageRecordingIndicator(boolean isTracking) {
			ImageView recordStatus = (ImageView) GpsStatusRecordFragment.this.getView().findViewById(R.id.gpsstatus_record_animRec);
			if (isTracking) {
				recordStatus.setImageResource(R.drawable.record_red);
			} else {
				recordStatus.setImageResource(R.drawable.record_grey);
			}
		}


		@Override
		public void onReceive(Context context, Intent intent) {
			String action = intent.getAction();
			ImageView imgSatIndicator = (ImageView)  GpsStatusRecordFragment.this.getView().findViewById(R.id.gpsstatus_record_imgSatIndicator);
			TextView tvAccuracy = (TextView)  getView().findViewById(R.id.gpsstatus_record_tvAccuracy);

			Log.v(TAG, "Received local intent " + action);

			if (action.equals(GPSLogger.INTENT_PROVIDER_ENABLED)) {
				imgSatIndicator.setImageResource(R.drawable.sat_indicator_unknown);
			}else if (action.equals(GPSLogger.INTENT_PROVIDER_DISABLED)) {
				imgSatIndicator.setImageResource(R.drawable.sat_indicator_off);
				tvAccuracy.setText("");
			}else if (action.equals(GPSLogger.INTENT_PROVIDER_STATUS_CHANGED)) {
				int newStatus = intent.getIntExtra("status", -1);
				Bundle b = intent.getExtras();
				String msg = null;
				if (b != null) {
					msg = b.getString("message");
					if (msg == null) msg = b.getString("toast");
				}

				switch (newStatus) {
				case LocationProvider.OUT_OF_SERVICE:
					imgSatIndicator.setImageResource(R.drawable.sat_indicator_off);
					tvAccuracy.setText(msg != null ? msg : "");
					break;
				case LocationProvider.TEMPORARILY_UNAVAILABLE:
					imgSatIndicator.setImageResource(R.drawable.sat_indicator_unknown);
					tvAccuracy.setText(msg != null ? msg : "");
					break;
				case LocationProvider.AVAILABLE:
					if (msg != null) tvAccuracy.setText(msg);
					break;
				default:
					Log.w(TAG, "Unknown status " + newStatus + "on INTENT_STATUS_CHANGED");
				}
			}else if (action.equals(GPSLogger.INTENT_GPS_STATUS_CHANGED)) {
				int newStatus = intent.getIntExtra("event", -1);
				switch (newStatus) {
				case GpsStatus.GPS_EVENT_FIRST_FIX:
					imgSatIndicator.setImageResource(R.drawable.sat_indicator_0);
					break;
				case GpsStatus.GPS_EVENT_STARTED:
					imgSatIndicator.setImageResource(R.drawable.sat_indicator_unknown);
					break;
				case GpsStatus.GPS_EVENT_STOPPED:
					imgSatIndicator.setImageResource(R.drawable.sat_indicator_off);
					break;
				case GpsStatus.GPS_EVENT_SATELLITE_STATUS:
					int satCount;
					long curTime;

					curTime = System.currentTimeMillis();
					if (lastGPSTimestampStatus + gpsLoggingInterval >= curTime)
						return;

					satCount = intent.getIntExtra("visible", -1);
					imgSatIndicator.setImageResource(satCount2ResourceId(satCount));

					lastGPSTimestampStatus = curTime;
				}
			} else if (action.equals(GPSLogger.INTENT_LOCATION_CHANGED)) {
				Bundle b;
				Location location;
				int satCount;
				location = intent.getParcelableExtra("location");
				// first of all we check if the time from the last used fix to the current fix is greater than the logging interval
				if((lastGPSTimestampLocation + gpsLoggingInterval) < System.currentTimeMillis()){
					lastGPSTimestampLocation = System.currentTimeMillis(); // save the time of this fix
					Log.v(TAG, "Location received " + location);

					if (location.hasAccuracy()) {
						tvAccuracy.setText(getResources().getString(R.string.various_accuracy) + ": " + ACCURACY_FORMAT.format(location.getAccuracy()) + getResources().getString(R.string.various_unit_meters));
					} else {
						tvAccuracy.setText("");
					}
				}
				b = location.getExtras();
				if (b != null) {
					satCount = b.getInt("satellites", -1);
					if (satCount >= 0)
						imgSatIndicator.setImageResource(satCount2ResourceId(satCount));
				}
			} else if (action.equals(GPSLogger.INTENT_TRACKING_STATUS_CHANGED)) {
				boolean isTracked = intent.getBooleanExtra("isTracking", false);
				manageRecordingIndicator(isTracked);
			}
		}
	}

}
