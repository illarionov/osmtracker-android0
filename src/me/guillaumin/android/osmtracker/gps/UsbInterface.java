/**
 * 
 */
package me.guillaumin.android.osmtracker.gps;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import android.annotation.TargetApi;
import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;


/**
 * @author Alexey Illarionov
 *
 */
@TargetApi(12)
public class UsbInterface extends ReceiverInterface {

	public static final String NAME = ReceiverInterfaces.USB.name();

	private UsbManager usb;
	private Context pContext;

	UsbInterface(Context pContext) {
		this.usb = (UsbManager)pContext.getSystemService(Context.USB_SERVICE);
		this.pContext = pContext;
	}

	@Override
	public boolean isAvailable() {
		return (this.usb != null);
	}

	@Override
	public Receiver getReceiver(String address) {
		return new UsbReceiver(pContext, this.usb, address);
	}

	public List<Receiver> getAllReceivers()
	{
		List<Receiver> adapters;
		HashMap<String, UsbDevice> deviceList;

		adapters = new ArrayList<Receiver>(3);
		adapters.add(new UsbReceiver(pContext, this.usb, UsbReceiver.DEVICE_ANY));
		deviceList = this.usb.getDeviceList();
		if (deviceList == null) return adapters;

		for (String d: deviceList.keySet()) {
			adapters.add(new UsbReceiver(pContext, this.usb, d));
		}

		return adapters;
	}


}
