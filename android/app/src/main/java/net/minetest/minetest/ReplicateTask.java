package net.minetest.minetest;


import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class ReplicateTask {
	public static final String HOST = "http://10.0.2.2:7007";
	private final Context context;
	private Handler handler;
	private HandlerThread handlerThread;
	private boolean isRunning = false;
	private MapDb mapDb;
	private String worldPath;

	public ReplicateTask(Context context) {
		this.context = context;
	}

	public void start(String _worldPath) {
		if (isRunning) return;
		isRunning = true;
		worldPath = _worldPath;

		// Initialize and start the HandlerThread
		handlerThread = new HandlerThread("ReplicateTaskThread");
		handlerThread.start();
		handler = new Handler(handlerThread.getLooper());

		handler.post(runnableTask);
	}

	public void stop() {
		isRunning = false;
		mapDb = null;
		if (handler != null) handler.removeCallbacks(runnableTask);
		if (handlerThread != null) {
			handlerThread.quitSafely();
			handlerThread = null;
			handler = null;
		}
	}

	private final Runnable runnableTask = new Runnable() {
		@Override
		public void run() {
			if (!isRunning) return;

			List<Block> blocks = queryNewBlocks();
			sendBlocks(blocks);

			// Schedule the next execution after 10 seconds
			if (isRunning) {
				handler.postDelayed(this, 2000);
			}
		}
	};

	public List<Block> queryNewBlocks() {
		if (mapDb == null) {
			mapDb = new MapDb(context, worldPath + "/map.sqlite");
		}

		long lastPos = mapDb.getDonePos();
		long lastMtime = mapDb.getDoneMtime();
		List<Block> blocks = new ArrayList<>();
		SQLiteDatabase db = mapDb.getReadableDatabase();
		try (
			Cursor c = db.rawQuery("SELECT pos, data, mtime FROM blocks WHERE (mtime > ?1) OR ((mtime = ?1) AND (pos > ?2)) ORDER BY mtime ASC, pos ASC LIMIT 100;",
			new String[]{String.valueOf(lastMtime), String.valueOf(lastPos)})
		) {
			while (c.moveToNext()) {
				Block b = new Block();
				b.pos = c.getLong(0);
				b.data = c.getBlob(1);
				b.mtime = c.getLong(2);
				blocks.add(b);
			}
		}

		// Log the query progress
		Log.d("ReplicateTask", "Queried new blocks: lastMtime=" + lastMtime + ", lastPos=" + lastPos + ", count=" + blocks.size());

		return blocks;
	}

	String url = "http://10.0.2.2:7007/map/";
	//String url = "http://10.0.8.73:7007/map/";

	public void sendBlocks(List<Block> blocks) {
		if (blocks == null || blocks.isEmpty()) {
			Log.d("ReplicateTask", "No blocks to send.");
			return;
		}

		try {
			// Pack the blocks into a binary format
			ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
			DataOutputStream dataStream = new DataOutputStream(byteStream);

			for (Block block : blocks) {
				dataStream.writeLong(block.pos); // Write block position
				dataStream.writeLong(block.mtime); // Write block modification time
				dataStream.writeLong(block.data.length); // Write block data length
				dataStream.write(block.data); // Write block data
			}

			dataStream.flush();
			byte[] payload = byteStream.toByteArray();

			String[] name = worldPath.split("/");
			HttpURLConnection connection = (HttpURLConnection) new URL(url + name[name.length - 1]).openConnection();

			// Log sending progress: number of blocks, first/last mtime/pos
			Block first = blocks.get(0);
			Block last = blocks.get(blocks.size() - 1);
			Log.i("ReplicateTask", "Sending " + blocks.size() + " blocks: " +
					"first [mtime=" + first.mtime + ", pos=" + first.pos + "], " +
					"last [mtime=" + last.mtime + ", pos=" + last.pos + "]");

			// Send the binary data over HTTP
			connection.setDoOutput(true);
			connection.setRequestMethod("POST");
			connection.setRequestProperty("Content-Type", "application/octet-stream");

			try (OutputStream outputStream = connection.getOutputStream()) {
				outputStream.write(payload);
			}

			int responseCode = connection.getResponseCode();
			connection.disconnect();
			if (responseCode != HttpURLConnection.HTTP_OK) {
				Log.e("ReplicateTask", "Failed to send blocks: HTTP " + responseCode);
				throw new RuntimeException("Error sending blocks");
			} else {
				Log.i("ReplicateTask", "Blocks sent successfully.");
			}

			storeProgress(blocks);

		} catch (Exception e) {
			Log.e("ReplicateTask", "Error sending blocks", e);
		}
	}

	private void storeProgress(List<Block> blocks) {
		if (blocks == null || blocks.isEmpty()) return;

		// Find the block with the largest pos and mtime (lexicographic comparison)
		Block maxBlock = null;
		for (Block block : blocks) {
			if (maxBlock == null ||
			   (block.mtime > maxBlock.mtime || (block.mtime == maxBlock.mtime && block.pos > maxBlock.pos))) {
				maxBlock = block;
			}
		}

		if (maxBlock != null) {
			Log.d("ReplicateTask", "Storing progress: mtime=" + maxBlock.mtime + ", pos=" + maxBlock.pos);
			mapDb.updateDoneSeq(maxBlock.mtime, maxBlock.pos);
		}
	}

	// Define a placeholder Block class (replace with your actual implementation)
	public static class Block {
		public long pos;
		public byte[] data;
		public long mtime;
	}

}
