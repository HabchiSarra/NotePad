/*
 * Copyright (C) 2012 Jonas Kalderstam
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nononsenseapps.notepad;

import java.util.ArrayList;
import java.util.Collection;

import com.nononsenseapps.notepad.interfaces.DeleteActionListener;
import com.nononsenseapps.notepad.interfaces.OnEditorDeleteListener;
import com.nononsenseapps.notepad.prefs.MainPrefs;
import com.nononsenseapps.notepad.prefs.PrefsActivity;
import com.nononsenseapps.notepad.prefs.SyncPrefs;
import com.nononsenseapps.ui.ExtrasCursorAdapter;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.ActionBar;
import android.app.ActionBar.OnNavigationListener;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.FragmentTransaction;
import android.app.LoaderManager;
import android.app.SearchManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.CursorLoader;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.Loader;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.database.Cursor;
import android.database.SQLException;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.EditText;

/**
 * Showing a single fragment in an activity.
 */
public class FragmentLayout extends Activity implements
		OnSharedPreferenceChangeListener, OnEditorDeleteListener,
		DeleteActionListener, OnNavigationListener,
		LoaderManager.LoaderCallbacks<Cursor> {
	private static final String TAG = "FragmentLayout";
	private static final String CURRENT_LIST_ID = "currentlistid";
	private static final String CURRENT_LIST_POS = "currentlistpos";
	private static final int CREATE_LIST = 0;
	private static final int RENAME_LIST = 1;
	private static final int DELETE_LIST = 2;
	// public static boolean lightTheme = false;
	public static String currentTheme = MainPrefs.THEME_LIGHT;
	public static boolean shouldRestart = false;
	public static boolean LANDSCAPE_MODE;
	public static boolean AT_LEAST_ICS;
	public static boolean AT_LEAST_HC;

	public final static boolean UI_DEBUG_PRINTS = false;
	public static final String DEFAULTLIST = "standardListId";

	// For my special dropdown navigation item
	public static final int ALL_NOTES_ID = -2;

	public static OnEditorDeleteListener ONDELETELISTENER = null;

	private NotesListFragment list;
	private Menu optionsMenu;

	private ExtrasCursorAdapter mSpinnerAdapter;
	private long currentListId = -1;
	private int currentListPos = 0;

	private long listIdToSelect = -1;
	private boolean beforeBoot = false; // Used to indicate the intent handling
										// how to select items

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		// Must set theme before this
		super.onCreate(savedInstanceState);

		Log.d(TAG, "onCreate");

		LANDSCAPE_MODE = getResources().getBoolean(R.bool.useLandscapeView);
		AT_LEAST_ICS = getResources()
				.getBoolean(R.bool.atLeastIceCreamSandwich);
		AT_LEAST_HC = getResources().getBoolean(R.bool.atLeastHoneycomb);

		if (savedInstanceState != null) {
			if (UI_DEBUG_PRINTS)
				Log.d(TAG, "Reloading state");
			currentListId = savedInstanceState.getLong(CURRENT_LIST_ID);
			currentListPos = savedInstanceState.getInt(CURRENT_LIST_POS);
		}

		// Setting theme here
		readAndSetSettings();

		// Set up dropdown navigation
		ActionBar actionBar = getActionBar();
		actionBar.setDisplayShowTitleEnabled(false);
		actionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_LIST);

		// Will set cursor in Loader
		// mSpinnerAdapter = new ExtrasCursorAdapter(this,
		// R.layout.actionbar_dropdown_item, null,
		// new String[] { NotePad.Lists.COLUMN_NAME_TITLE },
		// new int[] { android.R.id.text1 }, new int[] { -9, -8 },
		// new int[] { R.string.show_from_all_lists, R.string.error_title });
		mSpinnerAdapter = new ExtrasCursorAdapter(this,
				R.layout.actionbar_dropdown_item, null,
				new String[] { NotePad.Lists.COLUMN_NAME_TITLE },
				new int[] { android.R.id.text1 }, new int[] { ALL_NOTES_ID },
				new int[] { R.string.show_from_all_lists });

		mSpinnerAdapter
				.setDropDownViewResource(R.layout.actionbar_dropdown_item);

		// This will listen for navigation callbacks
		actionBar.setListNavigationCallbacks(mSpinnerAdapter, this);

		// XML makes sure notes list is displayed. And editor too in landscape
		// if (lightTheme)
		// setContentView(R.layout.fragment_layout_light);
		// else
		setContentView(R.layout.fragment_layout);

		// Set this as delete listener
		NotesListFragment list = (NotesListFragment) getFragmentManager()
				.findFragmentById(R.id.noteslistfragment);

		list.setOnDeleteListener(this);

		this.list = list;
		// So editor can access it
		ONDELETELISTENER = this;

		// Set a default list to open if one is set
		listIdToSelect = PreferenceManager.getDefaultSharedPreferences(this)
				.getLong(DEFAULTLIST, -1);

		// Handle the intent first, so we know what to possibly select once the
		// loader is finished
		beforeBoot = true;
		onNewIntent(getIntent());
		getLoaderManager().initLoader(0, null, this);
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		optionsMenu = menu;
		return super.onCreateOptionsMenu(menu);
	}

	@Override
	public boolean onPrepareOptionsMenu(Menu menu) {
		MenuItem deleteList = menu.findItem(R.id.menu_deletelist);
		if (deleteList != null) {
			// Only show this button if there is a list to create it in
			if (mSpinnerAdapter.getCount() == 0 || currentListId < 0) {
				deleteList.setVisible(false);
			} else {
				deleteList.setVisible(true);
			}
		}
		MenuItem renameList = menu.findItem(R.id.menu_renamelist);
		if (renameList != null) {
			// Only show this button if there is a list to create it in
			if (mSpinnerAdapter.getCount() == 0 || currentListId < 0) {
				renameList.setVisible(false);
			} else {
				renameList.setVisible(true);
			}
		}
		MenuItem defaultList = menu.findItem(R.id.menu_setdefaultlist);
		if (defaultList != null) {
			// Only show this button if there is a proper list showing
			if (mSpinnerAdapter.getCount() == 0 || currentListId < 0) {
				defaultList.setVisible(false);
			} else {
				defaultList.setVisible(true);
			}
		}

		return super.onPrepareOptionsMenu(menu);
	}

	/**
	 * If the user has a search button, ideally he should be able to use it.
	 * Expand the search provider in that case
	 */
	@Override
	public boolean onKeyUp(int keyCode, KeyEvent event) {
		switch (keyCode) {
		case KeyEvent.KEYCODE_SEARCH:
			if (list != null && list.mSearchItem != null) {
				list.mSearchItem.expandActionView();
			} else if (list != null) {
				onSearchRequested();
			}
			return true;
		case KeyEvent.KEYCODE_BACK:
			// Exit app
			finish();
			return true;
		}
		return false;
	}

	@Override
	public void onSaveInstanceState(Bundle outState) {
		super.onSaveInstanceState(outState);
		// Save current list
		outState.putLong(CURRENT_LIST_ID, currentListId);
		outState.putInt(CURRENT_LIST_POS, currentListPos);
	}

	@Override
	protected void onNewIntent(Intent intent) {
		if (UI_DEBUG_PRINTS)
			Log.d("FragmentLayout", "On New Intent");

		// Search
		if (Intent.ACTION_SEARCH.equals(intent.getAction())) {
			String query = intent.getStringExtra(SearchManager.QUERY);
			// list.onQueryTextChange(query);
			if (list != null && list.mSearchView != null) {
				list.mSearchView.setQuery(query, false);
			} else if (list != null) {
				list.onQueryTextSubmit(query);
			}
			// Edit or View a list or a note.
		} else if (Intent.ACTION_EDIT.equals(intent.getAction())
				|| Intent.ACTION_VIEW.equals(intent.getAction())) {
			if (UI_DEBUG_PRINTS)
				Log.d("FragmentLayout", "On New Intent EDIT");
			// First, if we should display a list
			if (intent.getData() != null
					&& intent.getData().getPath()
							.startsWith(NotePad.Lists.PATH_VISIBLE_LIST_ID)) {
				// Get id to display
				String newId = intent.getData().getPathSegments()
						.get(NotePad.Lists.ID_PATH_POSITION);
				long listId = Long.parseLong(newId);
				// Handle it differently depending on if the app has already
				// loaded or not.
				openListFromIntent(listId);
			} else if (intent.getData() != null
					&& intent.getExtras() != null
					&& intent.getData().getPath()
							.startsWith(NotePad.Notes.PATH_VISIBLE_NOTE_ID)) {
				if (list != null) {
					long listId = intent.getExtras().getLong(
							NotePad.Notes.COLUMN_NAME_LIST, -1);
					// Open the containing list if we have to. No need to change
					// lists
					// if we are already displaying all notes.
					if (listId != -1 && currentListId != ALL_NOTES_ID
							&& currentListId != listId) {
						openListFromIntent(listId);
					}
					if (listId != -1) {
						list.handleNoteIntent(intent);
					}
				}
			}
		} else if (Intent.ACTION_INSERT.equals(intent.getAction())) {
			if (UI_DEBUG_PRINTS)
				Log.d("FragmentLayout", "On New Intent INSERT");
			if (intent.getType() != null
					&& intent.getType().equals(NotePad.Lists.CONTENT_TYPE)
					|| intent.getData() != null
					&& intent.getData().equals(
							NotePad.Lists.CONTENT_VISIBLE_URI)) {
				// get Title
				if (intent.getExtras() != null) {
					String title = intent.getExtras().getString(
							NotePad.Lists.COLUMN_NAME_TITLE, "");
					createList(title);
				}
			} else if (intent.getType() != null
					&& intent.getType().equals(NotePad.Notes.CONTENT_TYPE)
					|| intent.getData() != null
					&& intent.getData().equals(
							NotePad.Notes.CONTENT_VISIBLE_URI)) {
				Log.d("FragmentLayout", "INSERT NOTE");
				if (list != null && intent.getExtras() != null) {
					long listId = intent.getExtras().getLong(
							NotePad.Notes.COLUMN_NAME_LIST, -1);
					// Open the containing list if we have to. No need to change
					// lists
					// if we are already displaying all notes.
					if (listId != -1 && currentListId != ALL_NOTES_ID
							&& currentListId != listId) {
						openListFromIntent(listId);
					}
					if (listId != -1) {
						list.handleNoteIntent(intent);
					}
				}
			}
		}
	}

	/**
	 * This is meant to be called from the intent handling. It handles the two
	 * possible cases that this app was already running when it received the
	 * intent or it was started fresh with the intent meaning we have to handle
	 * the opening asynchronously.
	 * 
	 * @param listId
	 */
	private void openListFromIntent(long listId) {
		if (beforeBoot) {
			// Set the variable to be selected after the loader has
			// finished its query
			listIdToSelect = listId;
			Log.d(TAG, "beforeBoot setting future id");
		} else {
			// Select the list directly since the loader is done
			int pos = getPosOfId(listId);
			Log.d("FragmentLayout", "pos: " + pos);
			if (pos > -1) {
				// select it
				ActionBar ab = getActionBar();
				if (ab != null && ab.getSelectedNavigationIndex() != pos) {
					ab.setSelectedNavigationItem(pos);
				}
			}
		}
	}

	@Override
	protected void onResume() {
		if (UI_DEBUG_PRINTS)
			Log.d("FragmentLayout", "onResume");
		if (shouldRestart) {
			if (UI_DEBUG_PRINTS)
				Log.d("FragmentLayout", "Should refresh");
			restartAndRefresh();
		}
		super.onResume();
	}

	@Override
	protected Dialog onCreateDialog(int id) {
		switch (id) {
		case DELETE_LIST:
			final Dialog deleteDialog = new Dialog(this);
			deleteDialog.setContentView(R.layout.delete_list_dialog);
			deleteDialog.setTitle(R.string.menu_deletelist);

			Button dYesButton = (Button) deleteDialog
					.findViewById(R.id.d_dialog_yes);
			dYesButton.setOnClickListener(new OnClickListener() {
				@Override
				public void onClick(View v) {
					deleteCurrentList();
					deleteDialog.dismiss();
				}
			});

			Button dNoButton = (Button) deleteDialog
					.findViewById(R.id.d_dialog_no);
			dNoButton.setOnClickListener(new OnClickListener() {
				@Override
				public void onClick(View v) {
					deleteDialog.cancel();
				}
			});
			return deleteDialog;
		case CREATE_LIST:
			final Dialog dialog = new Dialog(this);
			dialog.setContentView(R.layout.create_list_dialog);
			dialog.setTitle(R.string.menu_createlist);

			EditText title = (EditText) dialog.findViewById(R.id.editTitle);
			title.setText("");

			Button yesButton = (Button) dialog.findViewById(R.id.dialog_yes);
			yesButton.setOnClickListener(new OnClickListener() {
				@Override
				public void onClick(View v) {
					EditText title = (EditText) dialog
							.findViewById(R.id.editTitle);
					createList(title.getText().toString());
					title.setText("");
					dialog.dismiss();
				}
			});

			Button noButton = (Button) dialog.findViewById(R.id.dialog_no);
			noButton.setOnClickListener(new OnClickListener() {
				@Override
				public void onClick(View v) {
					dialog.cancel();
				}
			});
			return dialog;
		case RENAME_LIST:
			final Dialog renameDialog = new Dialog(this);
			renameDialog.setContentView(R.layout.rename_list_dialog);
			renameDialog.setTitle(R.string.menu_renamelist);

			EditText renameTitle = (EditText) renameDialog
					.findViewById(R.id.renameTitle);
			renameTitle.setText("");

			Button rYesButton = (Button) renameDialog
					.findViewById(R.id.r_dialog_yes);
			rYesButton.setOnClickListener(new OnClickListener() {
				@Override
				public void onClick(View v) {
					EditText renameTitle = (EditText) renameDialog
							.findViewById(R.id.renameTitle);
					renameList(renameTitle.getText().toString());
					renameTitle.setText("");
					renameDialog.dismiss();
				}
			});

			Button rNoButton = (Button) renameDialog
					.findViewById(R.id.r_dialog_no);
			rNoButton.setOnClickListener(new OnClickListener() {
				@Override
				public void onClick(View v) {
					renameDialog.cancel();
				}
			});
			return renameDialog;

		default:
			if (UI_DEBUG_PRINTS)
				Log.d(TAG, "Wanted to create some dialog: " + id);
			return null;
		}
	}

	protected void createList(String title) {
		if (UI_DEBUG_PRINTS)
			Log.d(TAG, "Create list: " + title);
		// I will not allow empty names for lists
		if (!title.equals("")) {
			ContentValues values = new ContentValues();
			values.put(NotePad.Lists.COLUMN_NAME_TITLE, title);
			// Add list
			Uri listUri = getContentResolver().insert(
					NotePad.Lists.CONTENT_URI, values);
			// Also create an empty note in it
			if (listUri != null) {
				createNote(
						getContentResolver(),
						Long.parseLong(listUri.getPathSegments().get(
								NotePad.Lists.ID_PATH_POSITION)));
				// Select list
				listIdToSelect = Long.parseLong(listUri.getLastPathSegment());
			}
		}
	}

	private int getPosOfId(long id) {
		int length = mSpinnerAdapter.getCount();
		int position;
		for (position = 0; position < length; position++) {
			if (id == mSpinnerAdapter.getItemId(position)) {
				break;
			}
		}
		if (position == length) {
			// Happens both if list is empty
			// and if id is -1
			position = -1;
		}
		return position;
	}

	protected void renameList(String title) {
		if (UI_DEBUG_PRINTS)
			Log.d(TAG, "Rename list: " + title);
		// I will not allow empty names for lists
		// Also must have a valid id
		if (!title.equals("") && currentListId > -1) {
			ContentValues values = new ContentValues();
			values.put(NotePad.Lists.COLUMN_NAME_TITLE, title);
			// Update list
			getContentResolver().update(
					Uri.withAppendedPath(NotePad.Lists.CONTENT_ID_URI_BASE,
							Long.toString(currentListId)), values, null, null);
		}
	}

	/**
	 * Returns true if user has activated sync and there is a valid account name
	 * selected (not "")
	 * 
	 * @return
	 */
	public static boolean shouldMarkAsDeleted(Context context) {
		SharedPreferences settings = PreferenceManager
				.getDefaultSharedPreferences(context);
		if (settings == null)
			return false;
		else
			return (settings.getBoolean(
					SyncPrefs.KEY_SYNC_ENABLE, false) && !settings
					.getString(SyncPrefs.KEY_ACCOUNT, "")
					.isEmpty());
	}

	/**
	 * Marks the current list and all the tasks contained in it as deleted in
	 * the database. Will be deleted on next sync.
	 */
	protected void deleteCurrentList() {
		if (UI_DEBUG_PRINTS)
			Log.d(TAG, "Delete current list");
		// Only if id is valid
		if (currentListId > -1) {
			// Only mark as deleted so it is synced
			if (shouldMarkAsDeleted(this)) {
				ContentValues values = new ContentValues();
				values.put(NotePad.Lists.COLUMN_NAME_DELETED, 1);
				// Mark list as deleted
				getContentResolver().update(
						Uri.withAppendedPath(NotePad.Lists.CONTENT_ID_URI_BASE,
								Long.toString(currentListId)), values, null,
						null);
				// Mark tasks as hidden locally. They are deleted with the list
				// in
				// the sync
				values = new ContentValues();
				values.put(NotePad.Notes.COLUMN_NAME_DELETED, 1);
				values.put(NotePad.Notes.COLUMN_NAME_MODIFIED, 0); // Yes zero,
																	// we
																	// don't
																	// want to
																	// sync
																	// tasks in
																	// deleted
																	// lists
				getContentResolver()
						.update(NotePad.Notes.CONTENT_URI,
								values,
								NotePad.Notes.COLUMN_NAME_LIST + " IS "
										+ currentListId, null);
			} else {
				// Delete for real
				getContentResolver().delete(
						Uri.withAppendedPath(NotePad.Lists.CONTENT_ID_URI_BASE,
								Long.toString(currentListId)), null, null);
			}

			// Remove default setting if this is the default list
			long defaultListId = PreferenceManager.getDefaultSharedPreferences(
					this).getLong(DEFAULTLIST, -1);
			if (currentListId == defaultListId) {
				// Remove knowledge of default list
				SharedPreferences.Editor prefEditor = PreferenceManager
						.getDefaultSharedPreferences(this).edit();
				prefEditor.remove(DEFAULTLIST);
				prefEditor.commit();
			}
		}
	}

	public void restartAndRefresh() {
		if (UI_DEBUG_PRINTS)
			Log.d("FragmentLayout", "Should restart and refresh");
		shouldRestart = false;
		Intent intent = getIntent();
		overridePendingTransition(0, 0);
		intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
		finish();
		overridePendingTransition(0, 0);
		startActivity(intent);
	}

	public void onSharedPreferenceChanged(SharedPreferences sharedPreferences,
			String key) {
		// Need to restart to allow themes and such to go into effect
		if (key.equals(MainPrefs.KEY_THEME)) {
			shouldRestart = true;
		}
	}

	private void readAndSetSettings() {
		// Read settings and set
		SharedPreferences prefs = PreferenceManager
				.getDefaultSharedPreferences(this);

		currentTheme = prefs.getString(MainPrefs.KEY_THEME,
				currentTheme);

		setTypeOfTheme();

		String sortType = prefs.getString(
				MainPrefs.KEY_SORT_TYPE,
				NotePad.Notes.DEFAULT_SORT_TYPE);
		String sortOrder = prefs.getString(
				MainPrefs.KEY_SORT_ORDER,
				NotePad.Notes.DEFAULT_SORT_ORDERING);

		NotePad.Notes.SORT_ORDER = sortType + " " + sortOrder;
		if (UI_DEBUG_PRINTS)
			Log.d("ReadingSettings", "sortOrder is: "
					+ NotePad.Notes.SORT_ORDER);

		// We want to be notified of future changes
		prefs.registerOnSharedPreferenceChangeListener(this);
	}

	private void setTypeOfTheme() {
		if (MainPrefs.THEME_LIGHT_ICS_AB.equals(currentTheme)) {
			setTheme(R.style.ThemeHoloLightDarkActonBar);
		} else if (MainPrefs.THEME_LIGHT.equals(currentTheme)) {
			setTheme(R.style.ThemeHoloLight);
		} else {
			setTheme(R.style.ThemeHolo);
		}
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case R.id.menu_delete:
			onDeleteAction();
			break;
		case R.id.menu_preferences:
			if (UI_DEBUG_PRINTS)
				Log.d("NotesListFragment", "onOptionsSelection pref");
			showPrefs();
			return true;
		case R.id.menu_createlist:
			// Create dialog
			if (UI_DEBUG_PRINTS)
				Log.d(TAG, "menu_createlist");
			showDialog(CREATE_LIST);
			return true;
		case R.id.menu_renamelist:
			// Create dialog
			if (UI_DEBUG_PRINTS)
				Log.d(TAG, "menu_renamelist");
			showDialog(RENAME_LIST);
			return true;
		case R.id.menu_deletelist:
			// Create dialog
			if (UI_DEBUG_PRINTS)
				Log.d(TAG, "menu_deletelist");
			showDialog(DELETE_LIST);
			return true;
		case R.id.menu_setdefaultlist:
			if (currentListId >= 0) {
				SharedPreferences.Editor prefEditor = PreferenceManager
						.getDefaultSharedPreferences(this).edit();
				prefEditor.putLong(DEFAULTLIST, currentListId);
				prefEditor.commit();
			}
			return true;
		case R.id.menu_search:
			if (list != null && list.mSearchItem != null) {
				list.mSearchItem.expandActionView();
			} else if (list != null) {
				// Launches the search window
				onSearchRequested();
			}
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	private void showPrefs() {
		// launch a new activity to display the dialog
		Intent intent = new Intent();
		intent.setClass(this, PrefsActivity.class);
		startActivity(intent);
	}

	/**
	 * This is a secondary activity, to show what the user has selected when the
	 * screen is not large enough to show it all in one activity.
	 */
	public static class NotesEditorActivity extends Activity implements
			DeleteActionListener {
		private static final String TAG = "NotesEditorActivity";
		private NotesEditorFragment editorFragment;
		private long currentId = -1;

		@Override
		protected void onCreate(Bundle savedInstanceState) {
			// Make sure to set themes before this
			super.onCreate(savedInstanceState);

			if (UI_DEBUG_PRINTS)
				Log.d("NotesEditorActivity", "onCreate");

			if (MainPrefs.THEME_LIGHT_ICS_AB
					.equals(FragmentLayout.currentTheme)) {
				setTheme(R.style.ThemeHoloLightDarkActonBar);
			} else if (MainPrefs.THEME_LIGHT
					.equals(FragmentLayout.currentTheme)) {
				setTheme(R.style.ThemeHoloLight);
			} else {
				setTheme(R.style.ThemeHolo);
			}

			// Set up navigation (adds nice arrow to icon)
			ActionBar actionBar = getActionBar();
			if (actionBar != null) {
				actionBar.setDisplayHomeAsUpEnabled(true);
				actionBar.setDisplayShowTitleEnabled(false);
			}

			if (getResources().getBoolean(R.bool.useLandscapeView)) {
				// If the screen is now in landscape mode, we can show the
				// dialog in-line with the list so we don't need this activity.
				Log.d("NotesEditorActivity",
						"Landscape mode detected, killing myself");
				finish();
				return;
			}

			setContentView(R.layout.note_editor_activity);

			this.currentId = getIntent().getExtras().getLong(
					NotesEditorFragment.KEYID);

			if (UI_DEBUG_PRINTS)
				Log.d("NotesEditorActivity", "Time to show the note!");
			// if (savedInstanceState == null) {
			// During initial setup, plug in the details fragment.
			// Set this as delete listener
			editorFragment = (NotesEditorFragment) getFragmentManager()
					.findFragmentById(R.id.portrait_editor);
			if (editorFragment != null) {
				editorFragment.setValues(currentId);
			}
		}

		@Override
		public boolean onOptionsItemSelected(MenuItem item) {
			switch (item.getItemId()) {
			case android.R.id.home:
				finish();
				break;
			case R.id.menu_delete:
				onDeleteAction();
				return true;
			case R.id.menu_revert:
				setResult(Activity.RESULT_CANCELED);
				finish();
				break;
			}
			return super.onOptionsItemSelected(item);
		}

		@Override
		public void onPause() {
			super.onPause();
			if (UI_DEBUG_PRINTS)
				Log.d("NotesEditorActivity", "onPause");
			if (isFinishing()) {
				// Log.d("NotesEditorActivity",
				// "onPause, telling list to display list");
				// SharedPreferences.Editor prefEditor = PreferenceManager
				// .getDefaultSharedPreferences(this).edit();
				// prefEditor.putBoolean(NotesListFragment.SHOWLISTKEY, true);
				// prefEditor.commit();
			}
		}

		@Override
		public void onResume() {
			super.onResume();
			if (UI_DEBUG_PRINTS)
				Log.d("NotesEditorActivity", "onResume");
			if (getResources().getBoolean(R.bool.useLandscapeView)) {
				if (UI_DEBUG_PRINTS)
					Log.d("NotesEditorActivity", "onResume, killing myself");
				// Log.d("NotesEditorActivity",
				// "onResume telling list to display me");
				// SharedPreferences.Editor prefEditor = PreferenceManager
				// .getDefaultSharedPreferences(this).edit();
				// prefEditor.putBoolean(NotesListFragment.SHOWLISTKEY, false);
				// prefEditor.commit();
				finish();
			}
		}

		@Override
		public void onDeleteAction() {
			if (UI_DEBUG_PRINTS)
				Log.d(TAG, "onDeleteAction");
			editorFragment.setSelfAction(); // Don't try to reload the deleted
											// note
			FragmentLayout.deleteNote(this, editorFragment.getCurrentNoteId());
			setResult(Activity.RESULT_CANCELED);
			finish();
		}
	}

	@Override
	public void onEditorDelete(long id) {
		deleteNote(this, id);
	}

	/**
	 * Calls deleteNotes wrapped in ArrayList
	 * 
	 * @param id
	 */
	public static void deleteNote(Context context, long id) {
		if (UI_DEBUG_PRINTS)
			Log.d(TAG, "deleteNote: " + id);
		// Only do this for valid id
		if (id > -1) {
			ArrayList<Long> idList = new ArrayList<Long>();
			idList.add(id);
			deleteNotes(context, idList);
		}
	}

	/**
	 * Delete all notes given from database Only marks them as deleted if sync
	 * is enabled
	 * 
	 * @param ids
	 */
	public static void deleteNotes(Context context, Iterable<Long> ids) {
		ContentResolver resolver = context.getContentResolver();
		boolean shouldMark = shouldMarkAsDeleted(context);
		for (long id : ids) {
			if (shouldMark) {
				ContentValues values = new ContentValues();
				values.put(NotePad.Notes.COLUMN_NAME_DELETED, "1");
				resolver.update(NotesEditorFragment.getUriFrom(id), values,
						null, null);
			} else {
				resolver.delete(NotesEditorFragment.getUriFrom(id), null, null);
			}
		}
	}

	/**
	 * Inserts a new note in the designated list
	 * 
	 * @param resolver
	 * @param listId
	 * @return
	 */
	public static Uri createNote(ContentResolver resolver, long listId) {
		if (listId > -1) {
			ContentValues values = new ContentValues();
			// Must always include list
			values.put(NotePad.Notes.COLUMN_NAME_LIST, listId);
			try {
				return resolver.insert(NotePad.Notes.CONTENT_URI, values);
			} catch (SQLException e) {
				if (UI_DEBUG_PRINTS)
					Log.d(TAG,
							"Failed to insert note. Sure there is a list to insert into?");
				return null;
			}
		} else {
			return null;
		}
	}

	@Override
	public void onMultiDelete(Collection<Long> ids, long curId) {
		if (ids.contains(curId)) {
			if (UI_DEBUG_PRINTS)
				Log.d("FragmentLayout",
						"id was contained in multidelete, setting no save first");
			NotesEditorFragment editor = (NotesEditorFragment) getFragmentManager()
					.findFragmentById(R.id.editor_container);
			if (editor != null) {
				editor.setSelfAction();
			}
		}
		if (UI_DEBUG_PRINTS)
			Log.d("FragmentLayout", "deleting notes...");
		deleteNotes(this, ids);
	}

	public static class NotesPreferencesDialog extends Activity {
		public static final int DIALOG_ACCOUNTS = 23;
		private MainPrefs prefFragment;

		@Override
		protected void onCreate(Bundle savedInstanceState) {
			super.onCreate(savedInstanceState);

			if (MainPrefs.THEME_DARK
					.equals(FragmentLayout.currentTheme)) {
				setTheme(R.style.ThemeHoloDialogNoActionBar);
			} else {
				setTheme(R.style.ThemeHoloLightDialogNoActionBar);
			}

			// Display the fragment as the main content.
			prefFragment = new MainPrefs();
			FragmentTransaction ft = getFragmentManager().beginTransaction();
			ft.replace(android.R.id.content, prefFragment);
			ft.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE);
			ft.commit();
		}

		@Override
		public void onLowMemory() {
			// Body
		}
		
		@Override
		public boolean onOptionsItemSelected(MenuItem item) {
			switch (item.getItemId()) {
			case android.R.id.home:
				finish();
				break;
			}
			return super.onOptionsItemSelected(item);
		}
//		@Override
//		protected Dialog onCreateDialog(int id) {
//			switch (id) {
//			case DIALOG_ACCOUNTS:
//				AlertDialog.Builder builder = new AlertDialog.Builder(this);
//				builder.setTitle("Select a Google account");
//				final Account[] accounts = AccountManager.get(this)
//						.getAccountsByType("com.google");
//				final int size = accounts.length;
//				String[] names = new String[size];
//				for (int i = 0; i < size; i++) {
//					names[i] = accounts[i].name;
//				}
//				// TODO
//				// Could add a clear alternative here
//				builder.setItems(names, new DialogInterface.OnClickListener() {
//					public void onClick(DialogInterface dialog, int which) {
//						// Stuff to do when the account is selected by the user
//						prefFragment.accountSelected(accounts[which]);
//					}
//				});
//				return builder.create();
//			}
//			return null;
//		}

	}

	@Override
	public void onDeleteAction() {
		// both list and editor should be notified
		NotesListFragment list = (NotesListFragment) getFragmentManager()
				.findFragmentById(R.id.noteslistfragment);
		NotesEditorFragment editor = (NotesEditorFragment) getFragmentManager()
				.findFragmentById(R.id.editor_container);
		if (editor != null)
			editor.setSelfAction();
		// delete note
		if (list != null)
			list.onDelete();
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		Log.d(TAG, "onDestroy");
	}

	@Override
	public boolean onNavigationItemSelected(int itemPosition, long itemId) {
		if (UI_DEBUG_PRINTS)
			Log.d(TAG, "onNavigationItemSelected pos: " + itemPosition
					+ " id: " + itemId);

		// Change the active list
		currentListId = itemId;
		currentListPos = itemPosition;
		// Display list'
		if (list != null) {
			list.showList(itemId);
		}
		return true;
	}

	@Override
	public Loader<Cursor> onCreateLoader(int id, Bundle args) {

		// This is called when a new Loader needs to be created. This
		// sample only has one Loader, so we don't care about the ID.
		Uri baseUri = NotePad.Lists.CONTENT_URI;
		// Now create and return a CursorLoader that will take care of
		// creating a Cursor for the data being displayed.

		return new CursorLoader(this, baseUri, new String[] {
				NotePad.Lists._ID, NotePad.Lists.COLUMN_NAME_TITLE },
				NotePad.Lists.COLUMN_NAME_DELETED + " IS NOT 1", // un-deleted
																	// records.
				null, NotePad.Lists.SORT_ORDER // Use the default sort order.
		);
	}

	@Override
	public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
		mSpinnerAdapter.swapCursor(data);

		if (listIdToSelect > -1 || listIdToSelect == ALL_NOTES_ID) {
			int position = getPosOfId(listIdToSelect);
			if (position > -1) {
				currentListPos = position;
				currentListId = listIdToSelect;
				getActionBar().setSelectedNavigationItem(position);
			}
			listIdToSelect = -1;
		}

		if (optionsMenu != null) {
			MenuItem createNote = optionsMenu.findItem(R.id.menu_add);
			if (createNote != null) {
				// Only show this button if there is a list to create notes in
				if (mSpinnerAdapter.getCount() == 0) {
					createNote.setVisible(false);
				} else {
					createNote.setVisible(true);
				}
			}
		}
		beforeBoot = false; // Need to do it here
	}

	@Override
	public void onLoaderReset(Loader<Cursor> loader) {
		mSpinnerAdapter.swapCursor(null);
	}
}
