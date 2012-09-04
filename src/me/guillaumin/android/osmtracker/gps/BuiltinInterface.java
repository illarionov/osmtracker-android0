/**
 *
 */
package me.guillaumin.android.osmtracker.gps;

import java.util.ArrayList;
import java.util.List;

import android.content.Context;
import android.location.LocationManager;

/**
 * @author Alexey Illarionov
 *
 */
public class BuiltinInterface extends ReceiverInterface {

	public static final String NAME = ReceiverInterfaces.BUILTIN.name();

	private LocationManager lm;

	BuiltinInterface(Context pContext) {
		this.lm = (LocationManager)pContext.getSystemService(Context.LOCATION_SERVICE);
	}

	@Override
	public boolean isAvailable() {
		return (lm != null && lm.getAllProviders().size() > 0);
	}

	@Override
	public Receiver getReceiver(String address) {
		return new BuiltinReceiver(lm, address);
	}

	public List<Receiver> getAllReceivers()
	{
		List<Receiver> receivers;
		List<String> providers;

		providers = this.lm.getProviders(false);
		receivers = new ArrayList<Receiver>(providers.size());

		for (String p: providers)
			receivers.add(getReceiver(p));

		return receivers;
	}

}
