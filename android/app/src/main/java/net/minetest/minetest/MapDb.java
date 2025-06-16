package net.minetest.minetest;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import androidx.annotation.Nullable;

public class MapDb extends SQLiteOpenHelper {
	public final String DONE_MTIME = "SyncDoneMtime";
	public final String DONE_POS = "SyncDonePos";

	public MapDb(@Nullable Context context, @Nullable String name) {
		// TODO: make sure this works on new databases
		super(context, name, null, 5);
		this.getWritableDatabase().close(); //upgrade
	}

	@Override
	public void onCreate(SQLiteDatabase db) {}

	@Override
	public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
		if (oldVersion < 5) {
			db.execSQL("alter table blocks add mtime integer default 0;");
			db.execSQL("create index blocks_mtime on blocks(mtime);");

			db.execSQL("CREATE TRIGGER update_blocks_mtime_insert after insert on blocks for each row " +
				"begin " +
				"update blocks set mtime = strftime('%s', 'now') where pos = new.pos;" +
				"end;");

				db.execSQL("CREATE TRIGGER update_blocks_mtime_update after update on blocks for each row " +
				"begin " +
				"update blocks set mtime = strftime('%s', 'now') where pos = old.pos;" +
				"end;");
		}
		if (oldVersion < 5) {
			db.execSQL("create table singles(name, seq);");
			ContentValues vals = new ContentValues();
			vals.put("name", DONE_MTIME);
			vals.put("seq", -1);
			db.insert("singles", null, vals);
			vals.put("name", DONE_POS);
			vals.put("seq", -1);
			db.insert("singles", null, vals);
		}
	}

	/**
	 * Updates the sqlite_sequence DONE_SEQ to a new value.
	 *
	 * @param newValue The new value to set for DONE_SEQ.
	 */
	public void updateDoneSeq(long newtime, long newpos) {
		SQLiteDatabase db = this.getWritableDatabase();
		ContentValues values = new ContentValues();
		values.put("seq", newtime);
		db.update("singles", values, "name = ?", new String[]{DONE_MTIME});
		values.put("seq", newpos);
		db.update("singles", values, "name = ?", new String[]{DONE_POS});
		db.close();
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
	 * Retrieves the value of DONE_POS from the sqlite_sequence table.
	 *
	 * @return The value of DONE_POS.
	 */
	public long getDonePos() {
		SQLiteDatabase db = this.getReadableDatabase();
		Cursor cseq = db.query("singles", new String[]{"seq"}, "name = ?", new String[]{DONE_POS}, null, null, null);
		long last = -1;
		if (cseq.moveToFirst()) {
			last = cseq.getInt(0);
		}
		cseq.close();
		db.close();
		return last;
	}
}
