/**
 *
 */
package me.guillaumin.android.osmtracker.gps;

import java.util.EnumSet;
import java.util.Set;

import me.guillaumin.android.osmtracker.R;
import android.content.Context;
import android.util.Log;

/**
 * @author Alexey Illarionov
 *
 */
public enum ReceiverInterfaces {

	/**
	 *  System Location provider
	 */
	BUILTIN(R.string.prefs_gps_inteface_builtin),

	/**
	 * GPS Receiver with bluetooth interface
	 */
	BLUETOOTH(R.string.prefs_gps_inteface_bluetooth),

	/**
	 * GPS Receiver with USB interface
	 */
	USB(R.string.prefs_gps_inteface_usb);


	public final int resId;

	private static final String TAG = ReceiverInterfaces.class.getSimpleName();

	ReceiverInterfaces(final int resId) {
		this.resId = resId;
	}

	public ReceiverInterface getInterface(Context pContext)
	{
		ReceiverInterface iface = null;

		switch (this) {
		case BUILTIN:
			iface = new BuiltinInterface(pContext);
			break;
		case BLUETOOTH:
			if ( android.os.Build.VERSION.SDK_INT >= 5)
				iface = new BluetoothInterface(pContext);
			break;
		case USB:
			if ( android.os.Build.VERSION.SDK_INT >= 12)
				iface = new UsbInterface(pContext);
			break;
		}

		if (iface == null)
			iface = new ReceiverInterface();

		return iface;
	}

	public static Set<ReceiverInterfaces> getAvailableInterfaces(final Context context)
	{
		final EnumSet<ReceiverInterfaces> interfaces = EnumSet.noneOf(ReceiverInterfaces.class);

		for (ReceiverInterfaces i : ReceiverInterfaces.values()) {
			if (i.getInterface(context).isAvailable())
				interfaces.add(i);
		}

		Log.i(TAG, "Supported interfaces: " + interfaces);

		return interfaces;
	}
};
