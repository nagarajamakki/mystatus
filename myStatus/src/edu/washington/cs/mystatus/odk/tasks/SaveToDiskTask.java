/*
 * Copyright (C) 2009 University of Washington
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package edu.washington.cs.mystatus.odk.tasks;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;

import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.ShortBufferException;

import org.javarosa.core.model.FormDef;
import org.javarosa.core.services.transport.payload.ByteArrayPayload;
import org.javarosa.form.api.FormEntryController;
import org.spongycastle.crypto.DataLengthException;
import org.spongycastle.crypto.InvalidCipherTextException;

import edu.washington.cs.mystatus.application.MyStatus;
import edu.washington.cs.mystatus.odk.listeners.FormSavedListener;
import edu.washington.cs.mystatus.odk.logic.FormController;
import edu.washington.cs.mystatus.odk.provider.InstanceProviderAPI;
import edu.washington.cs.mystatus.odk.provider.FormsProviderAPI.FormsColumns;
import edu.washington.cs.mystatus.odk.provider.InstanceProviderAPI.InstanceColumns;
import edu.washington.cs.mystatus.odk.utilities.EncryptionUtils;
import edu.washington.cs.mystatus.odk.utilities.EncryptionUtils.EncryptedFormInformation;
import edu.washington.cs.mystatus.utilities.DataEncryptionUtils;

import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.util.Log;

/**
 * Background task for loading a form.
 *
 * @author Carl Hartung (carlhartung@gmail.com)
 * @author Yaw Anokwa (yanokwa@gmail.com)
 */
public class SaveToDiskTask extends AsyncTask<Void, String, Integer> {
    private final static String t = "SaveToDiskTask";

    private FormSavedListener mSavedListener;
    private Boolean mSave;
    private Boolean mMarkCompleted;
    private Uri mUri;
    private String mInstanceName;
    private Uri mFormUri;

    public static final int SAVED = 500;
    public static final int SAVE_ERROR = 501;
    public static final int VALIDATE_ERROR = 502;
    public static final int VALIDATED = 503;
    public static final int SAVED_AND_EXIT = 504;


    public SaveToDiskTask(Uri uri, Boolean saveAndExit, Boolean markCompleted, String updatedName,
            Uri formUri) {
        mUri = uri;
        mSave = saveAndExit;
        mMarkCompleted = markCompleted;
        mInstanceName = updatedName;
        mFormUri = formUri;
    }


    /**
     * Initialize {@link FormEntryController} with {@link FormDef} from binary or from XML. If given
     * an instance, it will be used to fill the {@link FormDef}.
     */
    @Override
    protected Integer doInBackground(Void... nothing) {

        FormController formController = MyStatus.getInstance().getFormController();

        // validation failed, pass specific failure
        int validateStatus = formController.validateAnswers(mMarkCompleted);
        if (validateStatus != FormEntryController.ANSWER_OK) {
            return validateStatus;
        }

        if (mMarkCompleted) {
        	formController.postProcessInstance();
        }

    	MyStatus.getInstance().getActivityLogger().logInstanceAction(this, "save", Boolean.toString(mMarkCompleted));

    	// if there is a meta/instanceName field, be sure we are using the latest value
    	// just in case the validate somehow triggered an update.
    	String updatedSaveName = formController.getSubmissionMetadata().instanceName;
    	if ( updatedSaveName != null ) {
    		mInstanceName = updatedSaveName;
    	}

    	boolean saveOutcome = exportData(mMarkCompleted);

    	// attempt to remove any scratch file
        File shadowInstance = savepointFile(formController.getInstancePath());
        if ( shadowInstance.exists() ) {
        	shadowInstance.delete();
        }

        if (saveOutcome) {
        	return mSave ? SAVED_AND_EXIT : SAVED;
        }

        return SAVE_ERROR;

    }

    private void updateInstanceDatabase(boolean incomplete, boolean canEditAfterCompleted) {

        FormController formController = MyStatus.getInstance().getFormController();

        // Update the instance database...
        ContentValues values = new ContentValues();
        if (mInstanceName != null) {
            values.put(InstanceColumns.DISPLAY_NAME, mInstanceName);
        }
        if (incomplete || !mMarkCompleted) {
            values.put(InstanceColumns.STATUS, InstanceProviderAPI.STATUS_INCOMPLETE);
        } else {
            values.put(InstanceColumns.STATUS, InstanceProviderAPI.STATUS_COMPLETE);

            // Update the lastResponse time of this form and mark it as not needing a response
            ContentValues formValues = new ContentValues();
            formValues.put(FormsColumns.LAST_RESPONSE, Long.valueOf(System.currentTimeMillis()));
            formValues.put(FormsColumns.NEEDS_RESPONSE, 0);
            
            int updated = MyStatus.getInstance().getContentResolver()
                    .update(mFormUri, formValues, null, null);
            if (updated > 1) {
                Log.w(t, "Updated more than one form, that's not good: " + mFormUri.toString());
            } else if (updated == 1) {
                Log.i(t, "Form successfully updated");
            } else {
                Log.e(t, "Form doesn't exist but we have its Uri!! " + mFormUri.toString());
            }
        }
        // update this whether or not the status is complete...
        values.put(InstanceColumns.CAN_EDIT_WHEN_COMPLETE, Boolean.toString(canEditAfterCompleted));

        // If FormEntryActivity was started with an Instance, just update that instance
        if (MyStatus.getInstance().getContentResolver().getType(mUri) == InstanceColumns.CONTENT_ITEM_TYPE) {
            int updated = MyStatus.getInstance().getContentResolver().update(mUri, values, null, null);
            if (updated > 1) {
                Log.w(t, "Updated more than one entry, that's not good: " + mUri.toString());
            } else if (updated == 1) {
                Log.i(t, "Instance successfully updated");
            } else {
            	Log.e(t, "Instance doesn't exist but we have its Uri!! " + mUri.toString());
            }
        } else if (MyStatus.getInstance().getContentResolver().getType(mUri) == FormsColumns.CONTENT_ITEM_TYPE) {
            // If FormEntryActivity was started with a form, then it's likely the first time we're
            // saving.
            // However, it could be a not-first time saving if the user has been using the manual
            // 'save data' option from the menu. So try to update first, then make a new one if that
            // fails.
        	String instancePath = formController.getInstancePath().getAbsolutePath();
            String where = InstanceColumns.INSTANCE_FILE_PATH + "=?";
            String[] whereArgs = {
            		instancePath
            };
            int updated =
                MyStatus.getInstance().getContentResolver()
                        .update(InstanceColumns.CONTENT_URI, values, where, whereArgs);
            if (updated > 1) {
                Log.w(t, "Updated more than one entry, that's not good: " + instancePath);
            } else if (updated == 1) {
                Log.i(t, "Instance found and successfully updated: " + instancePath);
                // already existed and updated just fine
            } else {
                Log.i(t, "No instance found, creating");
                // Entry didn't exist, so create it.
                Cursor c = null;
                try {
                	// retrieve the form definition...
                	c = MyStatus.getInstance().getContentResolver().query(mUri, null, null, null, null);
	                c.moveToFirst();
	                String jrformid = c.getString(c.getColumnIndex(FormsColumns.JR_FORM_ID));
	                String jrversion = c.getString(c.getColumnIndex(FormsColumns.JR_VERSION));
	                String formname = c.getString(c.getColumnIndex(FormsColumns.DISPLAY_NAME));
	                String submissionUri = null;
	                if ( !c.isNull(c.getColumnIndex(FormsColumns.SUBMISSION_URI)) ) {
	                	submissionUri = c.getString(c.getColumnIndex(FormsColumns.SUBMISSION_URI));
	                }

	                // add missing fields into values
	                values.put(InstanceColumns.INSTANCE_FILE_PATH, instancePath);
	                values.put(InstanceColumns.SUBMISSION_URI, submissionUri);
	                if (mInstanceName != null) {
	                    values.put(InstanceColumns.DISPLAY_NAME, mInstanceName);
	                } else {
	                    values.put(InstanceColumns.DISPLAY_NAME, formname);
	                }
	                values.put(InstanceColumns.JR_FORM_ID, jrformid);
	                values.put(InstanceColumns.JR_VERSION, jrversion);
                } finally {
                	if ( c != null ) {
                		c.close();
                	}
                }
                mUri = MyStatus.getInstance().getContentResolver()
                			.insert(InstanceColumns.CONTENT_URI, values);
            }
        }
    }

    /**
     * Return the name of the savepoint file for a given instance.
     *
     * @param instancePath
     * @return
     */
    public static File savepointFile(File instancePath) {
        File tempDir = new File(MyStatus.CACHE_PATH);
        File temp = new File(tempDir, instancePath.getName() + ".save");
        return temp;
    }

    /**
     * Blocking write of the instance data to a temp file. Used to safeguard data
     * during intent launches for, e.g., taking photos.
     *
     * @param tempPath
     * @return
     */
    public static String blockingExportTempData() {
        FormController formController = MyStatus.getInstance().getFormController();

        long start = System.currentTimeMillis();
        File temp = savepointFile(formController.getInstancePath());
        ByteArrayPayload payload;
        try {
        	payload = formController.getFilledInFormXml();
            // write out xml
            if ( exportXmlFile(payload, temp.getAbsolutePath()) ) {
            	return temp.getAbsolutePath();
            }
            return null;
        } catch (IOException e) {
            Log.e(t, "Error creating serialized payload");
            e.printStackTrace();
            return null;
        } finally {
        	long end = System.currentTimeMillis();
        	Log.i(t, "Savepoint ms: " + Long.toString(end - start));
        }
    }

    /**
     * Write's the data to the sdcard, and updates the instances content provider.
     * In theory we don't have to write to disk, and this is where you'd add
     * other methods.
     * @param markCompleted
     * @return
     */
    private boolean exportData(boolean markCompleted) {
        FormController formController = MyStatus.getInstance().getFormController();

        ByteArrayPayload payload;
        try {
        	payload = formController.getFilledInFormXml();
            // write out xml
        	String instancePath = formController.getInstancePath().getAbsolutePath();
        	// instance get saved here
            exportXmlFile(payload, instancePath);

        } catch (IOException e) {
            Log.e(t, "Error creating serialized payload");
            e.printStackTrace();
            return false;
        }

        // update the mUri. We have exported the reloadable instance, so update status...
        // Since we saved a reloadable instance, it is flagged as re-openable so that if any error
        // occurs during the packaging of the data for the server fails (e.g., encryption),
        // we can still reopen the filled-out form and re-save it at a later time.
        updateInstanceDatabase(true, true);

        if ( markCompleted ) {
            // now see if the packaging of the data for the server would make it
        	// non-reopenable (e.g., encryption or send an SMS or other fraction of the form).
            boolean canEditAfterCompleted = formController.isSubmissionEntireForm();
            boolean isEncrypted = false;

            // build a submission.xml to hold the data being submitted
            // and (if appropriate) encrypt the files on the side

            // pay attention to the ref attribute of the submission profile...
            try {
                payload = formController.getSubmissionXml();
            } catch (IOException e) {
                Log.e(t, "Error creating serialized payload");
                e.printStackTrace();
                return false;
            }

            File instanceXml = formController.getInstancePath();
            File submissionXml = new File(instanceXml.getParentFile(), "submission.xml");
            // write out submission.xml -- the data to actually submit to aggregate
            exportXmlFile(payload, submissionXml.getAbsolutePath());

            // see if the form is encrypted and we can encrypt it...
            EncryptedFormInformation formInfo = EncryptionUtils.getEncryptedFormInformation(mUri,
            		formController.getSubmissionMetadata());
            if ( formInfo != null ) {
                // if we are encrypting, the form cannot be reopened afterward
                canEditAfterCompleted = false;
                // and encrypt the submission (this is a one-way operation)...
                if ( !EncryptionUtils.generateEncryptedSubmission(instanceXml, submissionXml, formInfo) ) {
                    return false;
                }
                isEncrypted = true;
            }

            // At this point, we have:
            // 1. the saved original instanceXml,
            // 2. all the plaintext attachments
            // 2. the submission.xml that is the completed xml (whether encrypting or not)
            // 3. all the encrypted attachments if encrypting (isEncrypted = true).
            //
            // NEXT:
            // 1. Update the instance database (with status complete).
            // 2. Overwrite the instanceXml with the submission.xml
            //    and remove the plaintext attachments if encrypting

            updateInstanceDatabase(false, canEditAfterCompleted);

	        if (  !canEditAfterCompleted ) {
	            // AT THIS POINT, there is no going back.  We are committed
	            // to returning "success" (true) whether or not we can
	            // rename "submission.xml" to instanceXml and whether or
	            // not we can delete the plaintext media files.
	        	//
	        	// Handle the fall-out for a failed "submission.xml" rename
	        	// in the InstanceUploader task.  Leftover plaintext media
	        	// files are handled during form deletion.

	            // delete the restore Xml file.
	            if ( !instanceXml.delete() ) {
	                Log.e(t, "Error deleting " + instanceXml.getAbsolutePath()
	                		+ " prior to renaming submission.xml");
	                return true;
	            }

	            // rename the submission.xml to be the instanceXml
	            if ( !submissionXml.renameTo(instanceXml) ) {
	                Log.e(t, "Error renaming submission.xml to " + instanceXml.getAbsolutePath());
	                return true;
	            }
	        } else {
	        	// try to delete the submissionXml file, since it is
	        	// identical to the existing instanceXml file
	        	// (we don't need to delete and rename anything).
	            if ( !submissionXml.delete() ) {
	                Log.w(t, "Error deleting " + submissionXml.getAbsolutePath()
	                		+ " (instance is re-openable)");
	            }
	        }

            // if encrypted, delete all plaintext files
            // (anything not named instanceXml or anything not ending in .enc)
            if ( isEncrypted ) {
                if ( !EncryptionUtils.deletePlaintextFiles(instanceXml) ) {
                    Log.e(t, "Error deleting plaintext files for " + instanceXml.getAbsolutePath());
                }
            }
        }
        return true;
    }


    /**
     * This method actually writes the xml to disk.
     * @param payload
     * @param path
     * @return
     */
    private static boolean exportXmlFile(ByteArrayPayload payload, String path) {
        // create data stream
        InputStream is = payload.getPayloadStream();
        int len = (int) payload.getLength();

        
        // read from data stream
        byte[] data = new byte[len];
        // data should be encrypted here
        try {
        	// encrypted saved instance here
        	File file = new File (path);
        	// remove old files
        	if (file.exists())
        		file.delete();
        	// get the output stream
        	FileOutputStream os = new FileOutputStream(new File(path));
        	DataEncryptionUtils ec = new DataEncryptionUtils();
        	ec.InitCiphers();
        	ec.CBCEncrypt(is, os);
//            int read = is.read(data, 0, len);
//            if (read > 0) {
//                // write xml file
//                try {
//                    // String filename = path + File.separator +
//                    // path.substring(path.lastIndexOf(File.separator) + 1) + ".xml";
//                	FileWriter fw = new FileWriter(path);
//                	fw.write(new String(data, "UTF-8"));
//                	fw.flush();
//                	fw.close();
//                    return true;
//
//                } catch (IOException e) {
//                    Log.e(t, "Error writing XML file");
//                    e.printStackTrace();
//                    return false;
//                }
//            }
        } catch (IOException e) {
            Log.e(t, "Error reading from payload data stream");
            e.printStackTrace();
            return false;
        } catch (DataLengthException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (ShortBufferException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IllegalBlockSizeException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (BadPaddingException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IllegalStateException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (InvalidCipherTextException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

        return false;
    }


    @Override
    protected void onPostExecute(Integer result) {
        synchronized (this) {
            if (mSavedListener != null)
                mSavedListener.savingComplete(result);
        }
    }


    public void setFormSavedListener(FormSavedListener fsl) {
        synchronized (this) {
            mSavedListener = fsl;
        }
    }


}
