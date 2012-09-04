/**
 *
 */
package me.guillaumin.android.osmtracker.gps;

import java.util.ArrayList;
import java.util.List;

/**
 * @author Alexey Illarionov
 *
 */
public class ReceiverInterface {

	public static final String NAME = "";

	public boolean isAvailable() {
		return false;
	}

	public List<Receiver> getAllReceivers() {
		return new ArrayList<Receiver>();
	}

	public Receiver getReceiver(String address) {
		return null;
	}

}
