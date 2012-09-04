/**
 *
 */
package me.guillaumin.android.osmtracker.gps;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import me.guillaumin.android.osmtracker.OSMTracker;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationProvider;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.text.format.Time;
import android.util.Log;
import static junit.framework.Assert.*;

/**
 * @author Alexey Illarionov
 *
 */
public class ReceiverInternalState {

	// Debugging
	private static final String TAG = ReceiverInternalState.class.getSimpleName();
	private static final boolean D = OSMTracker.DEBUG;

	/**
	 * Knots to meters per second
	 */
	private static final float KNOTS_TO_MPS = 0.514444f;

	private String providerName;

	final private NmeaFix nmeaFix = new NmeaFix();
	final private SirfFix sirfFix = new SirfFix();
	final private Location lastKnownLocation = new Location("");
	final private GpsStatus lastGpsStatus = new GpsStatus();
	private boolean hasLastKnownLocation = false;
	private boolean isCurrentLocation = false;

	// LocationProvider status
    private int mProviderStatus;

	// Handlers for processing events.
    final private LocationListenerTransporter mLocationListeners;
    final private GpsStatusTransporter mGpsStatusListeners;


	public ReceiverInternalState() {
		this("");
	}

	public ReceiverInternalState(String name) {
		this.providerName = name;
		this.mProviderStatus = LocationProvider.OUT_OF_SERVICE;
		this.mLocationListeners = new LocationListenerTransporter(name);
		this.mGpsStatusListeners = new GpsStatusTransporter();
	}

	public synchronized void requestLocationUpdates(long minTime, float minDistance, final LocationListener listener) {
		mLocationListeners.requestLocationUpdates(minTime, minDistance, listener);
	}

	public synchronized void removeUpdates(LocationListener listener) {
		mLocationListeners.removeUpdates(listener);
	}

	public synchronized boolean addGpsStatusListener(GpsStatus.Listener listener) {
		mGpsStatusListeners.addGpsStatusListener(listener);
		return true;
	}

	public synchronized void removeGpsStatusListener (GpsStatus.Listener listener) {
		mGpsStatusListeners.removeGpsStatusListener(listener);
	}

	public synchronized boolean hasListeners() {
		return ( this.mGpsStatusListeners.hasListeners()
				|| this.mLocationListeners.hasListeners());
	}


	public boolean putNmeaMessage(String msg) {
		return nmeaFix.putMessage(msg);
	}

	public boolean putSirfMessage(final byte[] msg, int offset, int length) {
		return sirfFix.putMessage(msg, offset, length);
	}

	public Location getLastKnownLocation() {
		Location res;
		synchronized(lastKnownLocation) {
			if (hasLastKnownLocation) {
				res = new Location(lastKnownLocation);
			}else {
				res = null;
			}
		}
		return res;
	}

	public Location getCurrentLocation() {
		Location res;
		synchronized(lastKnownLocation) {
			if (hasLastKnownLocation && isCurrentLocation) {
				res = new Location(lastKnownLocation);
			}else {
				res = null;
			}
		}
		return res;
	}

	public GpsStatus getGpsStatus(GpsStatus dst) {
		if (dst == null) {
			dst = new GpsStatus();
		}
		synchronized(lastGpsStatus) {
			dst.setStatus(lastGpsStatus);
		}
		return dst;
	}

	synchronized void providerEnabled() {
		if (mProviderStatus == LocationProvider.OUT_OF_SERVICE)
			mProviderStatus = LocationProvider.TEMPORARILY_UNAVAILABLE;
		mLocationListeners.onProviderEnabled();
	}

	synchronized void providerDisabled() {
		mProviderStatus = LocationProvider.OUT_OF_SERVICE;
		mLocationListeners.onProviderDisabled();
	}

	synchronized void transportStatusChanged(int newProviderStatus, String toast, String statusMessage) {
		mProviderStatus = newProviderStatus;
		mLocationListeners.onStatusChanged(newProviderStatus, toast, statusMessage);
	}

	private void setNewLocation(final Location l) {
		synchronized(lastKnownLocation) {
			if (l != null) {
				lastKnownLocation.set(l);
				hasLastKnownLocation = true;
				isCurrentLocation = true;
				if (D) Log.v(TAG, "New location: " + l.toString());
				/* Update status */
				if (hasLastKnownLocation == false) {
					mGpsStatusListeners.onGpsStatusChanged(GpsStatus.GPS_EVENT_FIRST_FIX);
					hasLastKnownLocation = true;
				}

				if (mProviderStatus != LocationProvider.AVAILABLE) {
					mProviderStatus = LocationProvider.AVAILABLE;
					mLocationListeners.onStatusChanged(mProviderStatus, null, "Location received");
				}

				mLocationListeners.onLocationChanged(lastKnownLocation);
			}else {
				if (isCurrentLocation) {
					if (D) Log.v(TAG, "Location lost");
					isCurrentLocation = false;
				}
				/* Update status */
				if (mProviderStatus == LocationProvider.AVAILABLE) {
					mProviderStatus = LocationProvider.TEMPORARILY_UNAVAILABLE;
					mLocationListeners.onStatusChanged(mProviderStatus, null, "Location lost");
				}
			}
		}
	}

	private void setNewGpsStatus(int svCount,
			int[] prns, float[] snrs, float[] elevations,
			float[] azimuths, int ephemerisMask,
			int almanacMask, int usedInFixMask) {
		synchronized(lastGpsStatus) {
			lastGpsStatus.setStatus(svCount > prns.length ? prns.length : svCount,
					prns,
					snrs,
					elevations,
					azimuths,
					ephemerisMask,
					almanacMask,
					usedInFixMask);
			mGpsStatusListeners.onGpsStatusChanged(GpsStatus.GPS_EVENT_SATELLITE_STATUS);
		}
	}

	private class NmeaFix {

		/* Current epoch */
		final NmeaFixTime currentTime = new NmeaFixTime();
		final Time nmeaDateTime = new Time("UTC");
		final Location currentLocation = new Location("");

		boolean epochClosed;

		/* GPGGA */
		boolean hasGga;

		/**
		 * Fix quality
		 */
		int ggaFixQuality;

		/**
		 * Number of satellites in use (not those in view)
		 */
		int ggaNbSat;

		/**
		 * Height of geoid above WGS84 ellipsoid
		 */
		double ggaGeoidHeight;

		/* GPRMC */
		boolean hasRmc;

		/**
		 * Date of fix
		 */
		int rmcDdmmyy;

		/**
		 * GPRMC Status true - active (A), false - void (V).
		 */
		boolean rmcStatusIsActive;


		/* GPGSA */
		boolean hasGsa;
		int gsaFixType;
		final int gsaPrn[] = new int[12];
		float gsaPdop;
		float gsaHdop;
		float gsaVdop;

		/* GPGSV parser state */
		private final NmeaGsvParser gsvParser = new NmeaGsvParser();

		public NmeaFix() {
			reset();
		}

		void reset() {
			currentTime.reset();

			this.hasGga = false;
			this.ggaFixQuality = -1;
			this.ggaNbSat = -1;

			this.hasRmc = false;
			this.rmcDdmmyy = -1;
			this.rmcStatusIsActive = false;

			this.hasGsa = false;

			this.nmeaDateTime.setToNow();
			this.currentLocation.reset();
			this.currentLocation.setProvider(providerName);
			this.epochClosed = true;
		}

		private void openEpoch(NmeaFixTime t) {
			currentTime.set(t);
			currentLocation.reset();
			currentLocation.setProvider(providerName);
			hasGga = hasRmc = false;
			epochClosed = false;
		}

		private boolean prepareEpoch(NmeaFixTime fixTime) {
			if (currentTime.isCurrentEpoch(fixTime)) {
				if (epochClosed) {
					return false;
				}
			}else {
				/* new epoch */
				if (!epochClosed) {
					closeEpoch(true);
				}
				openEpoch(fixTime);
			}
			return true;
		}

		private void closeEpoch(boolean force) {
			int yyyy, mm, dd;
			boolean locationValid;

			/* No GPGGA/GPRMC sentences received */
			if (!hasGga && !hasRmc) {
				epochClosed = true;
				return;
			}

			if (epochClosed && !force)
				return;

			/* wait for GGA and RMC messages */
			if (!force && (!hasGga || !hasRmc) )
				return;

			if (this.hasRmc && this.rmcStatusIsActive && (this.rmcDdmmyy > 0)) {
				yyyy = nmeaDateTime.year;
				yyyy = yyyy - yyyy % 100 + rmcDdmmyy % 100;
				mm = rmcDdmmyy / 100 % 100;
				dd = rmcDdmmyy / 10000 % 100;
			}else {
				yyyy = nmeaDateTime.year;
				mm = nmeaDateTime.month;
				dd = nmeaDateTime.monthDay;
			}

			nmeaDateTime.set(
					currentTime.getSecond(),
					currentTime.getMinute(),
					currentTime.getHour(),
					dd,
					mm,
					yyyy);

			currentLocation.setTime(nmeaDateTime.toMillis(true) + currentTime.mss);

			if (this.hasGga && this.hasRmc) {
				locationValid = ( (this.ggaFixQuality != 0) && (this.rmcStatusIsActive) );
			}else if (this.hasGga){
				locationValid = this.ggaFixQuality != 0;
			}else {
				assertTrue(this.hasRmc);
				locationValid = this.rmcStatusIsActive;
			}

			if (locationValid) {
				/* Update bundle */
				if (this.hasGga || this.hasGsa) {
					int fields = 0;
					Bundle extras;

					/* Number of satellites used in current solution */
					int satellites = -1;
					if (this.hasGga)
						satellites = this.ggaNbSat;
					if ((satellites < 0) && this.hasGsa) {
						satellites = 0;
						for(int prn: gsaPrn) { if (prn > 0) satellites += 1; }
					}

					extras = new Bundle(5);
					if (satellites >= 0) {
						fields += 1;
						extras.putInt("satellites", satellites);
					}
					if (hasGga) {
						if (!Double.isNaN(ggaGeoidHeight)) {
							extras.putDouble("geoidheight", ggaGeoidHeight);
						}
					}
					if (hasGsa) {
						if (!Float.isNaN(gsaHdop)) {
							fields += 1;
							extras.putFloat("HDOP", gsaHdop);
						}
						if (!Float.isNaN(gsaVdop)) {
							fields += 1;
							extras.putFloat("VDOP", gsaVdop);
						}
						if (!Float.isNaN(gsaPdop)) {
							fields += 1;
							extras.putFloat("PDOP", gsaPdop);
						}
					}

					if (fields > 0) currentLocation.setExtras(extras);
				}

				setNewLocation(currentLocation);
			}else {
				/* Location lost */
				this.hasGsa = false;
				setNewLocation(null);
			}

			epochClosed = true;
		}

		private int nmeaFieldCount(String msg, int start) {
			int pos;
			final int end = msg.length();
			int i;

			i=0;
			pos=start;
			while (pos < end) {
				pos = msg.indexOf(',', pos);
				if (pos < 0)
					break;
				else {
					pos += 1;
					i += 1;
				}
			};
			return i+1;
		}

		private double parseNmeaDegrees(String s, boolean oppositeDirection) throws NumberFormatException{
			double tmp;
			double res;

			if (s.length() < 1) return Double.NaN;
			tmp = Double.parseDouble(s);

			/* Degrees */
			res = Math.floor(tmp/100.0);

			/* Minutes */
			res = res + (tmp - 100.0 * res) / 60.0;

			if (oppositeDirection) res = -res;

			return res;
		}

		private boolean parseGpgga(String msg) {
			int startPos, curPos;
			int fieldCount;
			final NmeaFixTime fixTime = new NmeaFixTime();
			double lat, lon, alt, geoidheight;
			float hdop;
			int fixQ, nbSat;

			startPos = "$GPGGA,".length();
			fieldCount = nmeaFieldCount(msg, startPos);
			if (fieldCount != 14) {
				Log.d(TAG, "Invalid field count in $GPGGA message: " + fieldCount + " - " + msg);
				return false;
			}

			try {
				/* Field 1. Time of fix */
				curPos = msg.indexOf(',', startPos);
				if ( curPos - startPos < 6) {
					Log.d(TAG, "Invalid time of fix in $GPGGA message - " + msg);
					return false;
				}
				fixTime.set(msg.substring(startPos, curPos));
			}catch(NumberFormatException ie) {
				Log.d(TAG, "Invalid time of fix in $GPGGA message - " + msg);
				return false;
			}

			try {
				String sLat;
				boolean northDirection = true;

				/* Field 2. Latitude */
				curPos = msg.indexOf(',', (startPos = curPos+1));
				sLat = msg.substring(startPos, curPos);


				/* Field 3. Latitude direction */
				curPos = msg.indexOf(',', (startPos = curPos+1));
				if ( (curPos - startPos >= 1)
						&& (msg.charAt(startPos) == 'S') 
						) {
					northDirection = false;
				}
				lat = parseNmeaDegrees(sLat, !northDirection);
			}catch(NumberFormatException ie) {
				Log.d(TAG, "Invalid latitude in $GPGGA message - " + msg);
				return false;
			}

			try {
				String sLon;
				boolean eastDirection = true;

				/* Field 4. Longitude */
				curPos = msg.indexOf(',', (startPos = curPos+1));
				sLon = msg.substring(startPos, curPos);

				/* Field 5. Longitude direction */
				curPos = msg.indexOf(',', (startPos = curPos+1));
				if ( (curPos - startPos >= 1)
						&& (msg.charAt(startPos) == 'W') 
						) {
					eastDirection = false;
				}
				lon = parseNmeaDegrees(sLon, !eastDirection);
			}catch(NumberFormatException ie) {
				Log.d(TAG, "Invalid longitude in $GPGGA message - " + msg);
				return false;
			}

			/* Field 6 fix quality */
			curPos = msg.indexOf(',', (startPos = curPos+1));
			if (curPos-startPos == 0) {
				fixQ = 1;
			}else if (curPos-startPos > 1) {
				Log.d(TAG, "Invalid fix quality in $GPGGA message - " + msg);
				return false;
			}else {
				fixQ = Character.digit(msg.charAt(startPos), 10);
				if (fixQ < 0) {
					Log.d(TAG, "Invalid fix quality in $GPGGA message - " + msg);
					return false;
				}
			}

			/* Field 7. Number of satellites being tracked */
			try {
				curPos = msg.indexOf(',', (startPos = curPos+1));
				if (curPos-startPos == 0) {
					nbSat = -1;
				}else {
					nbSat = Integer.parseInt(msg.substring(startPos, curPos));
					if (nbSat < 0) throw new NumberFormatException();
				}
			}catch(NumberFormatException ie) {
				Log.d(TAG, "Invalid number of tracked satellites in $GPGGA message - " + msg);
				return false;
			}

			/* Field 8. HDOP */
			try {
				curPos = msg.indexOf(',', (startPos = curPos+1));
				if (curPos-startPos == 0) {
					hdop = Float.NaN;
				}else {
					hdop = Float.parseFloat(msg.substring(startPos, curPos));
					if (hdop < 0) throw new NumberFormatException();
				}
			}catch(NumberFormatException ie) {
				Log.d(TAG, "Invalid HDOP in $GPGGA message - " + msg);
				return false;
			}

			/* Field 9, 10.  Altitude above mean sea level */
			try {
				curPos = msg.indexOf(',', (startPos = curPos+1));
				if (curPos-startPos == 0) {
					alt = Double.NaN;
				}else {
					alt = Double.parseDouble(msg.substring(startPos, curPos));
				}

				curPos = msg.indexOf(',', (startPos = curPos+1));
				if ( (curPos - startPos >= 1)
						&& (msg.charAt(startPos) != 'M')
						) {
					alt = Double.NaN;
				}
			}catch(NumberFormatException ie) {
				Log.d(TAG, "Invalid altitude $GPGGA message - " + msg);
				return false;
			}

			/* Field 11, 12. Geoid height */
			try {
				curPos = msg.indexOf(',', (startPos = curPos+1));
				if (curPos-startPos == 0) {
					geoidheight = Double.NaN;
				}else {
					geoidheight = Double.parseDouble(msg.substring(startPos, curPos));
				}

				curPos = msg.indexOf(',', (startPos = curPos+1));
				if ( (curPos - startPos >= 1)
						&& (msg.charAt(startPos) != 'M')
						) {
					geoidheight = Double.NaN;
				}
			}catch(NumberFormatException ie) {
				Log.d(TAG, "Invalid geoid height $GPGGA message - " + msg);
				return false;
			}

			/* Field 13, 14 not interested in */

			/* Handle received data */
			if (!prepareEpoch(fixTime)) {
				Log.d(TAG, "$GPRMC message from closed epoch - " + msg);
				return false;
			}

			this.hasGga = true;
			this.ggaFixQuality = fixQ;
			this.ggaGeoidHeight = geoidheight;
			if (fixQ != 0) {
				this.ggaNbSat = nbSat;
				currentLocation.setLatitude(lat);
				currentLocation.setLongitude(lon);
				if (!Double.isNaN(alt)) {
					double altEllips;
					/* Set ellipsoid altitude */
					altEllips = alt;
					if (!Double.isNaN(geoidheight))
						altEllips += geoidheight;
					currentLocation.setAltitude(altEllips);
				}else
					currentLocation.removeAltitude();
				if (!Float.isNaN(hdop)) {
					currentLocation.setAccuracy(hdop*(float)OSMTracker.HDOP_APPROXIMATION_FACTOR);
				}else {
					currentLocation.removeAccuracy();
				}
			}
			closeEpoch(false);

			return true;
		}

		private boolean parseGprmc(String msg) {
			int startPos, curPos;
			int fieldCount;
			int ddmmyy;
			double lat, lon;
			float speed, bearing;
			boolean statusIsActive;
			final NmeaFixTime fixTime = new NmeaFixTime();

			startPos = "$GPRMC,".length();
			fieldCount = nmeaFieldCount(msg, startPos);
			if (fieldCount < 11) {
				Log.d(TAG, "Invalid field count in $GPGGA message: " + fieldCount + " - " + msg);
				return false;
			}

			try {
				/* Field 1. Time of fix */
				curPos = msg.indexOf(',', startPos);
				if ( curPos - startPos < 6) {
					Log.d(TAG, "Invalid time of fix in $GPRMC message - " + msg);
					return false;
				}
				fixTime.set(msg.substring(startPos, curPos));
			}catch(NumberFormatException ie) {
				Log.d(TAG, "Invalid time of fix in $GPRMC message - " + msg);
				return false;
			}

			/* Field 2. Status */
			curPos = msg.indexOf(',', (startPos = curPos+1));
			if (curPos-startPos == 0) {
				statusIsActive = true;
			}else if (curPos-startPos > 1) {
				Log.d(TAG, "Invalid status in $GPRMC message - " + msg);
				return false;
			}else {
				if ((msg.charAt(startPos) != 'A')
						&& (msg.charAt(startPos) != 'V')) {
					statusIsActive = true;
					Log.v(TAG, "Unknown GPRMC status - " + msg);
				}else {
					statusIsActive = (msg.charAt(startPos) == 'A');
				}
			}

			try {
				String sLat;
				boolean northDirection = true;

				/* Field 3. Latitude */
				curPos = msg.indexOf(',', (startPos = curPos+1));
				sLat = msg.substring(startPos, curPos);

				/* Field 4. Latitude direction */
				curPos = msg.indexOf(',', (startPos = curPos+1));
				if ( (curPos - startPos >= 1)
						&& (msg.charAt(startPos) == 'S')
						) {
					northDirection = false;
				}
				lat = parseNmeaDegrees(sLat, !northDirection);
			}catch(NumberFormatException ie) {
				Log.d(TAG, "Invalid latitude in $GPRMC message - " + msg);
				return false;
			}

			try {
				String sLon;
				boolean eastDirection = true;

				/* Field 5. Longitude */
				curPos = msg.indexOf(',', (startPos = curPos+1));
				sLon = msg.substring(startPos, curPos);

				/* Field 6. Longitude direction */
				curPos = msg.indexOf(',', (startPos = curPos+1));
				if ( (curPos - startPos >= 1)
						&& (msg.charAt(startPos) == 'W')
						) {
					eastDirection = false;
				}
				lon = parseNmeaDegrees(sLon, !eastDirection);
			}catch(NumberFormatException ie) {
				Log.d(TAG, "Invalid longitude in $GPRMC message - " + msg);
				return false;
			}

			/* Field 7. Speed over the ground  */
			try {
				curPos = msg.indexOf(',', (startPos = curPos+1));
				if (curPos-startPos == 0) {
					speed = Float.NaN;
				}else {
					speed = Float.parseFloat(msg.substring(startPos, curPos)) * KNOTS_TO_MPS;
					if (speed < 0.0) throw new NumberFormatException();
				}
			}catch(NumberFormatException ie) {
				Log.d(TAG, "Invalid speed over ground in $GPRMC message - " + msg);
				return false;
			}

			/* Field 8. Track angle */
			try {
				curPos = msg.indexOf(',', (startPos = curPos+1));
				if (curPos-startPos == 0) {
					bearing = Float.NaN;
				}else {
					bearing = Float.parseFloat(msg.substring(startPos, curPos));
				}
			}catch(NumberFormatException ie) {
				Log.d(TAG, "Invalid track angle in $GPRMC message - " + msg);
				return false;
			}

			/* Field 9. Date */
			try {
				curPos = msg.indexOf(',', (startPos = curPos+1));
				if (curPos-startPos == 0) {
					ddmmyy = -1;
				}else {
					ddmmyy = Integer.parseInt(msg.substring(startPos, curPos), 10);
					if ((ddmmyy < 0) || ddmmyy > 311299) throw new NumberFormatException();
				}
			}catch(NumberFormatException ie) {
				Log.d(TAG, "Invalid date in $GPRMC message - " + msg);
				return false;
			}

			/* Field 10,11 magnetic variation */
			/* Handle received data */

			/* handle data */
			if (!prepareEpoch(fixTime)) {
					Log.d(TAG, "$GPRMC message from closed epoch - " + msg);
					return false;
			}

			this.hasRmc = true;
			this.rmcStatusIsActive = statusIsActive;
			if (statusIsActive) {
				this.rmcDdmmyy = ddmmyy;
				currentLocation.setLatitude(lat);
				currentLocation.setLongitude(lon);
				if (Float.isNaN(speed)) {
					currentLocation.removeSpeed();
				}else {
					currentLocation.setSpeed(speed);
				}

				if (Float.isNaN(bearing)) {
					currentLocation.removeBearing();
				}else {
					currentLocation.setBearing(bearing);
				}
			}
			closeEpoch(false);
			return true;
		}

		private boolean parseGpgsa(String msg) {
			int startPos, curPos;
			int fieldCount;
			int fixMode;
			float pdop, hdop, vdop;
			final int prns[] = new int[12];

			startPos = "$GPGSA,".length();
			fieldCount = nmeaFieldCount(msg, startPos);
			if (fieldCount < 17) {
				Log.d(TAG, "Invalid field count in $GPGSA message: " + fieldCount + " - " + msg);
				return false;
			}

			/* Field 1. Auto / Manual selection if 2D/3D fix */
			curPos = msg.indexOf(',', startPos);

			/* Field 2. Fix mode 1 - no fix, 2 - 2D fix, 3 - 3D fix*/
			curPos = msg.indexOf(',', (startPos = curPos+1));
			if (curPos - startPos == 0)
				fixMode = -1;
			else {
				fixMode = Character.digit(msg.charAt(startPos), 10);
				if (fixMode < 0) {
					Log.d(TAG, "Invalid 3D Fix field $GPGSA message: " + msg);
					return false;
				}
			}

			/* 12 PRNs */
			try {
				for (int i=0; i<12; ++i) {
					curPos = msg.indexOf(',', (startPos = curPos+1));
					if (curPos - startPos == 0)
						prns[i] = -1;
					else {
						prns[i] = Integer.parseInt(msg.substring(startPos, curPos));
					}
				}
			}catch(NumberFormatException ie) {
				Log.d(TAG, "Invalid PRN in $GPGSA message - " + msg);
				return false;
			}

			/* Field 15. PDOP */
			try {
				curPos = msg.indexOf(',', (startPos = curPos+1));
				if (curPos-startPos == 0) {
					pdop = Float.NaN;
				}else {
					pdop = Float.parseFloat(msg.substring(startPos, curPos));
				}
			}catch(NumberFormatException ie) {
				Log.d(TAG, "Invalid PDOP field in $GPGSA message - " + msg);
				return false;
			}

			/* Field 16. HDOP */
			try {
				curPos = msg.indexOf(',', (startPos = curPos+1));
				if (curPos-startPos == 0) {
					hdop = Float.NaN;
				}else {
					hdop = Float.parseFloat(msg.substring(startPos, curPos));
				}
			}catch(NumberFormatException ie) {
				Log.d(TAG, "Invalid HDOP field in $GPGSA message - " + msg);
				return false;
			}

			/* Field 17. VDOP */
			try {
				curPos = msg.indexOf(',', (startPos = curPos+1));
				if (curPos < 0) curPos = msg.length();
				if (curPos-startPos == 0) {
					vdop = Float.NaN;
				}else {
					vdop = Float.parseFloat(msg.substring(startPos, curPos));
				}
			}catch(NumberFormatException ie) {
				Log.d(TAG, "Invalid VDOP field in $GPGSA message - " + msg);
				return false;
			}

			this.hasGsa = true;
			this.gsaFixType = fixMode;
			this.gsaHdop = hdop;
			this.gsaPdop = pdop;
			this.gsaVdop = vdop;
			System.arraycopy(prns, 0, this.gsaPrn, 0, prns.length);
			if (D) Log.v(TAG, "$GPGSA. 3dfix: " + this.gsaFixType +
					" HDOP: " + this.gsaHdop + " PDOP: " + this.gsaPdop +
					" VDOP: " + this.gsaVdop + " PRNs: " + Arrays.toString(this.gsaPrn));

			return true;
		}

		private boolean parseGpzda(String msg) {
			int startPos, curPos;
			int fieldCount;
			int dd, mm, yyyy;
			final NmeaFixTime currentTime = new NmeaFixTime();

			startPos = "$GPZDA,".length();
			fieldCount = nmeaFieldCount(msg, startPos);
			if (fieldCount < 6) {
				Log.d(TAG, "Invalid field count in $GPZDA message: " + fieldCount + " - " + msg);
				return false;
			}

			try {
				/* Field 1. Current time  */
				curPos = msg.indexOf(',', startPos);
				if ( curPos - startPos < 6) {
					Log.d(TAG, "Invalid time in $GPZDA message - " + msg);
					return false;
				}
				currentTime.set(msg.substring(startPos, curPos));

				/* Field 2. Day */
				curPos = msg.indexOf(',', (startPos = curPos+1));
				if (curPos-startPos == 0) throw new NumberFormatException();
				dd = Integer.parseInt(msg.substring(startPos, curPos));
				if (dd < 1 || dd > 31) throw new NumberFormatException();

				/* Field 3. Month */
				curPos = msg.indexOf(',', (startPos = curPos+1));
				if (curPos-startPos == 0) throw new NumberFormatException();
				mm = Integer.parseInt(msg.substring(startPos, curPos));
				if (mm < 1 || mm > 12) throw new NumberFormatException();

				/* Field 4. Year */
				curPos = msg.indexOf(',', (startPos = curPos+1));
				if (curPos < 0) curPos = msg.length();
				if (curPos-startPos == 0) throw new NumberFormatException();
				yyyy = Integer.parseInt(msg.substring(startPos, curPos));
				if (yyyy < 1995) throw new NumberFormatException();

				nmeaDateTime.set(
						currentTime.getSecond(),
						currentTime.getMinute(),
						currentTime.getHour(),
						dd,
						mm,
						yyyy);

				if (D) Log.v(TAG, "$GPZDA received. New time: " + nmeaDateTime.format3339(false));

			}catch(NumberFormatException ie) {
				Log.d(TAG, "Invalid time in $GPZDA message - " + msg);
				return false;
			}
			return true;
		}

		private boolean putMessage(String msg) {
			int checksumPos;

			/* Trim checksum */
			checksumPos = msg.lastIndexOf('*');
			if (checksumPos >= 4) {
				msg = msg.substring(0, checksumPos);
			}

			if (msg.startsWith("$GPGGA,")) {
				return parseGpgga(msg);
			}else if(msg.startsWith("$GPRMC,")) {
				return parseGprmc(msg);
			}else if (msg.startsWith("$GPGSA,")) {
				return parseGpgsa(msg);
			}else if (msg.startsWith("$GPGSV,")) {
				return gsvParser.putNmeaGsvMessage(msg);
			}else if (msg.startsWith("$GPZDA,")) {
				return parseGpzda(msg);
			}else if (msg.startsWith("$GPGLL,")) {
				/* TODO: $GPGLL message */
			}else if (msg.startsWith("$GPVTG,")) {
				/* TODO: $GPVTG message */
			}else {
				if (D) Log.d(TAG, "Unknown NMEA data type. Msg: " + msg);
				return false;
			}

			return true;
		}

		/* GPGSV parser state */
		private class NmeaGsvParser {
			private static final int NUM_SATELLITES = 12;
			int lastPartNumber;
			int totalParts;
			int svsInView;
			int satPos;
			final int prn[] = new int[NUM_SATELLITES];
			final float elevation[] = new float[NUM_SATELLITES];
			final float azimuth[] = new float[NUM_SATELLITES];
			final float snr[] = new float[NUM_SATELLITES];

			public NmeaGsvParser() {
				reset();
			}

			public void reset() {
				lastPartNumber = totalParts = 0;
				satPos = 0;
			}

			public void onMessageCompleted() {
				synchronized(NmeaFix.this) {
					int usedInFixMask = 0;
					if (NmeaFix.this.hasGsa) {
						for (int prn: NmeaFix.this.gsaPrn) {
							usedInFixMask |= (1<<(prn-1));
						}
					}
					ReceiverInternalState.this.setNewGpsStatus(
							svsInView,
							prn,
							snr,
							elevation,
							azimuth,
							/* XXX */ 0xffffffff,
							/* XXX */ 0xffffffff,
							usedInFixMask
							);
				}
			}

			public boolean putNmeaGsvMessage(String msg) {
				int startPos, curPos;
				int fieldCount;
				int curSatCnt;
				int totalParts, partNumber, svsInView;

				startPos = "$GPGSV,".length();
				fieldCount = nmeaFieldCount(msg, startPos);
				if (fieldCount < 3) {
					Log.d(TAG, "Invalid field count in $GPGSV message: " + fieldCount + " - " + msg);
					reset();
					return false;
				}

				if ((fieldCount - 3) % 4 != 0) {
					Log.d(TAG, "Invalid field count in $GPGSV message (%4 != 0): " + fieldCount + " - " + msg);
					reset();
					return false;
				}

				try {
					boolean isFirstPart = (this.totalParts == 0);
					/* Field 1. Total number of parts in message */
					curPos = msg.indexOf(',', startPos);
					totalParts = Integer.parseInt(msg.substring(startPos, curPos));
					if (totalParts <= 0) throw new  NumberFormatException("Wrong total part number"); 

					if (isFirstPart) {
						this.totalParts = totalParts;
					}else {
						if (this.totalParts != totalParts) throw new NumberFormatException("Total part number changed");
					}

					/* Field 2. Part number */
					curPos = msg.indexOf(',', (startPos = curPos+1));
					partNumber = Integer.parseInt(msg.substring(startPos, curPos));
					if (partNumber <= 0) throw new  NumberFormatException("Wrong part number");

					if (++this.lastPartNumber != partNumber) {
						reset();
						if (partNumber == 1) {
							this.totalParts = totalParts;
							this.lastPartNumber = 1;
						}else {
							Log.d(TAG, "Unexpected part number in $GPGSV message - " + msg);
							return false;
						}
					}

					/* Field 3. Total number of SVS in view */
					curPos = msg.indexOf(',', (startPos = curPos+1));
					if (curPos < 0) curPos = msg.length();
					if (curPos-startPos == 0) throw new NumberFormatException("Total number of SVS in view not defined");
					svsInView = Integer.parseInt(msg.substring(startPos, curPos));
					if (svsInView < 0) throw new NumberFormatException("Total number of SVS < 0");
					if (isFirstPart) {
						this.svsInView = svsInView;
					}else {
						if (this.svsInView != svsInView) throw new NumberFormatException("Total number of SVS in view changed");
					}

					/* Field 4,5,6,7. PRN, elevation, azimuth, snr */
					curSatCnt = (fieldCount - 3) / 4;
					for(int i=0; i < curSatCnt; ++i) {
						int prn,  el, az, snr;
						curPos = msg.indexOf(',', (startPos = curPos+1));
						if (curPos == startPos) {
							/* PRN not defined. Skip record */
							curPos = msg.indexOf(',', (startPos = curPos+1));
							curPos = msg.indexOf(',', (startPos = curPos+1));
							curPos = msg.indexOf(',', (startPos = curPos+1));
						}else {
							/* PRN */
							prn = Integer.parseInt(msg.substring(startPos, curPos));
							if (prn <= 0) throw new NumberFormatException("Wrong PRN");

							/* Elevation */
							curPos = msg.indexOf(',', (startPos = curPos+1));
							if (curPos == startPos) el = -1;
							else {
								el = Integer.parseInt(msg.substring(startPos, curPos));
								if (el < 0 || el > 90) throw new NumberFormatException("Wrong elevation");
							}

							/* Azimuth */
							curPos = msg.indexOf(',', (startPos = curPos+1));
							if (curPos == startPos) az = -1;
							else {
								az = Integer.parseInt(msg.substring(startPos, curPos));
								if (az < 0 || az > 359) throw new NumberFormatException("Wrong azimuth");
							}

							/* SNR */
							curPos = msg.indexOf(',', (startPos = curPos+1));
							if (curPos < 0) curPos = msg.length();
							if (curPos == startPos) snr = -1;
							else {
								snr = Integer.parseInt(msg.substring(startPos, curPos));
								if (snr < 0 || snr > 100) throw new NumberFormatException("Wrong SNR");
							}


							int p = 0;
							while (p < this.satPos) {
								if (this.prn[p] == prn)
									break;
								else
									p += 1;
							}

							if (p < this.prn.length) {
								this.prn[p] = prn;
								this.elevation[p] = el;
								this.azimuth[p] = az;
								this.snr[p] = snr;
								if (this.satPos == p) this.satPos += 1;
							}
						}

						if (this.lastPartNumber == this.totalParts) {
							if (this.satPos < this.prn.length) {
								Arrays.fill(this.prn, satPos, this.prn.length, -1);
								Arrays.fill(this.elevation, satPos, this.elevation.length, -1);
								Arrays.fill(this.azimuth, satPos, this.azimuth.length, -1);
								Arrays.fill(this.snr, satPos, this.snr.length, -1);
							}
							onMessageCompleted();
						}
					}
				}catch(NumberFormatException ie) {
					Log.d(TAG, "Malformed $GPGSV message: " + ie.getLocalizedMessage() + " - " + msg, ie);
					reset();
					return false;
				}

				return true;
			}

		} /* class NmeaGsvParser */

	} /* class NmeaFix */

	private static class NmeaFixTime {
		int hhmmss;
		int mss;

		public NmeaFixTime() {
			reset();
		}

		public void reset() {
			set(-1, -1);
		}

		void set(NmeaFixTime t) {
			set(t.hhmmss, t.mss);
		}

		public void set(int hhmmss, int mss) {
			this.hhmmss = hhmmss;
			this.mss = mss;
		}

		private boolean isCurrentEpoch(NmeaFixTime t) {
			if (t.hhmmss != this.hhmmss)
				return false;
			if (Math.abs(t.mss - this.mss) > 200)
				return false;
			return true;
		}

		public int getHour() { return (hhmmss / 10000) % 100; }
		public int getMinute() { return (hhmmss / 100) % 100; }
		public int getSecond() { return hhmmss % 100; }

		public void set(String s) throws NumberFormatException {
			long dl;

			if (s.length() < 6) throw new NumberFormatException();

			dl = (long)(1000.0 * Double.parseDouble(s));
			hhmmss = (int)(dl/1000);
			mss = (int)(dl % 1000);
			/* XXX: validation too weak */
			if ((hhmmss < 0) || (hhmmss > 240000)) throw new NumberFormatException();
			if ( (hhmmss % 10000) >= 6000) throw new NumberFormatException();
			if (hhmmss % 100 > 60) throw new NumberFormatException();
		}
	} /* class NmeaFixTime */

	private class SirfFix {

		private static final int SIRF_NUM_CHANNELS = 12;

		final Time internalTime = new Time("UTC");
		final Location currentLocation = new Location("");

		private int lastUsedInFixMask = 0;

		private int satPos = 0;
		private final int prn[] = new int[SIRF_NUM_CHANNELS];
		private final float elevation[] = new float[SIRF_NUM_CHANNELS];
		private final float azimuth[] = new float[SIRF_NUM_CHANNELS];
		private final float snr[] = new float[SIRF_NUM_CHANNELS];

		/* 2-bytes bitmask */
		private short get2d(final byte msg[], int p) {
			return (short)((((short)msg[p] & 0xff) << 8) |
					((short)msg[p+1] & 0xff));
		}

		/* 2-bytes unsigned integer */
		private int get2u(final byte msg[], int p) {
			return (int)get2d(msg, p) ;
		}

		/* 4-bytes bitmask */
		private int get4d(final byte msg[], int p) {
			return (
					(((int)msg[p] & 0xff) << 24) |
					(((int)msg[p+1] & 0xff) << 16) |
					(((int)msg[p+2] & 0xff) << 8) |
					((int)msg[p+3] & 0xff));
		}

		/* 4-bytes unsigned integer */
		private long get4u(final byte msg[], int p) {
			return (long)get4d(msg, p) & 0xffff;
		}

		/* 4-bytes signed integer */
		private int get4s(final byte msg[], int p) {
			return get4d(msg, p);
		}

		private boolean parseGeodeticNavData(final byte msg[], int offset, int length) {
			int payloadSize;
			boolean isNavValid;
			int year, month, day, hour, minute, second, mss;
			double lat, lon, altEllips, altMSL;
			float speed, bearing;
			float ehpe, hdop;
			int satellites;
			Bundle b;

			payloadSize = get2u(msg, offset+2);

			if (payloadSize != 91) {
				Log.d(TAG, "parseGeodeticNavData() error: payloadSize != 91 -  " + payloadSize);
				return false;
			}

			/* Field 2. Navigation valid (2D) */
			isNavValid = get2d(msg, offset+5) == 0;
			// Field 3. Navigation type (2D)
			//navType = get2d(msg, start+7);
			// Field 4.  Extended week number (2U)
			//wn = get2u(msg, start+9);
			// Field 5. GPS time of week (4U)
			//tow = get4u(msg, start+11);
			// Field 6. UTC year
			year = get2u(msg, offset+15);
			// Field 7. UTC month
			month = msg[offset+17] & 0xff;
			// Field 8. UTC day
			day = msg[offset+18] & 0xff;
			// Field 9. UTC hour
			hour = msg[offset+19] & 0xff;
			// Field 10. UTC minute
			minute = msg[offset+20] & 0xff;
			// Field 11. UTC second (milliseconds)
			second = get2u(msg, offset+21);
			mss = second % 1000;
			second /= 1000;

			// Field 12. Bitmap of SVS used in solution. (4D)
			lastUsedInFixMask = get4d(msg, 23);
			// Field 13. Latitude (4S)
			lat = (double)get4s(msg, offset+27) * 1.0e-7;
			// Field 14. Longitude (4S)
			lon = (double)get4s(msg, offset+31) * 1.0e-7;
			// Field 15. Altitude from ellipsoid (4S)
			altEllips = (double)get4s(msg, offset+35) * 0.01;
			// Field 16. Altitude from Mean Sea Level (4S)
			altMSL = (double)get4s(msg, offset+39) * 0.01;
			// Field 17. Map datum (21 = WGS-84) (1U)
			// datum = msg[start+43];
			// Field 18. Speed over ground (2U)
			speed = (float)get2u(msg, offset+44) * 0.01f;
			// Field 19. Course over ground (2U)
			bearing = (float)get2u(msg, offset+46) * 0.01f;
			// Field 20. Not implemented magnetic variation (2S)
			// magvar = get2s(msg, start+48);
			// Field 21. Climb rate (2S)
			// climbRate = get2s(msg, start+50);
			// Field 22. Heading rate (2S)
			// headingRate = get2s(msg, start+52);
			// Field 23. Estimated horizontal position error (4U)
			ehpe = (float)get4u(msg, offset+54) * 0.01f;
			// Field 24. Estimated vertical position error (4U)
			// evpe = (float)get4u(msg, start+58) * 0.01f;
			// Field 25. Estimated time error (4U)
			// ete = (float)get4u(msg, start+62) * 0.01f;
			// Field 26. Estimated horizontal velocity error (2U)
			// ehve = (float)get2u(msg, start+66) * 0.01f;
			// Field 27. Clock bias (4S)
			// bias = (float)get4s(msg, start+68) * 0.01f;
			// Field 28. Clock bias error (4U)
			// biasErr = (float)get4u(msg, start+72) * 0.01f;
			// Field 29. Clock drift (4S)
			// drift = (float)get4s(msg, start+76) * 0.01f;
			// Field 30. Clock drift error (4U)
			// driftErr = (float)get4u(msg, start+80) * 0.01f;
			// Field 31. Distance (4U)
			// distance = get4u(msg, start+84);
			// Field 32. Distance error (2U)
			// distanceErr = get2u(msg, start+88);
			// Field 33. Heading error (2U)
			// headingErr = (float)get2u(msg, start+90) * 0.01f;
			// Field 34. number of SVS in fix (1U)
			satellites = msg[offset+92];
			// Field 35. HDOP (1U)
			hdop = (float)msg[offset+93] * 0.2f;
			// Field 36. Additional info (1D)
			// info = msg[start+94];

			if (!isNavValid) {
				setNewLocation(null);
				return true;
			}
			internalTime.set(second, minute, hour, day, month, year);
			currentLocation.setTime(internalTime.toMillis(true) + mss);
			currentLocation.setLatitude(lat);
			currentLocation.setLongitude(lon);
			currentLocation.setAltitude(altEllips);
			currentLocation.setSpeed(speed);
			currentLocation.setBearing(bearing);
			currentLocation.setAccuracy(ehpe);

			b = new Bundle(3);
			b.putInt("satellites", satellites);
			b.putFloat("HDOP", hdop);
			b.putDouble("geoidheight", altEllips - altMSL);
			currentLocation.setExtras(b);
			setNewLocation(currentLocation);

			return true;
		}


		private boolean parseMeasuredTrackerDataOut(final byte msg[], int offset, int length) {
			int payloadSize;
			int i;
			int ephemerisMask = 0;
			int almanacMask = 0;

			payloadSize = get2u(msg, offset+2);

			if (payloadSize != 8 + 15 * SIRF_NUM_CHANNELS) {
				Log.d(TAG, "parseGeodeticNavData() error: payloadSize != 8+15*SIRF_NUM_CHANNELS -  " + payloadSize);
				return false;
			}

			offset += 12;
			satPos=0;
			for (i=0; i<SIRF_NUM_CHANNELS; ++i, offset += 15) {
				float avgCNO = 0;
				int prn = (int)msg[offset+0] & 0xff;
				int state = get2d(msg, offset+3);
				if (prn != 0) {
					this.prn[satPos] = prn;
					this.azimuth[satPos] = (float)(msg[offset+1] & 0xff) * 1.5f;
					this.elevation[satPos] = (float)(msg[offset+2] & 0xff) * 0.5f;
					for (int j=0; j<10; ++j) {
						avgCNO += (float)(msg[offset+5+j] & 0xff);
					}
					avgCNO /= 10.0;
					this.snr[satPos] = avgCNO; 
					if ((state & 0x80) != 0) {
						ephemerisMask |= 1<<(prn-1);
						/* XXX */
						almanacMask |= 1<<(prn-1);
					}
					satPos += 1;
				}
			}

			ReceiverInternalState.this.setNewGpsStatus(
					satPos,
					prn,
					snr,
					elevation,
					azimuth,
					ephemerisMask,
					almanacMask,
					lastUsedInFixMask);

			return true;
		}


		private boolean putMessage(final byte msg[], int offset, int length) {
			int messageId;

			/* SiRF Message ID */
			messageId = msg[offset+4];
			switch (messageId) {
			case 4:
				return parseMeasuredTrackerDataOut(msg, offset, length);
			case 41:
				return parseGeodeticNavData(msg, offset, length);
			}

			return false;
		}
	} /* class SirfFix */

	/* Transport to the main activity thread */
	private static class LocationListenerTransporter {

		private static final int MESSAGE_PROVIDER_DISABLED = 0;
		private static final int MESSAGE_PROVIDER_ENABLED = 1;
		private static final int MESSAGE_LOCATION_CHANGED = 2;
		private static final int MESSAGE_STATUS_CHANGED = 3;

		private List<LocationListenerTransport> listeners;
		private final String receiverAddress;

		public LocationListenerTransporter(String receiverAddress) {
			this(1, receiverAddress);
		}

		public LocationListenerTransporter(int size, String receiverAddress) {
			listeners = new ArrayList<LocationListenerTransport>(size);
			this.receiverAddress = receiverAddress;
		}

		public void requestLocationUpdates(long minTime, float minDistance, final LocationListener listener) {
			if (listener == null)
				throw new IllegalArgumentException();

			synchronized (listeners) {
				for (LocationListenerTransport h: listeners) {
					if (h.listener == listener) return;
				}
				listeners.add(new LocationListenerTransport(listener, minTime, minDistance));
			}
		}

		public void removeUpdates(LocationListener listener) {
			if (listener == null) throw new IllegalArgumentException();

			synchronized (listeners) {
				Iterator<LocationListenerTransport> iter = listeners.iterator();
				while (iter.hasNext()) {
					if (iter.next().listener == listener) {
						iter.remove();
						break;
					}
				}
			}
		}

		public boolean hasListeners() {
			synchronized(listeners) {
				return !listeners.isEmpty();
			}
		}

		public void onProviderDisabled() {
			synchronized (	listeners) {
				for (LocationListenerTransport h: listeners) {
					h.handler.obtainMessage(MESSAGE_PROVIDER_DISABLED, null).sendToTarget();
				}
			}
		}

		public void onProviderEnabled() {
			synchronized (listeners) {
				for (LocationListenerTransport h: listeners) {
					h.handler.obtainMessage(MESSAGE_PROVIDER_ENABLED, null).sendToTarget();
				}
			}
		}

		public void onLocationChanged(final Location location) {
			synchronized (listeners) {
				for (LocationListenerTransport h: listeners) {
					if (h.minTime != 0) {
						if (Math.abs(location.getTime() - h.lastSendLocation.getTime()) <= h.minTime)
							continue;
					}

					if (h.minDistance != 0) {
						if (location.distanceTo(h.lastSendLocation) <= h.minDistance)
							continue;
					}

					if ((h.minTime != 0) || (h.minDistance != 0)) {
						h.lastSendLocation.set(location);
					}

					h.handler.obtainMessage(MESSAGE_LOCATION_CHANGED, location).sendToTarget();
				}
			}
		}

		public void onStatusChanged(int status, final String toast, final String statusMessage) {
			Bundle b = null;
			if (toast != null || (statusMessage != null)) {
				b = new Bundle(2);
				if (toast != null)  b.putString("toast", toast);
				if (statusMessage != null) b.putString("message", statusMessage);
			}
			// Give the new status to the Handlers
			synchronized (listeners) {
				for (LocationListenerTransport h: listeners) {
					h.handler.obtainMessage(MESSAGE_STATUS_CHANGED, status, -1, b).sendToTarget();
				}
			}
		}

		private class LocationListenerTransport implements Handler.Callback {

			private final LocationListener listener;
			private final Handler handler;
			private long minTime;
			private float minDistance;

			private Location lastSendLocation = new Location("");

			LocationListenerTransport(LocationListener listener, long minTime, float minDistance) {
				this.handler = new Handler(this);
				this.listener = listener;
				this.minDistance = 0;
				this.minTime = 0;
			}

			@Override
			public boolean handleMessage(Message msg) {
				Bundle b;

				switch (msg.what) {
				case MESSAGE_PROVIDER_DISABLED:
					listener.onProviderDisabled(receiverAddress);
					break;
				case MESSAGE_PROVIDER_ENABLED:
					listener.onProviderEnabled(receiverAddress);
					break;
				case MESSAGE_LOCATION_CHANGED:
					if (D) {
						assertNotNull(listener);
						assertNotNull(msg.obj);
					}
					Location location = new Location((Location) msg.obj);
					listener.onLocationChanged(location);
					break;
				case MESSAGE_STATUS_CHANGED:
					b = (Bundle) msg.obj;
					listener.onStatusChanged(receiverAddress, msg.arg1, b);
					break;
				default:
					return false;
				}
				return true;
			}
		}
	}

	private static class GpsStatusTransporter {

		private static final int MESSAGE_GPS_STATUS_CHANGED = 0;

		private List<GpsStatusTransport> listeners;

		public GpsStatusTransporter() {
			this(1);
		}

		public GpsStatusTransporter(int size) {
			listeners = new ArrayList<GpsStatusTransport>(size);
		}

		public boolean addGpsStatusListener(GpsStatus.Listener listener) {
			if (listener == null)
				throw new IllegalArgumentException();

			synchronized (listeners) {
				for (GpsStatusTransport h: listeners) {
					if (h.listener == listener) {
						return true;
					}
				}
				listeners.add(new GpsStatusTransport(listener));
			}

			return true;
		}

		public void removeGpsStatusListener (GpsStatus.Listener listener) {
			if (listener == null)
				throw new IllegalArgumentException();

			synchronized (listeners) {
				Iterator<GpsStatusTransport> iter = listeners.iterator();
				while (iter.hasNext()) {
					if (iter.next().listener == listener) {
						iter.remove();
						break;
					}
				}
			}
		}

		public boolean hasListeners() {
			synchronized(listeners) {
				return !listeners.isEmpty();
			}
		}

		public void onGpsStatusChanged(int event) {
			synchronized (listeners) {
				for (GpsStatusTransport h: listeners) {
					h.handler.obtainMessage(MESSAGE_GPS_STATUS_CHANGED, event, -1).sendToTarget();
				}
			}
		}

		private static class GpsStatusTransport implements Handler.Callback {
			private final GpsStatus.Listener listener;
			private final Handler handler;

			GpsStatusTransport(GpsStatus.Listener listener) {
				this.handler = new Handler(this);
				this.listener = listener;
			}

			@Override
			public boolean handleMessage(Message msg) {
				switch (msg.what) {
				case MESSAGE_GPS_STATUS_CHANGED:
					if (D) assertNotNull(listener);
					listener.onGpsStatusChanged(msg.arg1);
					break;
				default:
					return false;
				}
				return true;
			}
		}
	}

}
