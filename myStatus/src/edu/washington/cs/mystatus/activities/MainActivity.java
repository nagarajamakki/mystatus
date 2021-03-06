package edu.washington.cs.mystatus.activities;

import info.guardianproject.cacheword.CacheWordActivityHandler;
import info.guardianproject.cacheword.ICacheWordSubscriber;

import java.io.File;

import android.app.Activity;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import edu.washington.cs.mystatus.R;
import edu.washington.cs.mystatus.application.MyStatus;
import edu.washington.cs.mystatus.odk.activities.FormDownloadList;
import edu.washington.cs.mystatus.odk.preferences.AdminPreferencesActivity;
import edu.washington.cs.mystatus.odk.preferences.PreferencesActivity;
import edu.washington.cs.mystatus.odk.provider.FormsProviderAPI.FormsColumns;
import edu.washington.cs.mystatus.odk.provider.InstanceProviderAPI.InstanceColumns;
import edu.washington.cs.mystatus.odk.utilities.Base64Wrapper;
import edu.washington.cs.mystatus.odk.utilities.FileUtils;
import edu.washington.cs.mystatus.receivers.ScreenOnOffReceiver;


/**
 * MainActivity is a simple main menu for myStatus.
 * 
 * @author Jake Bailey (rjacob@cs.washington.edu)
 */
public class MainActivity extends Activity implements ICacheWordSubscriber {

	private static final String TAG = "mystatus.MainActivity";

	private CacheWordActivityHandler mCacheWord;
	private boolean firstTimeInitialize = false;
	private ScreenOnOffReceiver screenReceiver;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.mystatus_main);

		View trackBtn = findViewById(R.id.button_track);
		View setupBtn = findViewById(R.id.button_setup);
		View planBtn  = findViewById(R.id.button_plan);
		View helpBtn  = findViewById(R.id.button_help);

		// adding activity handler
		mCacheWord = new CacheWordActivityHandler(this);
		// adding screen on off receiver for turning off the screen correctly
		IntentFilter intentFilter = new IntentFilter(Intent.ACTION_SCREEN_ON);
		intentFilter.addAction(Intent.ACTION_SCREEN_OFF);
		screenReceiver = new ScreenOnOffReceiver();
		registerReceiver(screenReceiver, intentFilter);
		//mCacheWord.connectToService();
		trackBtn.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				Log.d(TAG, "Track button clicked");
				((MyStatus)getApplicationContext()).connectCacheWord();
				startActivity(new Intent(MainActivity.this, SurveyListTabs.class));
			}
		});

		setupBtn.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				Log.d(TAG, "Subscribe button clicked");
				startActivity(new Intent(MainActivity.this, FormDownloadList.class));
			}
		});

		planBtn.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				Log.d(TAG, "Plan button clicked");
				startActivity(new Intent(MainActivity.this, ManagePrescriptionActivity.class));
			}
		});

		helpBtn.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				Log.d(TAG, "Help button clicked");
				startActivity(new Intent(MainActivity.this, HelpActivity.class));
			}
		});
	}
	
	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		super.onCreateOptionsMenu(menu);
		// Add items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.activity_main, menu);
		return true;
	}
	
	@Override
	public boolean onMenuItemSelected(int featureId, MenuItem item) {
		switch (item.getItemId()) {
		case R.id.action_settings:
			((MyStatus)getApplicationContext()).connectCacheWord();
			startActivity(new Intent(this, SettingsActivity.class));
			return true;
		case R.id.action_odk_settings:
			((MyStatus)getApplicationContext()).connectCacheWord();
			startActivity(new Intent(this, PreferencesActivity.class));
			return true;
		case R.id.action_odk_admin_settings:
			((MyStatus)getApplicationContext()).connectCacheWord();
			startActivity(new Intent(this, AdminPreferencesActivity.class));
			return true;
		default:
			return super.onMenuItemSelected(featureId, item);
		}
	}
	
	// methods need to be added to subscribed to cache word
	@Override
	public void onCacheWordUninitialized() {
		// clear out all the old history
		// clean up temp folder
		File f1 = new File (MyStatus.FORMS_PATH);
		File f2 = new File (MyStatus.INSTANCES_PATH);
		File f3 = new File (MyStatus.METADATA_PATH);
		FileUtils.deleteAllFilesInDirectoryRecursively(f1);
		FileUtils.deleteAllFilesInDirectoryRecursively(f2);
		FileUtils.deleteAllFilesInDirectoryRecursively(f3);
		firstTimeInitialize = true;
		showLockScreen();
	}

	@Override
	public void onCacheWordLocked() {
		// such as close database and erase all decrypted media files
		// clean up temp folder
		cleanUpTemporaryFiles();
	}
	
    @Override
	public void onCacheWordOpened() {
		// reset database for first time log in 
	    if (firstTimeInitialize){
	        MyStatus.createODKDirs();
	        getContentResolver().update(FormsColumns.CONTENT_URI, null, "resetDb", null);
	        getContentResolver().update(InstanceColumns.CONTENT_URI, null, "resetDb", null);
	        // work around for notification services....
	        SharedPreferences prefs = getSharedPreferences("mystatus", MODE_PRIVATE);
	        Base64Wrapper b64w;
			try {
				b64w = new Base64Wrapper();
				SharedPreferences.Editor editor = prefs.edit();
		        editor.putString("encoded_key", b64w.encodeToString(mCacheWord.getEncryptionKey()));
		        editor.commit();
			} catch (ClassNotFoundException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
	        
	        firstTimeInitialize = false;
	    }
		
	}
	
	// disconnect cacheword service
	// we have to do this as cacheword wont trigger timeout 
	// until we dont have any more subscribers.
	@Override
    protected void onPause() {
        super.onPause();
        mCacheWord.onPause();
    }
	
	// reconnect cache word service after disconnect
    @Override
    protected void onResume() {
        super.onResume();
        mCacheWord.onResume();
        //screen is off and should be lock
        if (screenReceiver.wasOffBefore){
        	cleanUpTemporaryFiles();
        	mCacheWord.manuallyLock();
        	showLockScreen();
        }
    }
    
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        if (mCacheWord.isLocked() && hasFocus){
            showLockScreen();
        } 
    }

    /**
     * show lock screen if not yet initialized
     */
    void showLockScreen() {
        Intent intent = new Intent(this, LockScreenActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra("originalIntent", getIntent());
        startActivity(intent);
        finish();
    }
    
    // make sure all the temporary files got clean up 
 	// right after the app destroy
	@Override
	protected void onDestroy() {
		cleanUpTemporaryFiles();
		super.onDestroy();	
	}
	
	/**
	 * Helper used to clean up all files and folder under the temp folder
	 */
	private void cleanUpTemporaryFiles(){
		File f = new File (MyStatus.TEMP_MEDIA_PATH);
		FileUtils.deleteAllFilesInDirectoryRecursively(f);
	}

	

}
