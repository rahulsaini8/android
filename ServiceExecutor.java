package com.example.onlypodcastserviceexecutor;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.app.Service;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.media.MediaPlayer.OnErrorListener;
import android.media.MediaPlayer.OnPreparedListener;
import android.os.AsyncTask;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

public class ServiceExecutor extends Service implements OnCompletionListener,
		OnErrorListener, OnPreparedListener {
	private String TAG = "PodcastService";
	private ExecutorService executor;
	private final String FILENAME = "temp.dat";
	private String URL, length;
	private File storageFile;
	private boolean fileCreated, isBufferingAvailable, isPaused;
	private Integer mentionedLength, totalFileDuration, totalFileSize, bufferedFileSize;
	private MediaPlayer mediaPlayer;
	public Thread playStreamThread, handlerBufferingThread, fetchDurationThread,
			saveStreamToFileThread;
	private int pausedAtMilliSec = 0;
	private String calledFrom = "XX"; // For debugging

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		Toast.makeText(this, "ServCalled", Toast.LENGTH_LONG).show();
		try {
			super.onStartCommand(intent, flags, startId);
			super.onCreate();

			/*
			 * Creating new ExecutorService with fixed number of threads = 5
			 */
			executor = Executors.newFixedThreadPool(5);
			URL = "http://podcastdownload.npr.org/anon.npr-podcasts/podcast/510299/175030727/npr_175030727.mp3";

			/*
			 * Harcoded time for initial use. Will be changed in 'fetchDurationRunnable' bellow when
			 * actual duration of stream will be fethced in background.
			 */
			mentionedLength = totalFileDuration = 600000;
			Toast.makeText(this, "URL: " + URL, Toast.LENGTH_LONG).show();

			/*
			 * Calling AsyncTask to start the process
			 */
			new AsyncCalled().execute();
		} catch (Exception e) {
			Log.e(TAG, "PodcastService: onStartCommand" + e.toString());
		}

		return START_STICKY;
	}

	@Override
	public IBinder onBind(Intent intent) {
		// TODO Auto-generated method stub
		return null;
	}

	private Runnable playStreamRunnable = new Runnable() {
		@Override
		public void run() {
			Log.e(TAG, "playStreamRunnable entered");
			playStream();
			Log.e(TAG, "playStreamRunnable completed");
		}
	};

	private Runnable saveStreamToFileRunnable = new Runnable() {
		@Override
		public void run() {
			Log.e(TAG, "saveStreamToFileRunnable entered");
			saveStreamToFile();
			Log.e(TAG, "saveStreamToFileRunnable completed");
		}
	};

	protected Runnable handlerBufferingRunnable = new Runnable() {
		@Override
		public void run() {
			Log.e(TAG, "handlerBufferingRunnable entered");
			handlerBuffering();
			Log.e(TAG, "handlerBufferingRunnable completed");
		}
	};

	private Runnable fetchDurationRunnable = new Runnable() {
		@Override
		public void run() {
			try {
				Log.e(TAG, "fetchDurationRunnable entered");
				final MediaPlayer tempMP = new MediaPlayer();
				tempMP.reset();
				int random = 5 + (int) (Math.random() * ((17 - 5) + 1));
				tempMP.setAudioSessionId(random);
				tempMP.setAudioStreamType(AudioManager.STREAM_NOTIFICATION);
				tempMP.setDataSource(URL);
				calledFrom = "fetchDurationRunnable";
				tempMP.setOnPreparedListener(new OnPreparedListener() {
					@Override
					public void onPrepared(MediaPlayer mp) {

						// SETTING UP STREAM DURATION IN A VARIABLE
						totalFileDuration = tempMP.getDuration();

						Log.e(TAG, "====>TOTAL FETCHED LENGHT: " + totalFileDuration
								+ " XML DURATION: " + length);
						Log.e(TAG, "====>totalFileDuration changed to: "
								+ totalFileDuration);
					}
				});
				tempMP.setOnErrorListener(new OnErrorListener() {
					@Override
					public boolean onError(MediaPlayer mp, int what, int extra) {
						Log.e(TAG, "mplayer Logged Error: What: " + what + " Extra: "
								+ extra);
						return false;
					}
				});

				tempMP.prepareAsync();
				// NOTE: WE ARE NOT PLAYING THE STREAM HERE. JUST FETCHING THE TIME. PLAYBACK IS
				// DONE IN SEPERATE THREAD
			} catch (Exception e) {
				Log.e(TAG, "fetchDurationRunnable: " + e.toString());
			}
			Log.e(TAG, "fetchDurationRunnable completed");
		}
	};

	public boolean setupTempFile() {
		try {
			storageFile = new File(this.getFilesDir(), FILENAME);
			if (storageFile.exists()) {
				storageFile.delete();
			}
			storageFile.createNewFile();
			if (storageFile.exists()) {
				return true;
			}
		} catch (Exception e) {
			Log.e(TAG, "setupTempFile: " + e.toString());
		}
		return false;
	}

	protected void saveStreamToFile() {
		try {
			URL url = new URL(URL);
			URLConnection conn = url.openConnection();
			conn.connect();
			totalFileSize = conn.getContentLength();

			InputStream is = conn.getInputStream();
			if (is == null) {
				Log.e(TAG + "downloadStreamInFile", "Unable to download feed " + URL);
			}

			FileOutputStream fos = this.openFileOutput(FILENAME, MODE_PRIVATE);
			byte[] buf = new byte[16384];

			do {
				int numRead = is.read(buf);
				if (numRead <= 0) {
					break;
				} else {
					fos.write(buf, 0, numRead);
					bufferedFileSize = (int) storageFile.length();
				}
			} while (bufferedFileSize < totalFileSize);

			fos.close();
			is.close();
		} catch (IOException e) {
			/*
			 * Problem fetching file from URL. Toast user to select different feed
			 */
			Log.e(TAG, "saveStreamToFile: " + e.toString());
		} catch (Exception e) {
			Log.e(TAG, "saveStreamToFile: " + e.toString());
		}
		Log.e(TAG, "saveStreamToFile:" + bufferedFileSize.toString());
	}

	protected void playStream() {
		try {
			if (isBufferingAvailable) {
				/*
				 * isBufferingPossible means if the temp storage file is created and we can play
				 * stream from there.
				 */

				/*
				 * Waiting while we have enough data in file to play the stream (for say 5000
				 * bytes).
				 * 
				 * ****PLEASE NOTE: I'M USING SLEEP METHOD BELOW ON ExecutorService. WILL THAT CAUSE
				 * ISSUE IN LONG RUN ?? ****
				 */
				bufferedFileSize = (int) storageFile.length();
				if (bufferedFileSize < 5000) {
					playStreamThread.sleep(2000);
					playStream();
				} else {
					// Setting up MediaPlayer
					if (mediaPlayer == null) {
						mediaPlayer = new MediaPlayer();
						mediaPlayer.setOnErrorListener(new OnErrorListener() {
							@Override
							public boolean onError(MediaPlayer mp, int what, int extra) {
								Log.e(TAG, "mediaPlayer Logged Error: What: " + what
										+ " Extra: " + extra);
								return false;
							}
						});
					}
					mediaPlayer.reset();
					FileInputStream fis = new FileInputStream(storageFile);
					mediaPlayer.setDataSource(fis.getFD());
					mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
					mediaPlayer.setOnCompletionListener(this);
					mediaPlayer.setOnErrorListener(this);
					calledFrom = "playStream"; // FOR DEBUGGING
					mediaPlayer.setOnPreparedListener(this);
					mediaPlayer.prepareAsync(); // When mediaPlayer is prepared in onPrepared(), it
												// will inturn call buffering thread.
				}
			} else {
				/*
				 * TODO
				 */
			}
		} catch (IOException e) {
			Log.e(TAG, "playStream: " + e.toString());
		} catch (Exception e) {
			Log.e(TAG, "playStream: " + e.toString());
		}
	}

	protected void handlerBuffering() {
		/*
		 * By buffering I mean, recursively checking if the file has enough data to continuously
		 * play the stream.
		 * 
		 * In every call it checks if the temp file has less than 2 sec stream remaining & the file
		 * is still downloading then pause the media player for 5 seconds.
		 * 
		 * It maintains a variable 'pausedAtMilliSec' which records where the stream is paused so
		 * that if the player also abruptly stops, it can be resumed from there.
		 * 
		 * Also 'WAIT_FOR' variable will maintain the sleeping time of this thread
		 */
		int choice = 0; // FOR DEBUGGING
		try {
			int WAIT_FOR = 1000; // DEFAULT
			if (mediaPlayer != null
					&& mediaPlayer.getCurrentPosition() < totalFileDuration) {

				/*
				 * Case I : Checking if mediaPlayer is about to cover all of the contents of
				 * buffered file (< 2 sec), then pause it to avoid playing last bit of content. If
				 * it plays all of the content the mediaplayer will be in stop status
				 */
				Double i = (double) (mediaPlayer.getDuration() - mediaPlayer
						.getCurrentPosition());
				i = i / 1000;
				Log.e(TAG, "mediaPlayer.getDuration()" + mediaPlayer.getDuration());
				Log.e(TAG, ".....Checking: IS STREAM LEFT < 2sec:" + i.toString());

				if (mediaPlayer.isPlaying()
						&& (bufferedFileSize < totalFileSize)
						&& (mediaPlayer.getDuration() - mediaPlayer.getCurrentPosition()) <= 2000) {

					choice = 1; // FOR DEBUGGING
					pausedAtMilliSec = mediaPlayer.getCurrentPosition();
					Log.e(TAG, "==> Choice 1, MP will be paused. Paused@:"
							+ pausedAtMilliSec);
					mediaPlayer.pause();
					isPaused = true;
					WAIT_FOR = 5000; // SLEEP FOR 5 SEC

				} else if (isPaused && !mediaPlayer.isPlaying()) {

					/*
					 * Case II : Media player is paused from above condition. Now needs to resume.
					 */
					Log.e(TAG, "==> Choice 2, MP will be resumed. Resumed @: "
							+ pausedAtMilliSec);
					choice = 2; // FOR DEBUGGING

					mediaPlayer.start();
					mediaPlayer.seekTo(pausedAtMilliSec);
					isPaused = false;
					WAIT_FOR = 1000;
				} else if (!mediaPlayer.isPlaying()) {
					choice = 3; // FOR DEBUGGING
					/*
					 * Case III : Media player is simply not playing because of some other reason.
					 * Happens for many reasons - can't figure out every.
					 */
					pausedAtMilliSec = mediaPlayer.getCurrentPosition();
					Log.e(TAG, "==> Choice 3, MP stooped abruptly. Resumed @: "
							+ pausedAtMilliSec);
					/*
					 * Resetting mediaplayer here because there may a chance that media player
					 * stopped & still there is stream to play, so it went to onCompletion()
					 * 
					 * Can resetting many times cause any issue?
					 */
					mediaPlayer.reset();
					FileInputStream fis = new FileInputStream(storageFile);
					mediaPlayer.setDataSource(fis.getFD());
					mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
					mediaPlayer.setOnCompletionListener(this);
					mediaPlayer.setOnErrorListener(this);
					calledFrom = "handlerBuffering choce 3"; // FOR DEBUGGING
					mediaPlayer.setOnPreparedListener(this);
					mediaPlayer.prepareAsync();
					WAIT_FOR = 1000;
				}
				handlerBufferingThread.sleep(WAIT_FOR);
				handlerBuffering(); // Recursion call
			}
		} catch (Exception e) {
			Log.e(TAG, "Choice : " + choice);
			Log.e(TAG, "handlerBuffering: " + e.toString());
		}
	}

	@Override
	public void onCompletion(MediaPlayer arg0) {
		try {
			Log.e(TAG, "onCompletion Started");
			Log.e(TAG, "onCompletion : mediaPlayer TotalDuration: " + totalFileDuration);
			Log.e(TAG,
					"onCompletion : mediaPlayer CurrentPosition: "
							+ mediaPlayer.getCurrentPosition());
			if (handlerBufferingThread != null
					&& mediaPlayer.getCurrentPosition() >= totalFileDuration) {
				handlerBufferingThread = null;
				mediaPlayer.reset();
			}
		} catch (Exception e) {
			Log.e(TAG, "onCompletion: Exception" + e.toString());
		}
	}

	@Override
	public boolean onError(MediaPlayer mp, int what, int extra) {
		try {
			Log.e(TAG, "Error occured in MediaPlayer: WHAT: " + what + "EXTRA:" + extra);
		} catch (Exception e) {
			Log.e(TAG, "MediaPlayer onError: Exception" + e.toString());
		}
		return false;
	}

	@Override
	public void onPrepared(MediaPlayer mp) {
		Log.e(TAG, "Called From: " + calledFrom);
		calledFrom = "deleted";
		// STARTING MEDIA PLAYER
		mediaPlayer.start();
		mediaPlayer.seekTo(pausedAtMilliSec);

		// STARTING THE THREAD FOR HANDLING BUFFERING
		if (handlerBufferingThread == null) {
			Log.e(TAG, "inside onPrepared starting handlerBufferingThread");
			handlerBufferingThread = new Thread(handlerBufferingRunnable);
			executor.execute(handlerBufferingThread);
			executor.shutdown();
		} else {
			// TODO
		}
	}

	class AsyncCalled extends AsyncTask {
		@Override
		protected Object doInBackground(Object... params) {
			try {
				Log.e(TAG, "doInBackground entered");
				fileCreated = setupTempFile(); // This function will create a temp file to store the
												// downloaded stream

				if (fileCreated) {
					isBufferingAvailable = true;
					/*
					 * Fetching actual stream duration in a seperate thread. Will be used while
					 * handling buffering
					 */
					fetchDurationThread = new Thread(fetchDurationRunnable);
					executor.execute(fetchDurationThread);

					/*
					 * Thread to download the stream into a file
					 */
					saveStreamToFileThread = new Thread(saveStreamToFileRunnable);
					executor.execute(saveStreamToFileThread);

				} else {

				}
				/*
				 * Starting the thread to play the feed simultaneously while downloading. This
				 * runnable will in turn initiate the thread for handle buffering.
				 */
				playStreamThread = new Thread(playStreamRunnable);
				executor.execute(playStreamThread);

			} catch (Exception e) {
				return e;
			}
			Log.e(TAG, "doInBackground exited");
			return null;
		}

		@Override
		protected void onPostExecute(Object result) {
			if (result instanceof Exception) {
				// TODO: Toast Exception
			}
			super.onPostExecute(result);
		}

	}

}
