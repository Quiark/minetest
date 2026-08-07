package net.minetest.minetest;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;
import androidx.annotation.Nullable;

public class MapDb extends SQLiteOpenHelper {
	public final String DONE_MTIME = "SyncDoneMtime";
	public final String DONE_POS = "SyncDonePos";
	// New singles keys for new-format tracking
	public final String DONE_X = "SyncDoneX";
	public final String DONE_Y = "SyncDoneY";
	public final String DONE_Z = "SyncDoneZ";
	public final boolean isNewFormat;

	public MapDb(@Nullable Context context, @Nullable String name) {
		super(context, name, null, 3);
		this.getWritableDatabase().close(); //upgrade
		this.isNewFormat = this.isNewBlocksFormat();
	}

	@Override
	public void onCreate(SQLiteDatabase db) {}

	@Override
	public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
		Log.i("MapDb", "Upgrading database from " + oldVersion + " to " + newVersion  );
		if (oldVersion < 3) {
			Log.i("MapDb", "adding mtime");
			String wherePredicate = "x = new.x and y = new.y and z = new.z;";

			db.execSQL("alter table blocks add mtime integer default 0;");
			db.execSQL("create index blocks_mtime on blocks(mtime);");

			db.execSQL("CREATE TRIGGER update_blocks_mtime_insert after insert on blocks for each row " +
				"begin " +
				"update blocks set mtime = strftime('%s', 'now') where " + wherePredicate +
				"end;");

				db.execSQL("CREATE TRIGGER update_blocks_mtime_update after update on blocks for each row " +
				"begin " +
				"update blocks set mtime = strftime('%s', 'now') where " + wherePredicate +
				"end;");
		}
		if (oldVersion < 3) {
			Log.i("MapDb", "Upgrading database adding singles table");
			db.execSQL("create table singles(name, seq);");
			ContentValues vals = new ContentValues();
			vals.put("name", DONE_MTIME);
			vals.put("seq", -1);
			db.insert("singles", null, vals);
			vals.put("name", DONE_POS);
			vals.put("seq", -1);
			db.insert("singles", null, vals);
			vals.put("name", DONE_X);
			vals.put("seq", -50000);
			db.insert("singles", null, vals);
			vals.put("name", DONE_Y);
			vals.put("seq", -50000);
			db.insert("singles", null, vals);
			vals.put("name", DONE_Z);
			vals.put("seq", -50000);
			db.insert("singles", null, vals);
		}
	}


	// Helper to upsert a value into 'singles' by name
	private void upsertSingle(SQLiteDatabase db, String name, long value) {
		ContentValues values = new ContentValues();
		values.put("seq", value);
		int updated = db.update("singles", values, "name = ?", new String[]{name});
		if (updated == 0) {
			values.put("name", name);
			db.insert("singles", null, values);
		}
	}

	/**
	 * Overload for new-format: store sync point as x,y,z instead of pos.
	 *
	 * @param newtime mtime watermark to store
	 * @param x block x
	 * @param y block y
	 * @param z block z
	 */
	public void updateDoneXYZ(long newtime, long x, long y, long z) {
		SQLiteDatabase db = this.getWritableDatabase();
		try {
			// update mtime
			upsertSingle(db, DONE_MTIME, newtime);
			// update x,y,z
			upsertSingle(db, DONE_X, x);
			upsertSingle(db, DONE_Y, y);
			upsertSingle(db, DONE_Z, z);
		} finally {
			db.close();
		}
	}

	/**
	 * Retrieves the value of DONE_SEQ from the sqlite_sequence table.
	 *
	 * @return The value of DONE_SEQ.
	 */
	public long getDoneMtime() {
		SQLiteDatabase db = this.getReadableDatabase();
		Cursor cseq = db.query("singles", new String[]{"seq"}, "name = ?", new String[]{DONE_MTIME}, null, null, null);
		long last = -1;
		if (cseq.moveToFirst()) {
			last = cseq.getInt(0);
		}
		cseq.close();
		db.close();
		return last;
	}

	/**
	 * Returns the last stored x, y, z (new-format) at once.
	 * Values default to -50000 if not present.
	 */
	public long[] getDoneXYZ() {
		SQLiteDatabase db = this.getReadableDatabase();
		long x = -50000, y = -50000, z = -50000;
		Cursor c = null;
		try {
			c = db.query(
				"singles",
				new String[]{"name", "seq"},
				"name IN (?,?,?)",
				new String[]{DONE_X, DONE_Y, DONE_Z},
				null, null, null
			);
			while (c.moveToNext()) {
				String name = c.getString(0);
				long val = c.getLong(1);
				if (DONE_X.equals(name)) x = val;
				else if (DONE_Y.equals(name)) y = val;
				else if (DONE_Z.equals(name)) z = val;
			}
		} finally {
			if (c != null) c.close();
			db.close();
		}
		return new long[]{x, y, z};
	}

	/**
	 * Checks whether the 'blocks' table uses the new format.
	 * Old format has a single 'pos' column.
	 * New format has separate 'x', 'y', 'z' columns.
	 *
	 * @return true if new format (x,y,z present and pos absent), false otherwise.
	 */
	private boolean isNewBlocksFormat() {
		SQLiteDatabase db = null;
		Cursor c = null;
		boolean hasPos = false, hasX = false, hasY = false, hasZ = false;
		try {
			db = this.getReadableDatabase();
			c = db.rawQuery("PRAGMA table_info(blocks)", null);
			final int nameIdx = c.getColumnIndex("name");
			while (c.moveToNext()) {
				String col = nameIdx >= 0 ? c.getString(nameIdx) : c.getString(1); // fallback to index 1
				if (col == null) continue;
				switch (col.toLowerCase()) {
					case "pos": hasPos = true; break;
					case "x": hasX = true; break;
					case "y": hasY = true; break;
					case "z": hasZ = true; break;
					default: break;
				}
			}
		} finally {
			if (c != null) c.close();
			if (db != null) db.close();
		}
		return hasX && hasY && hasZ && !hasPos;
	}
}
