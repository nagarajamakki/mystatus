package edu.washington.cs.mystatus.activities;

import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

import edu.washington.cs.mystatus.R;
import edu.washington.cs.mystatus.services.NotificationService;
import edu.washington.cs.mystatus.utilities.CalendarCreator;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.Dialog;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.CalendarContract.Events;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TimePicker;

/**
 * SettingsActivity provides a UI for managing notification settings.
 * 
 * It is also responsible for setting up recurring notifications when
 * notifications are enabled, and cleaning up when notifications are disabled.
 * 
 * @author Emily Chien (eechien@cs.washington.edu)
 * @see AlarmManager
 * @see NotificationService
 */

public class SettingsActivity extends Activity {
	
	private static final String TAG = "mystatus.SettingsActivity";
	
	private static final int DEFAULT_HOUR = 8;
	private static final int DEFAULT_MIN = 30;
	private static final int TIME_PICKER_ID = 0;
	private static boolean[] DAYS;
	private static int HOUR;
	private static int MINUTE;
	private static long EVENT_ID;
	private static long CALENDAR_ID;
	
	private Button mTime;
	private CheckBox mSun;
	private CheckBox mMon;
	private CheckBox mTue;
	private CheckBox mWed;
	private CheckBox mThu;
	private CheckBox mFri;
	private CheckBox mSat;
	private TimePicker mTP;
	private Button mCancel;
	private Button mSet;
	
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.mystatus_settings);
		
		setGlobalVars();

		// if it hasn't already been set, first time creation
		if (EVENT_ID == -1) {
			createEvent();
		}
		
		mTime = (Button) findViewById(R.id.time);
		mTime.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				showDialog(TIME_PICKER_ID);
			}
		});
	}
	
	/**
	 * Sets the Set and Cancel button listeners as well as the Time Picker on
	 * the Dialog that appears when "Set time" is pressed.
	 * @param d
	 */
	private void setSetCancelAndTime(final Dialog d) {
		mCancel = (Button) d.findViewById(R.id.dialog_cancel);
		mSet = (Button) d.findViewById(R.id.dialog_set);
		mTP = (TimePicker) d.findViewById(R.id.settings_time_picker);
		
		mCancel.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View view) {
				d.cancel();
			}
		});
		
		mSet.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View view) {
				setDAYS(mSun, 0, "sun_checked");
				setDAYS(mMon, 1, "mon_checked");
				setDAYS(mTue, 2, "tue_checked");
				setDAYS(mWed, 3, "wed_checked");
				setDAYS(mThu, 4, "thu_checked");
				setDAYS(mFri, 5, "fri_checked");
				setDAYS(mSat, 6, "sat_checked");
				
				setHourMin();
				
				updateEvent();
				
				d.dismiss();
			}
		});
	}
	
	/**
	 * Sets the global variables HOUR and MINUTE and puts them
	 * in the shared preferences
	 */
    private void setHourMin() {
		SharedPreferences prefs = getPreferences(MODE_PRIVATE);
		SharedPreferences.Editor editor = prefs.edit();
		HOUR = mTP.getCurrentHour();
		MINUTE = mTP.getCurrentMinute();
		editor.putInt("hour_setting", HOUR);
		editor.putInt("minute_setting", MINUTE);
		editor.commit();
    }
	
    /**
     * Sets the checkboxes on Dialog d based on the DAYS array
     */
	private void setCheckBoxes(Dialog d) {
		mSun = (CheckBox) d.findViewById(R.id.sunday);
		mSun.setChecked(DAYS[0]);
		mMon = (CheckBox) d.findViewById(R.id.monday);
		mMon.setChecked(DAYS[1]);
		mTue = (CheckBox) d.findViewById(R.id.tuesday);
		mTue.setChecked(DAYS[2]);
		mWed = (CheckBox) d.findViewById(R.id.wednesday);
		mWed.setChecked(DAYS[3]);
		mThu = (CheckBox) d.findViewById(R.id.thursday);
		mThu.setChecked(DAYS[4]);
		mFri = (CheckBox) d.findViewById(R.id.friday);
		mFri.setChecked(DAYS[5]);
		mSat = (CheckBox) d.findViewById(R.id.saturday);
		mSun.setChecked(DAYS[6]);
	}
	
	/**
	 * Sets DAYS[id] based on whether dayBox is checked or not and edits the shared preferences
	 */
	private void setDAYS(CheckBox dayBox, final int id, final String dayString) {
		SharedPreferences prefs = getPreferences(MODE_PRIVATE);
		SharedPreferences.Editor editor = prefs.edit();
		if (dayBox.isChecked()) {
			editor.putBoolean(dayString, true);
			DAYS[id] = true;
		} else {
			editor.putBoolean(dayString, false);
			DAYS[id] = false;
		}
		editor.commit();
	}
	
	/**
	 * Sets the global variables based on the shared preferences
	 */
	private void setGlobalVars() {
		SharedPreferences settings = getPreferences(MODE_PRIVATE);
		DAYS = new boolean[7];
		DAYS[0] = settings.getBoolean("su_checked", false);
		DAYS[1] = settings.getBoolean("mo_checked", true);
		DAYS[2] = settings.getBoolean("tu_checked", true);
		DAYS[3] = settings.getBoolean("we_checked", true);
		DAYS[4] = settings.getBoolean("th_checked", true);
		DAYS[5] = settings.getBoolean("fr_checked", true);
		DAYS[6] = settings.getBoolean("sa_checked", false);
		HOUR = settings.getInt("hour_setting", DEFAULT_HOUR);
		MINUTE = settings.getInt("minute_setting", DEFAULT_MIN);
		EVENT_ID = settings.getLong("event_id", -1);
		CALENDAR_ID = settings.getLong("calendar_id", -1);
		if (CALENDAR_ID == -1) {
			Calendar cal = Calendar.getInstance();
			ContentResolver cr = getContentResolver();
			CalendarCreator.addCalendar(cal, cr);
			CALENDAR_ID = getCalendarId();
		}
	}
	
	@Override
	protected Dialog onCreateDialog(int id) {
        Dialog dialog = new Dialog(this);
        dialog.setContentView(R.layout.settings_dialog);
        dialog.setTitle(R.string.settings_dialog_title);
        
        setSetCancelAndTime(dialog);
		setCheckBoxes(dialog);
		
		return dialog;	
	}
	
	// Creates a calendar event in the myStatus Calendar with the date and time provided
	// in the parameters
	private void createEvent() {
		// create beginning of event and end of event
		Date beginTime = new Date();
		beginTime.setDate(getDate(beginTime, DAYS));
		beginTime.setHours(HOUR);
		beginTime.setMinutes(MINUTE);
		
		Date  endTime = new Date();
		endTime.setDate(getDate(endTime, DAYS));
		endTime.setHours(HOUR + 1);
		endTime.setMinutes(MINUTE);
		Calendar cal = Calendar.getInstance();
		TimeZone tz = cal.getTimeZone();
		// create the event
		ContentResolver cr = getContentResolver();
		ContentValues values = new ContentValues();
		values.put(Events.DTSTART, beginTime.getTime());
		values.put(Events.DTEND, endTime.getTime());
		values.put(Events.TITLE, "myStatus");
		values.put(Events.CALENDAR_ID, CALENDAR_ID);
		values.put(Events.EVENT_TIMEZONE, tz.getDisplayName());
		values.put(Events.RRULE, "FREQ=WEEKLY;BYDAY=" + getWeekdayAbr(DAYS));
		
		// add the event
		Uri uri = cr.insert(Uri.parse("content://com.android.calendar/events"), values);
		EVENT_ID = Long.parseLong(uri.getLastPathSegment());
		Log.i(TAG, "Created event.");
		
		SharedPreferences prefs = getPreferences(MODE_PRIVATE);
		SharedPreferences.Editor editor = prefs.edit();
		editor.putLong("event_id", EVENT_ID);
		editor.commit();
		
		Date today = new Date();
		today.setHours(HOUR);
		today.setMinutes(MINUTE);
		today.setSeconds(0);
		int[] dates = getDates(today);
		// starts alarm manager for each day checked
		for (int i = 0; i < 7; i++) {
			if (dates[i] != Integer.MAX_VALUE) {
				today.setDate(dates[i]);
				enableNotifications(today.getTime(), i);
			}
		}
	}
	
	// Updates the date and time of the calendar event to the parameters given
	private void updateEvent() {
		disableNotifications();
		
		ContentResolver cr = getContentResolver();
		ContentValues values = new ContentValues();
		
		Date beginTime = new Date();
		beginTime.setHours(HOUR);
		beginTime.setMinutes(MINUTE);
		beginTime.setDate(getDate(beginTime, DAYS));
		values.put(Events.DTSTART, beginTime.getTime());
		Date endTime = new Date();
		endTime.setHours(HOUR + 1);
		endTime.setMinutes(MINUTE);
		endTime.setDate(getDate(endTime, DAYS));
		values.put(Events.DTEND, endTime.getTime());
		values.put(Events.RRULE, "FREQ=WEEKLY;BYDAY=" + getWeekdayAbr(DAYS));

		int rows = cr.update(Uri.parse("content://com.android.calendar/events"),
				values, Events._ID + " =? ", new String[]{Long.toString(EVENT_ID)});
		Log.i(TAG, "Rows updated: " + rows);
		Date today = new Date();
		today.setHours(HOUR);
		today.setMinutes(MINUTE);
		today.setSeconds(0);
		int[] dates = getDates(today);
		// TODO: fix this to avoid repeating notification......
		for (int i = 0; i < 7; i++) {
			if (dates[i] != Integer.MAX_VALUE) {
				today.setDate(dates[i]);
				enableNotifications(today.getTime(), i);
			}
		}	
	}
	
	// Returns the 2 letter capital abbreviation of the
	// weekday passed
	private String getWeekdayAbr(boolean[] weekdays) {
		String[] days = {"SU", "MO", "TU", "WE", "TH", "FR", "SA"};
		String ret = "";
		for (int i = 0; i < 7; i++) {
			if (weekdays[i])
				ret += days[i] + ",";
		}
		if (ret.length() > 3)
			return ret.substring(0, ret.length() - 1);
		else
			return ret;
	}
	
	// gets the calendar ID for the myStatus calendar
	private long getCalendarId() {
		ContentResolver cr = getContentResolver();
		Cursor cursor = cr.query(Uri.parse("content://com.android.calendar/calendars"),
		        (new String[] { "_id", "calendar_displayName" }), null, null, null);
		
		while (cursor.moveToNext()) {
			String displayName = cursor.getString(1);
			if (displayName.equals("myStatus")) {
				return Integer.parseInt(cursor.getString(0));
			}
		}
		return -1;
	}
	
	// Enables an AlarmManager to create an alarm when its time for the calendar event
	private void enableNotifications(long startTime, int id) {
		PendingIntent notifyIntent = PendingIntent.getService(this, id,
				new Intent(this, NotificationService.class), 0);
		AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
		am.setRepeating(AlarmManager.RTC_WAKEUP, startTime, 604800000, notifyIntent);
		Log.i(TAG, "Enabled notifications");
	}

	/**
	 * Cancels the AlarmManager alarm that generates notifications.
	 */
	private void disableNotifications() {
		// TODO: for all days
		Log.w(TAG, "Disabling notifications");
		for (int i = 0; i < 7; i++) {
			PendingIntent notificationIntent = PendingIntent.getService(this, i,
					new Intent(this, NotificationService.class), 0);
			AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
			am.cancel(notificationIntent);
		}
	}
	
	// get the day of the month for the next day of the week.
	private int getDate(Date today, boolean[] desiredDays) {
		Calendar cal = Calendar.getInstance();
		cal.setTime(today);
		int todaysDayOfWeek = cal.get(Calendar.DAY_OF_WEEK);
		int desiredDayNum = 0;
		// Start from todaysDayOfWeek day of the week so that
		// days are not skipped when initializing the event
		for (int i = todaysDayOfWeek - 1; i < 7; i++) {
			if (desiredDays[i]) {
				desiredDayNum = i + 1;
				break;
			}
		}
		// if the day was not set;
		if (desiredDayNum == 0) {
			for (int i = 0; i < todaysDayOfWeek - 1; i++) {
				if (desiredDays[i]) {
					desiredDayNum = i + 1;
					break;
				}
			}
		}
		int diff = desiredDayNum - todaysDayOfWeek;
		return cal.get(Calendar.DATE) + diff;	
	}
	
	/**
	 * Gets the dates after today that correspond to the checked days.
	 * If today was Tuesday July 8th and Monday was checked, would return
	 * 14 in the Monday spot of the returned array.
	 */
	private int[] getDates(Date today) {
		int[] dates = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
				Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
		Calendar cal = Calendar.getInstance();
		cal.setTime(today);
		int todaysDayOfWeek = cal.get(Calendar.DAY_OF_WEEK);
		for (int i = 0; i < 7; i++) {
			if (DAYS[i]) {
				dates[i] = i + 1; // sets day to Sunday (1), Monday (2), or Tuesday(3) etc.
			}
		}
		// get difference
		for (int i = 0; i < 7; i++) {
			if (dates[i] != Integer.MAX_VALUE) {
				dates[i] = dates[i] - todaysDayOfWeek;
			}
		}
		// get final date
		for (int i = 0; i < 7; i++) {
			if (dates[i] != Integer.MAX_VALUE) {
				dates[i] = cal.get(Calendar.DATE) + dates[i];
			}
		}
		return dates;
	}
}
