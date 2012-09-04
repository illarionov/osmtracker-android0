/**
 *
 */
package me.guillaumin.android.osmtracker.gps;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import me.guillaumin.android.osmtracker.gps.GpsStatus.RawDataListener;
import android.annotation.TargetApi;
import android.location.Location;
import android.location.LocationListener;
import android.os.Handler;
import android.os.Message;

/**
 * @author AlexeyIllarionov
 *
 */
public abstract class Receiver {

	public abstract String getName();

	public abstract String getAddress();

	public Location getLastKnownLocation() {
		return null;
	}


	public abstract void requestLocationUpdates(long minTime, float minDistance, LocationListener listener);

	public abstract void removeUpdates(LocationListener listener);

	public abstract boolean addRawDataListener (RawDataListener listener);

	public abstract void removeRawDataListener (RawDataListener listener);

	public abstract boolean addGpsStatusListener(GpsStatus.Listener listener);

	public abstract void removeGpsStatusListener (GpsStatus.Listener listener);

	public abstract GpsStatus getGpsStatus(GpsStatus status);


	/* Transport of raw data to the main activity thread */
	protected static class RawDataTransporter {

		private List<RawDataTransport> mRawDataListeners;

		public RawDataTransporter() {
			this(1);
		}

		public RawDataTransporter(int size) {
			mRawDataListeners = new ArrayList<RawDataTransport>(size);
		}


		public boolean addRawDataListener (RawDataListener listener) {
			if (listener == null)
				throw new IllegalArgumentException();

			synchronized (mRawDataListeners) {
				for (RawDataTransport h: mRawDataListeners) {
					if (h.rawDataListener == listener) {
						return true;
					}
				}
				mRawDataListeners.add(new RawDataTransport(listener));
			}

			return true;
		}

		public void removeRawDataListener(RawDataListener listener) {
			if (listener == null)
				throw new IllegalArgumentException();

			synchronized (mRawDataListeners) {
				Iterator<RawDataTransport> iter = mRawDataListeners.iterator();
				while (iter.hasNext()) {
					if (iter.next().rawDataListener == listener) {
						iter.remove();
						break;
					}
				}
			}
		}

		public boolean hasListeners() {
			synchronized(mRawDataListeners) {
				return !mRawDataListeners.isEmpty();
			}
		}

		@TargetApi(9)
		public void onRawDataReceived(final byte buf[], int offset, int length) {
			byte snd[] = null;
			synchronized (mRawDataListeners) {
				for (RawDataTransport h: mRawDataListeners) {
					if (snd == null) snd = Arrays.copyOfRange(buf, offset, offset+length);
					h.handler.obtainMessage(RawDataTransport.MESSAGE_RAW_DATA_RECEIVED, snd).sendToTarget();
				}
			}
		}

		protected class RawDataTransport implements Handler.Callback {

			// Message types sent from the BluetoothReceiverService Handler
			private static final int MESSAGE_RAW_DATA_RECEIVED = 0;

			RawDataListener rawDataListener;
			private final Handler handler;

			RawDataTransport(final RawDataListener listener) {
				this.handler = new Handler(this);
				this.rawDataListener = listener;
			}

			@Override
			public boolean handleMessage(Message msg) {
				switch (msg.what) {
				case MESSAGE_RAW_DATA_RECEIVED:
					rawDataListener.onRawDataReceived((byte [])msg.obj);
					break;
				default:
					return false;
				}
				return true;
			}
		}
	}

}
