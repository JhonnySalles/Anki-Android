/***************************************************************************************
 *                                                                                      *
 * Copyright (c) 2020 Mike Hardy <mike@mikehardy.net>                                   *
 *                                                                                      *
 * This program is free software; you can redistribute it and/or modify it under        *
 * the terms of the GNU General Public License as published by the Free Software        *
 * Foundation; either version 3 of the License, or (at your option) any later           *
 * version.                                                                             *
 *                                                                                      *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY      *
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A      *
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.             *
 *                                                                                      *
 * You should have received a copy of the GNU General Public License along with         *
 * this program.  If not, see <http://www.gnu.org/licenses/>.                           *
 ****************************************************************************************/

package com.ichi2.anki;

import android.content.Context;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

import timber.log.Timber;

import com.ichi2.async.CollectionTask;
import com.ichi2.compat.CompatHelper;
import com.ichi2.utils.JSONObject;


@SuppressWarnings({"PMD.AvoidThrowingRawExceptionTypes"})
public class TemporaryModel {

    public enum ChangeType { ADD, DELETE }
    public static final String INTENT_MODEL_FILENAME = "editedModelFilename";
    private ArrayList<Object[]> mTemplateChanges = new ArrayList<>();
    private String mEditedModelFileName = null;
    private final @NonNull JSONObject mEditedModel;


    public TemporaryModel(JSONObject model) {
        Timber.d("Constructor called with model");
        mEditedModel = model;
    }


    public static TemporaryModel fromBundle(Bundle bundle) {
        String mEditedModelFileName = bundle.getString(INTENT_MODEL_FILENAME);
        // Bundle.getString is @Nullable, so we have to check.
        // If we return null then onCollectionLoaded() just will load original from database
        if (mEditedModelFileName == null) {
            Timber.d("fromBundle() - model file name under key %s", INTENT_MODEL_FILENAME);
            return null;
        }

        Timber.d("onCreate() loading saved model file %s", mEditedModelFileName);
        TemporaryModel model = new TemporaryModel((getTempModel(mEditedModelFileName)));
        model.loadTemplateChanges(bundle);
        return model;
    }


    public Bundle toBundle() {
        Bundle outState = new Bundle();
        outState.putString(INTENT_MODEL_FILENAME,
                saveTempModel(AnkiDroidApp.getInstance().getApplicationContext(), mEditedModel));
        outState.putSerializable("mTemplateChanges", mTemplateChanges);
        return outState;
    }


    @SuppressWarnings("unchecked")
    private void loadTemplateChanges(Bundle bundle) {
        try {
            mTemplateChanges = (ArrayList<Object[]>) bundle.getSerializable("mTemplateChanges");
        } catch (ClassCastException e) {
            Timber.e(e, "Unexpected cast failure");
        }
    }


    public JSONObject getTemplate(int ord) {
        Timber.d("getTemplate() on ordinal %s", ord);
        return mEditedModel.getJSONArray("tmpls").getJSONObject(ord);
    }


    public int getTemplateCount() {
        return mEditedModel.getJSONArray("tmpls").length();
    }


    public long getModelId() {
        return mEditedModel.getLong("id");
    }


    public void updateCss(String css) {
        mEditedModel.put("css", css);
    }


    public String getCss() {
        return mEditedModel.getString("css");
    }


    public void updateTemplate(int ordinal, JSONObject template) {
        mEditedModel.getJSONArray("tmpls").put(ordinal, template);
    }


    public void addNewTemplate(JSONObject newTemplate) {
        Timber.d("addNewTemplate()");
        addTemplateChange(ChangeType.ADD, newTemplate.getInt("ord"));
        mEditedModel.getJSONArray("tmpls").put(newTemplate);
    }


    public void removeTemplate(int ord) {
        Timber.d("removeTemplate() on ordinal %s", ord);
        addTemplateChange(ChangeType.DELETE, ord);
    }


    public void saveToDatabase(CollectionTask.TaskListener listener) {
        Timber.d("saveToDatabase() called");
        TemporaryModel.clearTempModelFiles();
        CollectionTask.TaskData args = new CollectionTask.TaskData(new Object[] {mEditedModel, getTemplateChanges()});
        CollectionTask.launchCollectionTask(CollectionTask.TASK_TYPE_SAVE_MODEL, listener, args);

    }


    public JSONObject getModel() {
        return mEditedModel;
    }


    public void setEditedModelFileName(String fileName) {
        mEditedModelFileName = fileName;
    }


    public String getEditedModelFileName() {
        return mEditedModelFileName;
    }


    /**
     * Save the current model to a temp file in the application internal cache directory
     * @return String representing the absolute path of the saved file, or null if there was a problem
     */
    public static @Nullable
    String saveTempModel(@NonNull Context context, @NonNull JSONObject tempModel) {
        Timber.d("saveTempModel() saving tempModel");
        File tempModelFile;
        try (ByteArrayInputStream source = new ByteArrayInputStream(tempModel.toString().getBytes())) {
            tempModelFile = File.createTempFile("editedTemplate", ".json", context.getCacheDir());
            CompatHelper.getCompat().copyFile(source, tempModelFile.getAbsolutePath());
        } catch (IOException ioe) {
            Timber.e(ioe, "Unable to create+write temp file for model");
            return null;
        }
        return tempModelFile.getAbsolutePath();
    }


    /**
     * Get the model temporarily saved into the file represented by the given path
     * @return JSONObject holding the model, or null if there was a problem
     */
    public static @Nullable JSONObject getTempModel(@NonNull String tempModelFileName) {
        Timber.d("getTempModel() fetching tempModel %s", tempModelFileName);
        try (ByteArrayOutputStream target = new ByteArrayOutputStream()) {
            CompatHelper.getCompat().copyFile(tempModelFileName, target);
            return new JSONObject(target.toString());
        } catch (Exception e) {
            Timber.e(e, "Unable to read+parse tempModel from file %s", tempModelFileName);
            return null;
        }
    }


    /** Clear any temp model files saved into internal cache directory */
    public static int clearTempModelFiles() {
        int deleteCount = 0;
        for (File c : AnkiDroidApp.getInstance().getCacheDir().listFiles()) {
            if (c.getAbsolutePath().endsWith("json") && c.getAbsolutePath().contains("editedTemplate")) {
                if (!c.delete()) {
                    Timber.w("Unable to delete temp file %s", c.getAbsolutePath());
                } else {
                    deleteCount++;
                    Timber.d("Deleted temp model file %s", c.getAbsolutePath());
                }
            }
        }
        return deleteCount;
    }



    /**
     * Template deletes shift card ordinals in the database. To operate without saving, we must keep track to apply in order.
     * In addition, we don't want to persist a template add just to delete it later, so we combine those if they happen
     */
    public void addTemplateChange(ChangeType type, int ordinal) {
        Timber.d("addTemplateChange() type %s for ordinal %s", type, ordinal);
        if (mTemplateChanges == null) {
            mTemplateChanges = new ArrayList<>();
        }
        Object[] change = new Object[] {ordinal, type};

        // If we are deleting something we added but have not saved, edit it out of the change list
        if (type == ChangeType.DELETE) {
            int ordinalAdjustment = 0;
            for (int i = mTemplateChanges.size() - 1; i >= 0; i--) {
                Object[] oldChange = mTemplateChanges.get(i );
                switch ((ChangeType)oldChange[1]) {
                    case DELETE: {
                        // Deleting an ordinal at or below us? Adjust our comparison basis...
                        if ((Integer)oldChange[0] - ordinalAdjustment <= ordinal) {
                            ordinalAdjustment++;
                            continue;
                        }
                        break;
                    }
                    case ADD:
                        if (ordinal == (Integer)oldChange[0] - ordinalAdjustment) {
                            // Deleting something we added this session? Edit it out via compaction
                            compactTemplateChanges((Integer)oldChange[0]);
                            return;
                        }
                        break;
                    default:
                        break;

                }
            }
        }

        Timber.d("addTemplateChange() added ord/type: %s/%s", change[0], change[1]);
        mTemplateChanges.add(change);
    }


    /**
     * Check if the given ordinal is an addition from this editing session (and thus is not in the database)
     * @param ord the ordinal to check
     * @return boolean true if the given ordinal was added this session (and is not in the database yet)
     */
    public boolean isTemplatePendingAdd(int ord) {
        int ordinalAdjustment = 0;
        for (int i = mTemplateChanges.size() - 1; i >= 0; i--) {
            Object[] oldChange = mTemplateChanges.get(i);
            switch ((ChangeType) oldChange[1]) {
                case DELETE: {
                    // Deleting an ordinal at or below us? Adjust our comparison basis...
                    if ((Integer) oldChange[0] - ordinalAdjustment <= ord) {
                        ordinalAdjustment++;
                        continue;
                    }
                    break;
                }
                case ADD:
                    if (ord == (Integer) oldChange[0] - ordinalAdjustment) {
                        // something we added this session?
                        return true;
                    }
                    break;
                default:
                    break;
            }
        }
        return false;
    }


    /**
     * Return an int[] containing the collection-relative ordinals of all the currently pending deletes,
     * including the ordinal passed in, as opposed to the changelist-relative ordinals
     *
     * @return int[] of all ordinals currently in the database, pending delete
     */
    public int[] getDeleteDbOrds(int ord) {
        dumpChanges();
        Timber.d("getDeleteDbOrds()");

        // array containing the original / db-relative ordinals for all pending deletes plus the proposed one
        ArrayList<Integer> deletedDbOrds = new ArrayList<>();

        // For each entry in the changes list - and the proposed delete - scan for deletes to get original ordinal
        for (int i = 0; i <= mTemplateChanges.size(); i++) {
            int ordinalAdjustment = 0;

            // We need an initializer. Though proposed change is checked last, it's a reasonable default initializer.
            Object[] currentChange = { ord, ChangeType.DELETE };
            if (i < mTemplateChanges.size()) {
                // Until we exhaust the pending change list we will use them
                currentChange = mTemplateChanges.get(i);
            }

            // If the current pending change isn't a delete, it is unimportant here
            if (currentChange[1] != ChangeType.DELETE) {
                continue;
            }

            // If it is a delete, scan previous deletes and shift as necessary for original ord
            for (int j = 0; j < i; j++) {
                Object[] previousChange = mTemplateChanges.get(j);

                // Is previous change a delete? Lower ordinal than current change?
                if ((previousChange[1] == ChangeType.DELETE) && ((int)previousChange[0] <= (int)currentChange[0])) {
                    // If so, that is the case where things shift. It means our ordinals moved and original ord is higher
                    ordinalAdjustment++;
                }
            }

            // We know how many times ordinals smaller than the current were deleted so we have the total adjustment
            // Save this pending delete at it's original / db-relative position
            deletedDbOrds.add((int)currentChange[0] + ordinalAdjustment);
        }

        int[] deletedDbOrdInts = new int[deletedDbOrds.size()];
        for (int i = 0; i < deletedDbOrdInts.length; i++) {
            deletedDbOrdInts[i] = (deletedDbOrds.get(i));
        }
        return deletedDbOrdInts;
    }


    private void dumpChanges() {
        for (int i = 0; i < mTemplateChanges.size(); i++) {
            Object[] change = mTemplateChanges.get(i);
            Timber.d("dumpChanges() Change %s is type/ord %s/%s", i, change[0], change[1]);
        }
    }


    public @NonNull ArrayList<Object[]> getTemplateChanges() {
        if (mTemplateChanges == null) {
            mTemplateChanges = new ArrayList<>();
        }
        return mTemplateChanges;
    }


    /**
     * Scan the sequence of template add/deletes, looking for the given ordinal.
     * When found, purge that ordinal and shift future changes down if they had ordinals higher than the one purged
     */
    private void compactTemplateChanges(int addedOrdinalToDelete) {

        Timber.d("compactTemplateChanges() merge/purge add/delete ordinal added as %s", addedOrdinalToDelete);
        boolean postChange = false;
        int ordinalAdjustment = 0;
        for (int i = 0; i < mTemplateChanges.size(); i++) {
            Object[] change = mTemplateChanges.get(i);
            int ordinal = (Integer)change[0];
            ChangeType changeType = (ChangeType)change[1];
            Timber.d("compactTemplateChanges() examining change entry %s / %s", ordinal, changeType);

            // Only make adjustments after the ordinal we want to delete was added
            if (!postChange) {
                if (ordinal == addedOrdinalToDelete && changeType == ChangeType.ADD) {
                    Timber.d("compactTemplateChanges() found our entry at index %s", i);
                    // Remove this entry to start compaction, then fix up the loop counter since we altered size
                    postChange = true;
                    mTemplateChanges.remove(i);
                    i--;
                }
                continue;
            }

            // We compact all deletes with higher ordinals, so any delete is below us: shift our comparison basis
            if (changeType == ChangeType.DELETE) {
                ordinalAdjustment++;
                Timber.d("compactTemplateChanges() delete affecting purged template, shifting basis, adj: %s", ordinalAdjustment);
            }

            // If following ordinals were higher, we move them as part of compaction
            if ((ordinal + ordinalAdjustment) > addedOrdinalToDelete) {
                Timber.d("compactTemplateChanges() shifting later/higher ordinal down");
                change[0] = --ordinal;
            }
        }
    }
}