package com.termux.app;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.termux.R;
import com.termux.drawer.DrawerLayout;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSession.SessionChangedCallback;
import com.termux.view.TerminalKeyListener;
import com.termux.view.TerminalView;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnShowListener;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Vibrator;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.StyleSpan;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnKeyListener;
import android.view.View.OnLongClickListener;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.AdapterView.OnItemLongClickListener;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * A terminal emulator activity.
 * 
 * See
 * <ul>
 * <li>http://www.mongrel-phones.com.au/default/how_to_make_a_local_service_and_bind_to_it_in_android</li>
 * <li>https://code.google.com/p/android/issues/detail?id=6426</li>
 * </ul>
 * about memory leaks.
 */
public final class TermuxActivity extends Activity implements ServiceConnection {

	private static final int CONTEXTMENU_SELECT_ID = 0;
	private static final int CONTEXTMENU_PASTE_ID = 3;
	private static final int CONTEXTMENU_KILL_PROCESS_ID = 4;
	private static final int CONTEXTMENU_RESET_TERMINAL_ID = 5;
	private static final int CONTEXTMENU_STYLING_ID = 6;
	private static final int CONTEXTMENU_TOGGLE_FULLSCREEN_ID = 7;
	private static final int CONTEXTMENU_HELP_ID = 8;

	private static final int MAX_SESSIONS = 8;

	private static final String RELOAD_STYLE_ACTION = "com.termux.app.reload_style";

	/** The main view of the activity showing the terminal. */
	@NonNull TerminalView mTerminalView;

	final FullScreenHelper mFullScreenHelper = new FullScreenHelper(this);

	TermuxPreferences mSettings;

	/**
	 * The connection to the {@link TermuxService}. Requested in {@link #onCreate(Bundle)} with a call to
	 * {@link #bindService(Intent, ServiceConnection, int)}, and obtained and stored in
	 * {@link #onServiceConnected(ComponentName, IBinder)}.
	 */
	TermuxService mTermService;

	/** Initialized in {@link #onServiceConnected(ComponentName, IBinder)}. */
	ArrayAdapter<TerminalSession> mListViewAdapter;

	/** The last toast shown, used cancel current toast before showing new in {@link #showToast(String, boolean)}. */
	Toast mLastToast;

	/**
	 * If between onResume() and onStop(). Note that only one session is in the foreground of the terminal view at the
	 * time, so if the session causing a change is not in the foreground it should probably be treated as background.
	 */
	boolean mIsVisible;

	private final BroadcastReceiver mBroadcastReceiever = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			if (mIsVisible) {
				String whatToReload = intent.getStringExtra(RELOAD_STYLE_ACTION);
				if (whatToReload == null || "colors".equals(whatToReload)) mTerminalView.checkForColors();
				if (whatToReload == null || "font".equals(whatToReload)) mTerminalView.checkForTypeface();
			}
		}
	};

	@Override
	public void onCreate(Bundle bundle) {
		super.onCreate(bundle);

		// Prevent overdraw:
		getWindow().getDecorView().setBackground(null);

		setContentView(R.layout.drawer_layout);
		mTerminalView = (TerminalView) findViewById(R.id.terminal_view);
		mSettings = new TermuxPreferences(this);
		mTerminalView.setTextSize(mSettings.getFontSize());
		mFullScreenHelper.setImmersive(mSettings.isFullScreen());
		mTerminalView.requestFocus();

		OnKeyListener keyListener = new OnKeyListener() {
			@Override
			public boolean onKey(View v, int keyCode, KeyEvent event) {
				if (event.getAction() != KeyEvent.ACTION_DOWN) return false;

				final TerminalSession currentSession = getCurrentTermSession();
				if (currentSession == null) return false;

				if (keyCode == KeyEvent.KEYCODE_ENTER && !currentSession.isRunning()) {
					// Return pressed with finished session - remove it.
					currentSession.finishIfRunning();

					int index = mTermService.removeTermSession(currentSession);
					mListViewAdapter.notifyDataSetChanged();
					if (mTermService.getSessions().isEmpty()) {
						// There are no sessions to show, so finish the activity.
						finish();
					} else {
						if (index >= mTermService.getSessions().size()) {
							index = mTermService.getSessions().size() - 1;
						}
						switchToSession(mTermService.getSessions().get(index));
					}
					return true;
				} else if (!(event.isCtrlPressed() && event.isShiftPressed())) {
					// Only hook shortcuts with Ctrl+Shift down.
					return false;
				}

				// Get the unmodified code point:
				int unicodeChar = event.getUnicodeChar(0);

				if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || unicodeChar == 'n'/* next */) {
					int index = mTermService.getSessions().indexOf(currentSession);
					if (++index >= mTermService.getSessions().size()) index = 0;
					switchToSession(mTermService.getSessions().get(index));
				} else if (keyCode == KeyEvent.KEYCODE_DPAD_UP || unicodeChar == 'p' /* previous */) {
					int index = mTermService.getSessions().indexOf(currentSession);
					if (--index < 0) index = mTermService.getSessions().size() - 1;
					switchToSession(mTermService.getSessions().get(index));
				} else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
					getDrawer().openDrawer(Gravity.START);
				} else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
					getDrawer().closeDrawers();
				} else if (unicodeChar == 'f'/* full screen */) {
					toggleImmersive();
				} else if (unicodeChar == 'm'/* menu */) {
					mTerminalView.showContextMenu();
				} else if (unicodeChar == 'r'/* rename */) {
					renameSession(currentSession);
				} else if (unicodeChar == 'c'/* create */) {
					addNewSession(false, null);
				} else if (unicodeChar == 'u' /* urls */) {
					showUrlSelection();
				} else if (unicodeChar == 'v') {
					doPaste();
				} else if (unicodeChar == '+' || event.getUnicodeChar(KeyEvent.META_SHIFT_ON) == '+') {
					// We also check for the shifted char here since shift may be required to produce '+',
					// see https://github.com/termux/termux-api/issues/2
					changeFontSize(true);
				} else if (unicodeChar == '-') {
					changeFontSize(false);
				} else if (unicodeChar >= '1' && unicodeChar <= '9') {
					int num = unicodeChar - '1';
					if (mTermService.getSessions().size() > num) switchToSession(mTermService.getSessions().get(num));
				}
				return true;
			}
		};
		mTerminalView.setOnKeyListener(keyListener);
		findViewById(R.id.left_drawer_list).setOnKeyListener(keyListener);

		mTerminalView.setOnKeyListener(new TerminalKeyListener() {
			@Override
			public float onScale(float scale) {
				if (scale < 0.9f || scale > 1.1f) {
					boolean increase = scale > 1.f;
					changeFontSize(increase);
					return 1.0f;
				}
				return scale;
			}

			@Override
			public void onLongPress(MotionEvent event) {
				mTerminalView.showContextMenu();
			}

			@Override
			public void onSingleTapUp(MotionEvent e) {
				// Toggle keyboard visibility if tapping with a finger:
				InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
				imm.toggleSoftInput(InputMethodManager.SHOW_IMPLICIT, 0);
			}

		});

		findViewById(R.id.new_session_button).setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				addNewSession(false, null);
			}
		});

		findViewById(R.id.new_session_button).setOnLongClickListener(new OnLongClickListener() {
			@Override
			public boolean onLongClick(View v) {
				Resources res = getResources();
				new AlertDialog.Builder(TermuxActivity.this).setTitle(R.string.new_session)
						.setItems(new String[] { res.getString(R.string.new_session_normal_unnamed), res.getString(R.string.new_session_normal_named),
								res.getString(R.string.new_session_failsafe) }, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						switch (which) {
						case 0:
							addNewSession(false, null);
							break;
						case 1:
							DialogUtils.textInput(TermuxActivity.this, R.string.session_new_named_title, R.string.session_new_named_positive_button, null,
									new DialogUtils.TextSetListener() {
								@Override
								public void onTextSet(String text) {
									addNewSession(false, text);
								}
							});
							break;
						case 2:
							addNewSession(true, null);
							break;
						}
					}
				}).show();
				return true;
			}
		});

		findViewById(R.id.toggle_keyboard_button).setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
				imm.toggleSoftInput(InputMethodManager.SHOW_IMPLICIT, 0);
				getDrawer().closeDrawers();
			}
		});

		registerForContextMenu(mTerminalView);

		Intent serviceIntent = new Intent(this, TermuxService.class);
		// Start the service and make it run regardless of who is bound to it:
		startService(serviceIntent);
		if (!bindService(serviceIntent, this, 0)) throw new RuntimeException("bindService() failed");

		mTerminalView.checkForTypeface();
		mTerminalView.checkForColors();

		TermuxInstaller.setupStorageSymlink(this);
	}

	/**
	 * Part of the {@link ServiceConnection} interface. The service is bound with
	 * {@link #bindService(Intent, ServiceConnection, int)} in {@link #onCreate(Bundle)} which will cause a call to this
	 * callback method.
	 */
	@Override
	public void onServiceConnected(ComponentName componentName, IBinder service) {
		mTermService = ((TermuxService.LocalBinder) service).service;

		mTermService.mSessionChangeCallback = new SessionChangedCallback() {
			@Override
			public void onTextChanged(TerminalSession changedSession) {
				if (!mIsVisible) return;
				if (getCurrentTermSession() == changedSession) mTerminalView.onScreenUpdated();
			}

			@Override
			public void onTitleChanged(TerminalSession updatedSession) {
				if (!mIsVisible) return;
				if (updatedSession != getCurrentTermSession()) {
					// Only show toast for other sessions than the current one, since the user
					// probably consciously caused the title change to change in the current session
					// and don't want an annoying toast for that.
					showToast(toToastTitle(updatedSession), false);
				}
				mListViewAdapter.notifyDataSetChanged();
			}

			@Override
			public void onSessionFinished(final TerminalSession finishedSession) {
				if (mTermService.mWantsToStop) {
					// The service wants to stop as soon as possible.
					finish();
					return;
				}
				if (mIsVisible && finishedSession != getCurrentTermSession()) {
					// Show toast for non-current sessions that exit.
					int indexOfSession = mTermService.getSessions().indexOf(finishedSession);
					// Verify that session was not removed before we got told about it finishing:
					if (indexOfSession >= 0) showToast(toToastTitle(finishedSession) + " - exited", true);
				}
				mListViewAdapter.notifyDataSetChanged();
			}

			@Override
			public void onClipboardText(TerminalSession session, String text) {
				if (!mIsVisible) return;
				showToast("Clipboard set:\n\"" + text + "\"", true);
				ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
				clipboard.setPrimaryClip(new ClipData(null, new String[] { "text/plain" }, new ClipData.Item(text)));
			}

			@Override
			public void onBell(TerminalSession session) {
				if (mIsVisible) ((Vibrator) getSystemService(VIBRATOR_SERVICE)).vibrate(50);
			}
		};

		ListView listView = (ListView) findViewById(R.id.left_drawer_list);
		mListViewAdapter = new ArrayAdapter<TerminalSession>(getApplicationContext(), R.layout.line_in_drawer, mTermService.getSessions()) {
			final StyleSpan boldSpan = new StyleSpan(Typeface.BOLD);
			final StyleSpan italicSpan = new StyleSpan(Typeface.ITALIC);

			@Override
			public View getView(int position, View convertView, ViewGroup parent) {
				View row = convertView;
				if (row == null) {
					LayoutInflater inflater = getLayoutInflater();
					row = inflater.inflate(R.layout.line_in_drawer, parent, false);
				}

				TerminalSession sessionAtRow = getItem(position);
				boolean sessionRunning = sessionAtRow.isRunning();

				TextView firstLineView = (TextView) row.findViewById(R.id.row_line);

				String name = sessionAtRow.mSessionName;
				String sessionTitle = sessionAtRow.getTitle();

				String numberPart = "[" + (position + 1) + "] ";
				String sessionNamePart = (TextUtils.isEmpty(name) ? "" : name);
				String sessionTitlePart = (TextUtils.isEmpty(sessionTitle) ? "" : ((sessionNamePart.isEmpty() ? "" : "\n") + sessionTitle));

				String text = numberPart + sessionNamePart + sessionTitlePart;
				SpannableString styledText = new SpannableString(text);
				styledText.setSpan(boldSpan, 0, numberPart.length() + sessionNamePart.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
				styledText.setSpan(italicSpan, numberPart.length() + sessionNamePart.length(), text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

				firstLineView.setText(styledText);

				if (sessionRunning) {
					firstLineView.setPaintFlags(firstLineView.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
				} else {
					firstLineView.setPaintFlags(firstLineView.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
				}
				int color = sessionRunning || sessionAtRow.getExitStatus() == 0 ? Color.BLACK : Color.RED;
				firstLineView.setTextColor(color);
				return row;
			}
		};
		listView.setAdapter(mListViewAdapter);
		listView.setOnItemClickListener(new OnItemClickListener() {
			@Override
			public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
				TerminalSession clickedSession = mListViewAdapter.getItem(position);
				switchToSession(clickedSession);
				getDrawer().closeDrawers();
			}
		});
		listView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
			@Override
			public boolean onItemLongClick(AdapterView<?> parent, View view, final int position, long id) {
				final TerminalSession selectedSession = mListViewAdapter.getItem(position);
				renameSession(selectedSession);
				return true;
			}
		});

		if (mTermService.getSessions().isEmpty()) {
			if (mIsVisible) {
				TermuxInstaller.setupIfNeeded(TermuxActivity.this, new Runnable() {
					@Override
					public void run() {
						try {
							if (TermuxPreferences.isShowWelcomeDialog(TermuxActivity.this)) {
								new AlertDialog.Builder(TermuxActivity.this).setTitle(R.string.welcome_dialog_title).setMessage(R.string.welcome_dialog_body)
										.setCancelable(false).setPositiveButton(android.R.string.ok, null)
										.setNegativeButton(R.string.welcome_dialog_dont_show_again_button, new DialogInterface.OnClickListener() {
											@Override
											public void onClick(DialogInterface dialog, int which) {
												TermuxPreferences.disableWelcomeDialog(TermuxActivity.this);
												dialog.dismiss();
											}
										}).show();
							}
							addNewSession(false, null);
						} catch (WindowManager.BadTokenException e) {
							// Activity finished - ignore.
						}
					}
				});
			} else {
				// The service connected while not in foreground - just bail out.
				finish();
			}
		} else {
			switchToSession(getStoredCurrentSessionOrLast());
		}
	}

	@SuppressLint("InflateParams")
	void renameSession(final TerminalSession sessionToRename) {
		DialogUtils.textInput(this, R.string.session_rename_title, R.string.session_rename_positive_button, sessionToRename.mSessionName,
				new DialogUtils.TextSetListener() {
					@Override
					public void onTextSet(String text) {
						sessionToRename.mSessionName = text;
					}
				});
	}

	@Override
	public void onServiceDisconnected(ComponentName name) {
		if (mTermService != null) {
			// Respect being stopped from the TermuxService notification action.
			finish();
		}
	}

	@Nullable TerminalSession getCurrentTermSession() {
		return mTerminalView.getCurrentSession();
	}

	@Override
	public void onStart() {
		super.onStart();
		mIsVisible = true;

		if (mTermService != null) {
			// The service has connected, but data may have changed since we were last in the foreground.
			switchToSession(getStoredCurrentSessionOrLast());
			mListViewAdapter.notifyDataSetChanged();
		}

		registerReceiver(mBroadcastReceiever, new IntentFilter(RELOAD_STYLE_ACTION));
	}

	@Override
	protected void onStop() {
		super.onStop();
		mIsVisible = false;
		TerminalSession currentSession = getCurrentTermSession();
		if (currentSession != null) TermuxPreferences.storeCurrentSession(this, currentSession);
		unregisterReceiver(mBroadcastReceiever);
		getDrawer().closeDrawers();
	}

	@Override
	public void onBackPressed() {
		if (getDrawer().isDrawerOpen(Gravity.START))
			getDrawer().closeDrawers();
		else
			finish();
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		if (mTermService != null) {
			// Do not leave service with references to activity.
			mTermService.mSessionChangeCallback = null;
			mTermService = null;
		}
		unbindService(this);
	}

	DrawerLayout getDrawer() {
		return (DrawerLayout) findViewById(R.id.drawer_layout);
	}

	void addNewSession(boolean failSafe, String sessionName) {
		if (mTermService.getSessions().size() >= MAX_SESSIONS) {
			new AlertDialog.Builder(this).setTitle(R.string.max_terminals_reached_title).setMessage(R.string.max_terminals_reached_message)
					.setPositiveButton(android.R.string.ok, null).show();
		} else {
			String executablePath = (failSafe ? "/system/bin/sh" : null);
			TerminalSession newSession = mTermService.createTermSession(executablePath, null, null, failSafe);
			if (sessionName != null) {
				newSession.mSessionName = sessionName;
			}
			switchToSession(newSession);
			getDrawer().closeDrawers();
		}
	}

	/** Try switching to session and note about it, but do nothing if already displaying the session. */
	void switchToSession(TerminalSession session) {
		if (mTerminalView.attachSession(session)) noteSessionInfo();
	}

	String toToastTitle(TerminalSession session) {
		final int indexOfSession = mTermService.getSessions().indexOf(session);
		StringBuilder toastTitle = new StringBuilder("[" + (indexOfSession + 1) + "]");
		if (!TextUtils.isEmpty(session.mSessionName)) {
			toastTitle.append(" ").append(session.mSessionName);
		}
		String title = session.getTitle();
		if (!TextUtils.isEmpty(title)) {
			// Space to "[${NR}] or newline after session name:
			toastTitle.append(session.mSessionName == null ? " " : "\n");
			toastTitle.append(title);
		}
		return toastTitle.toString();
	}

	void noteSessionInfo() {
		if (!mIsVisible) return;
		TerminalSession session = getCurrentTermSession();
		final int indexOfSession = mTermService.getSessions().indexOf(session);
		showToast(toToastTitle(session), false);
		mListViewAdapter.notifyDataSetChanged();
		final ListView lv = ((ListView) findViewById(R.id.left_drawer_list));
		lv.setItemChecked(indexOfSession, true);
		lv.smoothScrollToPosition(indexOfSession);
	}

	@Override
	public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
		TerminalSession currentSession = getCurrentTermSession();
		if (currentSession == null) return;

		ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
		menu.add(Menu.NONE, CONTEXTMENU_PASTE_ID, Menu.NONE, R.string.paste_text).setEnabled(clipboard.hasPrimaryClip());
		menu.add(Menu.NONE, CONTEXTMENU_SELECT_ID, Menu.NONE, R.string.select);
		menu.add(Menu.NONE, CONTEXTMENU_RESET_TERMINAL_ID, Menu.NONE, R.string.reset_terminal);
		menu.add(Menu.NONE, CONTEXTMENU_KILL_PROCESS_ID, Menu.NONE, R.string.kill_process).setEnabled(currentSession.isRunning());
		menu.add(Menu.NONE, CONTEXTMENU_TOGGLE_FULLSCREEN_ID, Menu.NONE, R.string.toggle_fullscreen).setCheckable(true).setChecked(mSettings.isFullScreen());
		menu.add(Menu.NONE, CONTEXTMENU_STYLING_ID, Menu.NONE, R.string.style_terminal);
		menu.add(Menu.NONE, CONTEXTMENU_HELP_ID, Menu.NONE, R.string.help);
	}

	/** Hook system menu to show context menu instead. */
	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		mTerminalView.showContextMenu();
		return false;
	}

	static LinkedHashSet<CharSequence> extractUrls(String text) {
		// Pattern for recognizing a URL, based off RFC 3986
		// http://stackoverflow.com/questions/5713558/detect-and-extract-url-from-a-string
		final Pattern urlPattern = Pattern.compile(
				"(?:^|[\\W])((ht|f)tp(s?)://|www\\.)" + "(([\\w\\-]+\\.)+?([\\w\\-.~]+/?)*" + "[\\p{Alnum}.,%_=?&#\\-+()\\[\\]\\*$~@!:/{};']*)",
				Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL);
		LinkedHashSet<CharSequence> urlSet = new LinkedHashSet<>();
		Matcher matcher = urlPattern.matcher(text);
		while (matcher.find()) {
			int matchStart = matcher.start(1);
			int matchEnd = matcher.end();
			String url = text.substring(matchStart, matchEnd);
			urlSet.add(url);
		}
		return urlSet;
	}

	void showUrlSelection() {
		String text = getCurrentTermSession().getEmulator().getScreen().getTranscriptText();
		LinkedHashSet<CharSequence> urlSet = extractUrls(text);
		if (urlSet.isEmpty()) {
			new AlertDialog.Builder(this).setMessage(R.string.select_url_no_found).show();
			return;
		}

		final CharSequence[] urls = urlSet.toArray(new CharSequence[urlSet.size()]);
		Collections.reverse(Arrays.asList(urls)); // Latest first.

		// Click to copy url to clipboard:
		final AlertDialog dialog = new AlertDialog.Builder(TermuxActivity.this).setItems(urls, new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface di, int which) {
				String url = (String) urls[which];
				ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
				clipboard.setPrimaryClip(new ClipData(null, new String[] { "text/plain" }, new ClipData.Item(url)));
				Toast.makeText(TermuxActivity.this, R.string.select_url_copied_to_clipboard, Toast.LENGTH_LONG).show();
			}
		}).setTitle(R.string.select_url_dialog_title).create();

		// Long press to open URL:
		dialog.setOnShowListener(new OnShowListener() {
			@Override
			public void onShow(DialogInterface di) {
				ListView lv = dialog.getListView(); // this is a ListView with your "buds" in it
				lv.setOnItemLongClickListener(new OnItemLongClickListener() {
					@Override
					public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
						dialog.dismiss();
						String url = (String) urls[position];
						startActivity(Intent.createChooser(new Intent(Intent.ACTION_VIEW, Uri.parse(url)), null));
						return true;
					}
				});
			}
		});

		dialog.show();
	}

	@Override
	public boolean onContextItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case CONTEXTMENU_SELECT_ID:
			CharSequence[] items = new CharSequence[] { getString(R.string.select_text), getString(R.string.select_url),
					getString(R.string.select_all_and_share) };
			new AlertDialog.Builder(this).setItems(items, new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int which) {
					switch (which) {
					case 0:
						mTerminalView.toggleSelectingText();
						break;
					case 1:
						showUrlSelection();
						break;
					case 2:
						TerminalSession session = getCurrentTermSession();
						if (session != null) {
							Intent intent = new Intent(Intent.ACTION_SEND);
							intent.setType("text/plain");
							intent.putExtra(Intent.EXTRA_TEXT, session.getEmulator().getScreen().getTranscriptText().trim());
							intent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_transcript_title));
							startActivity(Intent.createChooser(intent, getString(R.string.share_transcript_chooser_title)));
						}
						break;
					}
					dialog.dismiss();
				}
			}).show();
			return true;
		case CONTEXTMENU_PASTE_ID:
			doPaste();
			return true;
		case CONTEXTMENU_KILL_PROCESS_ID:
			final AlertDialog.Builder b = new AlertDialog.Builder(this);
			b.setIcon(android.R.drawable.ic_dialog_alert);
			b.setMessage(R.string.confirm_kill_process);
			b.setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int id) {
					dialog.dismiss();
					getCurrentTermSession().finishIfRunning();
				}
			});
			b.setNegativeButton(android.R.string.no, null);
			b.show();
			return true;
		case CONTEXTMENU_RESET_TERMINAL_ID: {
			TerminalSession session = getCurrentTermSession();
			if (session != null) {
				session.reset();
				showToast(getResources().getString(R.string.reset_toast_notification), true);
			}
			return true;
		}
		case CONTEXTMENU_STYLING_ID: {
			Intent stylingIntent = new Intent();
			stylingIntent.setClassName("com.termux.styling", "com.termux.styling.TermuxStyleActivity");
			try {
				startActivity(stylingIntent);
			} catch (ActivityNotFoundException e) {
				new AlertDialog.Builder(this).setMessage(R.string.styling_not_installed)
						.setPositiveButton(R.string.styling_install, new android.content.DialogInterface.OnClickListener() {
							@Override
							public void onClick(DialogInterface dialog, int which) {
								startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("http://play.google.com/store/apps/details?id=com.termux.styling")));
							}
						}).setNegativeButton(android.R.string.cancel, null).show();
			}
		}
			return true;
		case CONTEXTMENU_TOGGLE_FULLSCREEN_ID:
			toggleImmersive();
			return true;
		case CONTEXTMENU_HELP_ID:
			startActivity(new Intent(this, TermuxHelpActivity.class));
			return true;
		default:
			return super.onContextItemSelected(item);
		}
	}

	void toggleImmersive() {
		boolean newValue = !mSettings.isFullScreen();
		mSettings.setFullScreen(this, newValue);
		mFullScreenHelper.setImmersive(newValue);
	}

	void changeFontSize(boolean increase) {
		mSettings.changeFontSize(this, increase);
		mTerminalView.setTextSize(mSettings.getFontSize());
	}

	void doPaste() {
		ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
		ClipData clipData = clipboard.getPrimaryClip();
		if (clipData == null) return;
		CharSequence paste = clipData.getItemAt(0).coerceToText(this);
		if (!TextUtils.isEmpty(paste)) getCurrentTermSession().getEmulator().paste(paste.toString());
	}

	/** The current session as stored or the last one if that does not exist. */
	public TerminalSession getStoredCurrentSessionOrLast() {
		TerminalSession stored = TermuxPreferences.getCurrentSession(this);
		if (stored != null) return stored;
		int numberOfSessions = mTermService.getSessions().size();
		if (numberOfSessions == 0) return null;
		return mTermService.getSessions().get(numberOfSessions - 1);
	}

	/** Show a toast and dismiss the last one if still visible. */
	void showToast(String text, boolean longDuration) {
		if (mLastToast != null) mLastToast.cancel();
		mLastToast = Toast.makeText(TermuxActivity.this, text, longDuration ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT);
		mLastToast.setGravity(Gravity.TOP, 0, 0);
		mLastToast.show();
	}

}
