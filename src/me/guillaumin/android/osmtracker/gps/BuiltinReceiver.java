/**
 *
 */
package me.guillaumin.android.osmtracker.gps;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;

import me.guillaumin.android.osmtracker.gps.GpsStatus.Listener;
import me.guillaumin.android.osmtracker.gps.GpsStatus.RawDataListener;

import android.annotation.TargetApi;
import android.location.GpsStatus;
import android.location.LocationListener;
import android.location.LocationManager;

import static junit.framework.Assert.*;

/**
 * @author Alexey Illarionov
 *
 */
@TargetApi(5)
public class BuiltinReceiver extends Receiver {

	private LocationManager lm;
	private String name;
	private android.location.GpsStatus tmpGpsStatus;

	private List<Nmea2RawListener> mListeners = new ArrayList<Nmea2RawListener>(1);
	private List<GpsStatusListener> mGpsStatusListeners = new ArrayList<GpsStatusListener>(1);

	protected BuiltinReceiver(LocationManager lm, String name) {
		assert(lm != null);
		assert(name != null);
		this.lm = lm;
		this.name = name;
		this.tmpGpsStatus = lm.getGpsStatus(null);
	}

	/* (non-Javadoc)
	 * @see me.guillaumin.android.osmtracker.gps.Receiver#getName()
	 */
	@Override
	public String getName() {
		return name;
	}

	/* (non-Javadoc)
	 * @see me.guillaumin.android.osmtracker.gps.Receiver#getAddress()
	 */
	@Override
	public String getAddress() {
		return name;
	}

	@Override
	public String toString() {
		return name;
	}

	public void requestLocationUpdates(long minTime, float minDistance, LocationListener listener) {
		lm.requestLocationUpdates(name,  minTime,  minDistance, listener);
	}

	public void removeUpdates(LocationListener listener) {
		lm.removeUpdates(listener);
	}


	@Override
	public boolean addRawDataListener (RawDataListener listener) {
		/* XXX */
		synchronized(this.mListeners) {
			Nmea2RawListener rdl;
			for (Nmea2RawListener h: mListeners) {
				if (h.rdl == listener) {
					return true;
				}
			}
			rdl = new Nmea2RawListener(listener);
			if (lm.addNmeaListener(rdl)) {
				this.mListeners.add(rdl);
			}else
				return false;
		}
		return true;
	 }

	@Override
	 public void removeRawDataListener (RawDataListener listener) {
		synchronized(this.mListeners) {
			for (Nmea2RawListener i: this.mListeners) {
				if (i.rdl == listener) {
					lm.removeNmeaListener(i);
					this.mListeners.remove(i);
					break;
				}
			}
		}
	 }

	@Override
	public boolean addGpsStatusListener(me.guillaumin.android.osmtracker.gps.GpsStatus.Listener listener) {
		GpsStatusListener l;
		synchronized(this.mListeners) {
			for (GpsStatusListener i: this.mGpsStatusListeners) {
				if (i.ourListener == listener) {
					return true;
				}
			}
			l = new GpsStatusListener(listener);
			if (lm.addGpsStatusListener(l)) {
				this.mGpsStatusListeners.add(l);
			}else
				return false;
			}
		return true;
	}

	@Override
	public void removeGpsStatusListener(Listener listener) {
		synchronized(this.mGpsStatusListeners) {
			for (GpsStatusListener i: this.mGpsStatusListeners) {
				if (i.ourListener == listener) {
					lm.removeGpsStatusListener(i);
					this.mGpsStatusListeners.remove(i);
					break;
				}
			}
		}
	}

	@Override
	public me.guillaumin.android.osmtracker.gps.GpsStatus getGpsStatus(
			me.guillaumin.android.osmtracker.gps.GpsStatus status) {
		if (status == null) status = new me.guillaumin.android.osmtracker.gps.GpsStatus();
		synchronized(this.tmpGpsStatus) {
			this.lm.getGpsStatus(this.tmpGpsStatus);
			status.setStatus(this.tmpGpsStatus);
		}
		return status;
	}

	private class Nmea2RawListener implements GpsStatus.NmeaListener {

		private RawDataListener rdl;

		public Nmea2RawListener(RawDataListener rdl) {
			this.rdl =rdl;
		}

		@Override
		public void onNmeaReceived(long timestamp, String nmea) {
			try {
				this.rdl.onRawDataReceived(nmea.getBytes(GpsInputReader.NMEA_CHARSET));
			} catch (UnsupportedEncodingException e) {
				fail();
			}
		}
	}

	private class GpsStatusListener implements android.location.GpsStatus.Listener {

		private me.guillaumin.android.osmtracker.gps.GpsStatus.Listener ourListener;

		public GpsStatusListener(me.guillaumin.android.osmtracker.gps.GpsStatus.Listener listener) {
			ourListener = listener;
		}

		@Override
		public void onGpsStatusChanged(int event) {
			ourListener.onGpsStatusChanged(event);
		}
	}

}
