package me.guillaumin.android.osmtracker.activity;

import java.io.File;
import java.io.FilenameFilter;
import java.util.List;
import java.util.Set;

import me.guillaumin.android.osmtracker.OSMTracker;
import me.guillaumin.android.osmtracker.R;
import me.guillaumin.android.osmtracker.gps.Receiver;
import me.guillaumin.android.osmtracker.gps.ReceiverInterface;
import me.guillaumin.android.osmtracker.gps.ReceiverInterfaces;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.os.Bundle;
import android.os.Environment;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceChangeListener;
import android.preference.Preference.OnPreferenceClickListener;
import android.preference.PreferenceActivity;
import android.preference.PreferenceCategory;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.util.Log;

/**
 * Manages preferences screen.
 * 
 * @author Nicolas Guillaumin
 *
 */
public class Preferences extends PreferenceActivity {

	private static final String TAG = Preferences.class.getSimpleName();
	
	/**
	 * Directory containing user layouts, relative to storage dir.
	 */
	public static final String LAYOUTS_SUBDIR = "layouts";
	
	/**
	 * File extension for layout files
	 */
	private static final String LAYOUT_FILE_EXTENSION = ".xml";

	private static final int GPS_SETTINGS_REQUEST = 0;
	private static final int BLUETOOTH_SETTINGS_REQUEST = 1;


	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		addPreferencesFromResource(R.xml.preferences);
		
		// Set summary of some preferences to their actual values
		// and register a change listener to set again the summary in case of change
		final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
		
		// External storage directory
		EditTextPreference storageDirPref = (EditTextPreference) findPreference(OSMTracker.Preferences.KEY_STORAGE_DIR);
		storageDirPref.setSummary(prefs.getString(OSMTracker.Preferences.KEY_STORAGE_DIR, OSMTracker.Preferences.VAL_STORAGE_DIR));
		storageDirPref.setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
			@Override
			public boolean onPreferenceChange(Preference preference, Object newValue) {
				// Ensure there is always a leading slash
				if (! ((String) newValue).startsWith(File.separator)) {
					newValue = File.separator + (String) newValue;
				}
				
				// Set summary with the directory value
				preference.setSummary((String) newValue);
				
				// Re-populate layout list preference
				populateLayoutPreference((String) newValue); 
				
				// Set layout to default layout
				((ListPreference) findPreference(OSMTracker.Preferences.KEY_UI_BUTTONS_LAYOUT)).setValue(OSMTracker.Preferences.VAL_UI_BUTTONS_LAYOUT);
				return true;
			}
		});
		populateLayoutPreference(storageDirPref.getText());

		// Voice record duration
		Preference pref = findPreference(OSMTracker.Preferences.KEY_VOICEREC_DURATION);
		pref.setSummary(prefs.getString(OSMTracker.Preferences.KEY_VOICEREC_DURATION, OSMTracker.Preferences.VAL_VOICEREC_DURATION) + " " + getResources().getString(R.string.prefs_voicerec_duration_seconds));
		pref.setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
			@Override
			public boolean onPreferenceChange(Preference preference, Object newValue) {
				// Set summary with the number of seconds, following by "seconds"
				preference.setSummary(newValue+ " " + getResources().getString(R.string.prefs_voicerec_duration_seconds));
				return true;
			}
		});
		
		// Update GPS logging interval summary to the current value
		pref = findPreference(OSMTracker.Preferences.KEY_GPS_LOGGING_INTERVAL);
		pref.setSummary(
				prefs.getString(OSMTracker.Preferences.KEY_GPS_LOGGING_INTERVAL, OSMTracker.Preferences.VAL_GPS_LOGGING_INTERVAL)
				+ " " + getResources().getString(R.string.prefs_gps_logging_interval_seconds)
				+ ". " + getResources().getString(R.string.prefs_gps_logging_interval_summary));
		pref.setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
			@Override
			public boolean onPreferenceChange(Preference preference, Object newValue) {
				// Set summary with the interval and "seconds"
				preference.setSummary(newValue
						+ " " + getResources().getString(R.string.prefs_gps_logging_interval_seconds)
						+ ". " + getResources().getString(R.string.prefs_gps_logging_interval_summary));
				return true;
			}
		});

		// Button screen orientation option
		pref = findPreference(OSMTracker.Preferences.KEY_UI_ORIENTATION);
		ListPreference orientationListPreference = (ListPreference) pref;
		String displayValueKey = prefs.getString(OSMTracker.Preferences.KEY_UI_ORIENTATION, OSMTracker.Preferences.VAL_UI_ORIENTATION);
		int displayValueIndex = orientationListPreference.findIndexOfValue(displayValueKey);
		String displayValue = orientationListPreference.getEntries()[displayValueIndex].toString();
		orientationListPreference.setSummary(displayValue + ".\n" 
				+ getResources().getString(R.string.prefs_ui_orientation_summary));
		
		// Set a listener to update the preference display after a change is made
		pref.setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
			@Override
			public boolean onPreferenceChange(Preference preference, Object newValue) {
				// Set summary with the display text of the item and a description of the preference
				ListPreference orientationListPreference = (ListPreference)preference;
				// Pull the display string from the list preference rather than simply using the key value
				int newValueIndex = orientationListPreference.findIndexOfValue((String)newValue);
				String newPreferenceDisplayValue = orientationListPreference.getEntries()[newValueIndex].toString();
				
				preference.setSummary(newPreferenceDisplayValue
						+ ".\n" + getResources().getString(R.string.prefs_ui_orientation_summary));
				return true;
			}
		});

		// Clear OSM data: Disable if there's no OSM data stored
		pref = findPreference(OSMTracker.Preferences.KEY_OSM_OAUTH_CLEAR_DATA);
		if (prefs.contains(OSMTracker.Preferences.KEY_OSM_OAUTH_TOKEN)
				&& prefs.contains(OSMTracker.Preferences.KEY_OSM_OAUTH_SECRET)) {
			pref.setEnabled(true);
		} else {
			pref.setEnabled(false);
		}
		pref.setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
			@Override
			public boolean onPreferenceChange(Preference preference, Object newValue) {
				// Clear data
				Editor editor = prefs.edit();
				editor.remove(OSMTracker.Preferences.KEY_OSM_OAUTH_TOKEN);
				editor.remove(OSMTracker.Preferences.KEY_OSM_OAUTH_SECRET);
				editor.commit();
				
				preference.setEnabled(false);
				return false;
			}
		});

		// GPS receiver interface
		populateGpsReceiverInterface();

	}


	private void populateGpsReceiverInterface()
	{
		String[] entries, entryValues;
		ListPreference lf;
		Set<ReceiverInterfaces> interfaces;
		int i;

		lf = (ListPreference) findPreference(OSMTracker.Preferences.KEY_GPS_INTERFACE);
		assert(lf != null);

		interfaces = ReceiverInterfaces.getAvailableInterfaces(this);
		assert(interfaces != null);
		assert(interfaces.contains(ReceiverInterfaces.BUILTIN));

		entries = new String[interfaces.size()];
		entryValues = new String[interfaces.size()];
		i=0;
		for(ReceiverInterfaces ri: interfaces) {
			entryValues[i] = ri.name();
			entries[i++] = this.getString(ri.resId);
		}

		lf.setEntries(entries);
		lf.setEntryValues(entryValues);
		if (lf.getValue() == null)
			lf.setValue(OSMTracker.Preferences.VAL_GPS_INTERFACE);

		toggleGpsReceiverInterface(ReceiverInterfaces.valueOf(lf.getValue()));

		lf.setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
			@Override
			public boolean onPreferenceChange(Preference preference, Object newValue) {
				toggleGpsReceiverInterface(ReceiverInterfaces.valueOf(newValue.toString()));
				return true;
			}
		});
	}

	private void toggleGpsReceiverInterface(ReceiverInterfaces newIface)
	{

		findPreference("gps.builtin.category").setEnabled(
				newIface == ReceiverInterfaces.BUILTIN);
		findPreference("gps.bluetooth.category").setEnabled(
				newIface == ReceiverInterfaces.BLUETOOTH);
		findPreference("gps.usb.category").setEnabled(
				newIface == ReceiverInterfaces.USB);

		switch (newIface) {
			case BUILTIN:
				populateBuiltinGpsPreference();
				break;
			case BLUETOOTH:
				populateBluetoothGpsPreference();
				break;
			case USB:
				populateUsbGpsPreference();
				break;
		}
	}

	/*
	 * Populates Built-in GPS preferences
	 */
	private void populateBuiltinGpsPreference() {
		Preference pref;

		// GPS settings
		pref = findPreference(OSMTracker.Preferences.KEY_GPS_OSSETTINGS);
		pref.setOnPreferenceClickListener(new OnPreferenceClickListener() {
			@Override
			public boolean onPreferenceClick(Preference preference) {
				startActivityForResult(
						new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS),
						GPS_SETTINGS_REQUEST
						);
				return true;
			}
		});

		updateLocationProvidersList();

	}

	private void updateLocationProvidersList()
	{
		ReceiverInterface receiver;
		ListPreference lf;
		List<Receiver> receivers;
		String entries[], values[];
		String selected;
		boolean containsSelected = false;

		receiver = ReceiverInterfaces.BUILTIN.getInterface(this);
		lf = (ListPreference) findPreference(OSMTracker.Preferences.KEY_GPS_BUILTIN_RECEIVER);
		selected = lf.getSharedPreferences().getString(lf.getKey(), null);

		receivers = receiver.getAllReceivers();

		if (selected != null) {
			for (Receiver p: receivers) {
				if (p.getAddress().equalsIgnoreCase(selected)) {
					containsSelected = true;
					break;
				}
			}
			if (!containsSelected) {
				Log.w(TAG, "Provider " + selected + "not found");
				//providers.add(0, selected);
			}
		}

		entries = new String[receivers.size()];
		values = new String[receivers.size()];
		for (int i=0; i < receivers.size(); ++i) {
			entries[i] = receivers.get(i).getName();
			values[i] = receivers.get(i).getAddress();
		}

		lf.setEntries(entries);
		lf.setEntryValues(values);
		if (lf.getValue() == null)
			lf.setValue(OSMTracker.Preferences.VAL_GPS_BUILTIN_RECEIVER);
	}


	/*
	 * Populates Bluetooth GPS preferences
	 */
	private void populateBluetoothGpsPreference() {
		Preference pref;
		ListPreference lpref;

		// Bluetooth settings
		pref = findPreference(OSMTracker.Preferences.KEY_GPS_BLUETOOTH_OSSETTINGS);
		pref.setOnPreferenceClickListener(new OnPreferenceClickListener() {
			@Override
			public boolean onPreferenceClick(Preference preference) {
				startActivityForResult(
						new Intent(Settings.ACTION_BLUETOOTH_SETTINGS),
						BLUETOOTH_SETTINGS_REQUEST);
				return true;
			}
		});

		/* Bluetooth receivers */
		updateBluetoothReceiversList();
		lpref = (ListPreference)findPreference(OSMTracker.Preferences.KEY_GPS_BLUETOOTH_RECEIVER);
		lpref.setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
			@Override
			public boolean onPreferenceChange(Preference preference, Object newValue) {
				return updateBluetoothReceiversSummary(preference, newValue);
			}
		});
		updateBluetoothReceiversSummary(lpref, lpref.getValue());
	}

	private boolean updateBluetoothReceiversSummary(Preference preference, Object newValue) {
		if (newValue == null) {
			preference.setSummary("");
		}else {
			String addr;
			Receiver r;

			addr = newValue.toString();
			r = ReceiverInterfaces.BLUETOOTH.getInterface(this).getReceiver(addr);

			if (r == null) {
				preference.setSummary(addr);
			}else {
				String summary = r.getName();
				if (addr.length() > 6)
					summary += "   " + addr.substring(addr.length() - 6);
				preference.setSummary(summary);
			}
		}

		return true;
	}

	private void updateBluetoothReceiversList()
	{
		ReceiverInterface gpsInterface;
		ListPreference lf;
		List<Receiver> receivers;
		String entries[], values[];
		String selected;
		boolean containsSelected = false;

		gpsInterface = ReceiverInterfaces.BLUETOOTH.getInterface(this);
		lf = (ListPreference) findPreference(OSMTracker.Preferences.KEY_GPS_BLUETOOTH_RECEIVER);
		selected = lf.getSharedPreferences().getString(lf.getKey(), null);

		receivers = gpsInterface.getAllReceivers();

		if (selected != null) {
			for (Receiver p: receivers) {
				if (p.getAddress().equalsIgnoreCase(selected)) {
					containsSelected = true;
					break;
				}
			}
			if (!containsSelected ) {
				Log.w(TAG, "Provider " + selected + " not found");
				receivers.add(0, gpsInterface.getReceiver(selected));
			}
		}

		entries = new String[receivers.size()];
		values = new String[receivers.size()];
		for (int i=0; i < receivers.size(); ++i) {
			values[i] = receivers.get(i).getAddress();
			entries[i] = receivers.get(i).getName();
		}

		lf.setEntries(entries);
		lf.setEntryValues(values);
		if ((lf.getValue() == null) && (entries.length > 0))
			lf.setValue(values[0]);
	}

	private void populateUsbGpsPreference() {
		Preference pref;
		ListPreference lpref;

		/* Bluetooth receivers */
		updateUsbReceiversList();
		lpref = (ListPreference)findPreference(OSMTracker.Preferences.KEY_GPS_USB_RECEIVER);
		lpref.setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
			@Override
			public boolean onPreferenceChange(Preference preference, Object newValue) {
				return updateUsbReceiversSummary(preference, newValue);
			}
		});
		updateUsbReceiversSummary(lpref, lpref.getValue());
	}

	private boolean updateUsbReceiversSummary(Preference preference, Object newValue) {
		if (newValue == null) {
			preference.setSummary("");
		}else {
			String addr;
			Receiver r;

			addr = newValue.toString();
			r = ReceiverInterfaces.USB.getInterface(this).getReceiver(addr);

			if (r == null) {
				preference.setSummary(addr);
			}else {
				String summary = r.getName();
				preference.setSummary(summary);
			}
		}

		return true;
	}

	private void updateUsbReceiversList()
	{
		ReceiverInterface gpsInterface;
		ListPreference lf;
		List<Receiver> receivers;
		String entries[], values[];
		String selected;
		boolean containsSelected = false;

		gpsInterface = ReceiverInterfaces.USB.getInterface(this);
		lf = (ListPreference) findPreference(OSMTracker.Preferences.KEY_GPS_USB_RECEIVER);
		selected = lf.getSharedPreferences().getString(lf.getKey(), null);

		receivers = gpsInterface.getAllReceivers();

		if (selected != null) {
			for (Receiver p: receivers) {
				if (p.getAddress().equalsIgnoreCase(selected)) {
					containsSelected = true;
					break;
				}
			}
			if (!containsSelected ) {
				Log.w(TAG, "Provider " + selected + " not found");
				receivers.add(0, gpsInterface.getReceiver(selected));
			}
		}

		entries = new String[receivers.size()];
		values = new String[receivers.size()];
		for (int i=0; i < receivers.size(); ++i) {
			values[i] = receivers.get(i).getAddress();
			entries[i] = receivers.get(i).getName();
		}

		lf.setEntries(entries);
		lf.setEntryValues(values);
		if ((lf.getValue() == null) && (entries.length > 0))
			lf.setValue(values[0]);
	}


	protected void onActivityResult(int requestCode, int resultCode,
            Intent data) {
		if (requestCode == GPS_SETTINGS_REQUEST)
			updateLocationProvidersList();
		else if (requestCode == BLUETOOTH_SETTINGS_REQUEST)
			updateBluetoothReceiversList();
	}

	/**
	 * Populates the user layout list preference.
	 * @param storageDir Where to find layout files
	 */
	private void populateLayoutPreference(String storageDir) {
		// Populate layout lists reading available layouts from external storage
		ListPreference lf = (ListPreference) findPreference(OSMTracker.Preferences.KEY_UI_BUTTONS_LAYOUT);
		String[] entries;
		String[] values;
		
		// Check for presence of layout directory
		File layoutsDir = new File(Environment.getExternalStorageDirectory(), storageDir + File.separator + LAYOUTS_SUBDIR + File.separator);
		if (layoutsDir.exists() && layoutsDir.canRead()) {
			// List each layout file
			String[] layoutFiles = layoutsDir.list(new FilenameFilter() {
				@Override
				public boolean accept(File dir, String filename) {
					return filename.endsWith(LAYOUT_FILE_EXTENSION);
				}
			});
			// Create array of values for each layout file + the default one
			entries = new String[layoutFiles.length+1];
			values = new String[layoutFiles.length+1];
			entries[0] = getResources().getString(R.string.prefs_ui_buttons_layout_defaut);
			values[0] = OSMTracker.Preferences.VAL_UI_BUTTONS_LAYOUT;
			for (int i=0; i<layoutFiles.length; i++) {
				entries[i+1] = layoutFiles[i].substring(0, layoutFiles[i].length()-LAYOUT_FILE_EXTENSION.length());
				values[i+1] = layoutFiles[i];
			}
		} else {
			// No layout found, populate values with just the default entry.
			entries = new String[] {getResources().getString(R.string.prefs_ui_buttons_layout_defaut)};
			values = new String[] {OSMTracker.Preferences.VAL_UI_BUTTONS_LAYOUT};
		}
		lf.setEntries(entries);
		lf.setEntryValues(values);
	}
	
}
