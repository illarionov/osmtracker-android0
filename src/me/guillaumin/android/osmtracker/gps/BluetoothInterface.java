/**
 *
 */
package me.guillaumin.android.osmtracker.gps;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;


import android.annotation.TargetApi;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;

/**
 * @author Alexey Illarionov
 *
 */
@TargetApi(5)
public class BluetoothInterface extends ReceiverInterface {

	public static final String NAME = ReceiverInterfaces.BLUETOOTH.name();

	private BluetoothAdapter adapter;
	private Context pContext;

	@SuppressWarnings("unused")
	private static final String TAG = BluetoothInterface.class.getSimpleName();

	BluetoothInterface(Context pContext) {
		adapter = BluetoothAdapter.getDefaultAdapter();
		this.pContext = pContext;
	}

	@Override
	public boolean isAvailable() {
		return adapter != null;
	}

	@Override
	public Receiver getReceiver(String address) {
		BluetoothDevice d;
		d = adapter.getRemoteDevice(address);
		return new BluetoothReceiver(pContext, adapter, d);
	}

	public List<Receiver> getAllReceivers()
	{
		Set<BluetoothDevice> pairedDevices;
		ArrayList<Receiver> adapters;

		assert(adapter != null);
		pairedDevices = adapter.getBondedDevices();

		if (pairedDevices == null || (pairedDevices.isEmpty()))
			return new ArrayList<Receiver>();

		adapters = new ArrayList<Receiver>(pairedDevices.size());

		for (BluetoothDevice d: pairedDevices)
			adapters.add(new BluetoothReceiver(pContext, adapter, d));

		return adapters;
	}

}
