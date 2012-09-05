package me.guillaumin.android.osmtracker.layout;

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
import android.support.v4.content.LocalBroadcastManager;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Layout for the GPS Status image and misc
 * action buttons.
 * 
 * @author Nicolas Guillaumin
 * 
 */
public class GpsStatusRecord extends LinearLayout {
	
	private final static String TAG = GpsStatusRecord.class.getSimpleName();
	
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
	private final GPSLoggerReceiver gpsLoggerReceiver;

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
	private final long gpsLoggingInterval;

	public GpsStatusRecord(Context context, AttributeSet attrs) {
		super(context, attrs);
		LayoutInflater.from(context).inflate(R.layout.gpsstatus_record, this, true);

		//read the logging interval from preferences
		gpsLoggingInterval = Long.parseLong(PreferenceManager.getDefaultSharedPreferences(context).getString(
				OSMTracker.Preferences.KEY_GPS_LOGGING_INTERVAL, OSMTracker.Preferences.VAL_GPS_LOGGING_INTERVAL)) * 1000;

		 gpsLoggerReceiver = new GPSLoggerReceiver(context);
	}

	public void requestLocationUpdates(boolean request) {
		if (request) {
			gpsLoggerReceiver.register();
		} else {
			gpsLoggerReceiver.unregister();
		}
	}

	/**
	 * Manages the state of the recording indicator, depending if we're tracking or not.
	 * @param isTracking true if the indicator must show that we're tracking, otherwise false
	 */
	public void manageRecordingIndicator(boolean isTracking) {
		ImageView recordStatus = (ImageView) findViewById(R.id.gpsstatus_record_animRec);
		if (isTracking) {
			recordStatus.setImageResource(R.drawable.record_red);
		} else {
			recordStatus.setImageResource(R.drawable.record_grey);
		}
	}

	private class GPSLoggerReceiver extends BroadcastReceiver {

		private Context ctx;

		public GPSLoggerReceiver(Context ctx) {
			this.ctx = ctx;
		}

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
			LocalBroadcastManager.getInstance(ctx
					).registerReceiver(this, this.createIntentFilter());
		}

		public void unregister() {
			LocalBroadcastManager.getInstance(ctx
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

		@Override
		public void onReceive(Context context, Intent intent) {
			String action = intent.getAction();
			ImageView imgSatIndicator = (ImageView) findViewById(R.id.gpsstatus_record_imgSatIndicator);
			TextView tvAccuracy = (TextView) findViewById(R.id.gpsstatus_record_tvAccuracy);

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
