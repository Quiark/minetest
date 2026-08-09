package net.minetest.minetest;


import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
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
			if (!MainActivity.SYNC_ENABLED) return;

			List<Block> blocks = queryNewBlocks();
			sendBlocks(blocks);

			// Schedule the next execution after 10 seconds
			if (isRunning) {
				handler.postDelayed(this, 2000);
			}
		}
	};

	public List<Block> queryNewBlocks() {
		Log.d("ReplicateTask", "Querying new blocks, db open? " + (mapDb != null));
		if (!acquireDb()) return new ArrayList<>();

		long[] lastCoord = mapDb.getDoneXYZ();
		long lastMtime = mapDb.getDoneMtime();
		List<Block> blocks = new ArrayList<>();
		SQLiteDatabase db = mapDb.getReadableDatabase();
		try (
			Cursor c = getNewBlocks(db, lastMtime, lastCoord)
		) {
			while (c.moveToNext()) {
				Block b = new Block();
				// x, y, z, data, mtime
				b.x = c.getLong(0);
				b.y = c.getLong(1);
				b.z = c.getLong(2);
				b.data = c.getBlob(3);
				b.mtime = c.getLong(4);
				blocks.add(b);
			}
		}

		// Log the query progress
		Log.d("ReplicateTask", "Queried new blocks: lastMtime=" + lastMtime + ", lastPos=" + ", count=" + blocks.size());

		return blocks;
	}

	private synchronized boolean acquireDb() {
		if (mapDb == null) {
			File dbFile = new File(worldPath + "/map.sqlite");
			// if the db file doesn't exist yet, wait
			if (!dbFile.exists()) return false;

			mapDb = new MapDb(context, dbFile.getAbsolutePath());
		}
		return true;
	}

	private Cursor getNewBlocks(SQLiteDatabase db, long lastMtime, long[] lastCoord) {
			assert(lastCoord != null && lastCoord.length == 3);
			long lx =  lastCoord[0];
			long ly =  lastCoord[1];
			long lz =  lastCoord[2];

			// New-format: lexicographic ordering by (mtime, x, y, z)
			// Start strictly after (lastMtime, lastCoord)
			String sql =
				"SELECT x, y, z, data, mtime " +
				"FROM blocks " +
				"WHERE (mtime > ?1) OR " +
				"      (mtime = ?1 AND (" +
				"          (x > ?2) OR " +
				"          (x = ?2 AND (y > ?3 OR (y = ?3 AND z > ?4)))" +
				"      )) " +
				"ORDER BY mtime ASC, x ASC, y ASC, z ASC " +
				"LIMIT 100;";
			return db.rawQuery(sql, new String[] {
				String.valueOf(lastMtime),
				String.valueOf(lx),
				String.valueOf(ly),
				String.valueOf(lz)
			});
	}

	String url = HOST + "/map/";

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
				dataStream.writeLong(1); // Write format flag
				dataStream.writeLong(block.x); // Write x coordinate
				dataStream.writeLong(block.y); // Write y coordinate
				dataStream.writeLong(block.z); // Write z coordinate
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
					"first [mtime=" + first.mtime + "], " +
					"last [mtime=" + last.mtime + "]");

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

		// New format: pick max by (mtime, x, y, z)
		Block maxBlock = null;
		for (Block b : blocks) {
			if (maxBlock == null ||
				(b.mtime > maxBlock.mtime) ||
				(b.mtime == maxBlock.mtime && (
					b.x > maxBlock.x ||
					(b.x == maxBlock.x && (
						b.y > maxBlock.y ||
						(b.y == maxBlock.y && b.z > maxBlock.z)
					))
				))
			) {
				maxBlock = b;
			}
		}
		if (maxBlock != null) {
			Log.d("ReplicateTask", "Storing progress (new): mtime=" + maxBlock.mtime +
					", x=" + maxBlock.x + ", y=" + maxBlock.y + ", z=" + maxBlock.z);
			// Persist progress for new-format worlds
			mapDb.updateDoneXYZ(maxBlock.mtime, maxBlock.x, maxBlock.y, maxBlock.z);
		}

	}

	// Define a placeholder Block class (replace with your actual implementation)
	public static class Block {
		public byte[] data;
		public long mtime;
		public long x;
		public long y;
		public long z;
	}

}
