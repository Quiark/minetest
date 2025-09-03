#!/usr/bin/env python3
import argparse
import os
import sqlite3
import sys
from typing import Tuple

def die(msg: str, code: int = 1):
    print(f"Error: {msg}", file=sys.stderr)
    sys.exit(code)

def get_create_sql(cur: sqlite3.Cursor, table: str) -> str:
    cur.execute("SELECT sql FROM src.sqlite_master WHERE type='table' AND name=?;", (table,))
    row = cur.fetchone()
    if not row or not row[0]:
        die(f"Table '{table}' not found (or has no CREATE SQL) in source database.")
    return row[0]

def table_exists(cur: sqlite3.Cursor, schema: str, table: str) -> bool:
    cur.execute(f"SELECT 1 FROM {schema}.sqlite_master WHERE type='table' AND name=?;", (table,))
    return cur.fetchone() is not None

def ensure_columns(cur: sqlite3.Cursor, schema: str, table: str, required: Tuple[str, ...]):
    # PRAGMA schema.table_info('table')
    tname_escaped = table.replace("'", "''")
    cur.execute(f"PRAGMA {schema}.table_info('{tname_escaped}');")
    cols = {row[1] for row in cur.fetchall()}  # row[1] = name
    missing = [c for c in required if c not in cols]
    if missing:
        die(f"Table '{table}' in {schema} is missing required columns: {', '.join(missing)}")

def main():
    parser = argparse.ArgumentParser(description="Copy table 'blocks' (pos, mtime, data) from one SQLite DB to a new DB, adding an offset to pos.")
    parser.add_argument("source_db", help="Path to source SQLite database")
    parser.add_argument("dest_db", help="Path to destination SQLite database (created if missing)")
    parser.add_argument("--table", default="blocks", help="Table name to copy (default: blocks)")
    parser.add_argument("--offset", type=int, default=101, help="Amount to add to 'pos' (default: 101)")
    parser.add_argument("--overwrite", action="store_true", help="Drop destination table if it already exists")
    args = parser.parse_args()

    src_path = os.path.abspath(args.source_db)
    dst_path = os.path.abspath(args.dest_db)
    table = args.table

    if not os.path.exists(src_path):
        die(f"Source DB not found: {src_path}")

    # Create/connect destination (creates file if not present)
    try:
        con = sqlite3.connect(dst_path)
        con.isolation_level = None  # we'll manage transactions manually
        cur = con.cursor()
        cur.execute("PRAGMA foreign_keys=OFF;")
        cur.execute("PRAGMA synchronous=NORMAL;")
        cur.execute("PRAGMA journal_mode=WAL;")

        # Attach source as 'src'
        cur.execute("ATTACH DATABASE ? AS src;", (src_path,))

        # Validate source table and columns
        if not table_exists(cur, "src", table):
            die(f"Source table '{table}' does not exist.")
        ensure_columns(cur, "src", table, ("pos", "mtime", "data"))

        # Handle destination table existence
        if table_exists(cur, "main", table):
            if args.overwrite:
                cur.execute("BEGIN;")
                try:
                    cur.execute(f'DROP TABLE "{table}";')
                    con.commit()
                except Exception:
                    con.rollback()
                    raise
            else:
                die(f"Destination already has table '{table}'. Use --overwrite to drop it.")

        # Recreate table in destination using the source's CREATE TABLE SQL
        create_sql = 'CREATE TABLE IF NOT EXISTS main.blocks (x INTEGER, y INTEGER, z INTEGER, data BLOB, mtime INTEGER, PRIMARY KEY (x, y, z));'
        # Wrap in a transaction for speed and atomicity
        cur.execute("BEGIN;")
        try:
            cur.execute(create_sql)  # creates main.table with same schema
            
            # Process rows in batches of 256
            batch_size = 256
            offset = 0
            total_copied = 0
            
            while True:
                # Fetch a batch of rows
                cur.execute(f'SELECT pos, mtime, data FROM src."{table}" ORDER BY pos DESC LIMIT ? OFFSET ?;', (batch_size, offset))
                batch = cur.fetchall()
                
                if not batch:
                    break  # No more rows
                
                # Process each row in the batch
                batch_data = []
                for pos, mtime, data in batch:
                    # For the new schema, we need to convert pos to x, y, z coordinates
                    # This assumes pos is encoded as a single integer that needs to be decoded
                    # You may need to adjust this based on your actual pos encoding
                    x = new_pos  # Placeholder - adjust based on actual coordinate system
                    y = 0        # Placeholder - adjust based on actual coordinate system  
                    z = 0        # Placeholder - adjust based on actual coordinate system
                    batch_data.append((x, y, z, mtime, data))
                
                # Insert the batch
                cur.executemany(
                    f'INSERT INTO main."{table}" (x, y, z, mtime, data) VALUES (?, ?, ?, ?, ?);',
                    batch_data
                )
                
                total_copied += len(batch)
                offset += batch_size
                
                # Optional: print progress for large datasets
                if total_copied % 10000 == 0:
                    print(f"Processed {total_copied} rows...")
            
            con.commit()
        except Exception as e:
            con.rollback()
            raise

        # Report counts
        cur.execute(f'SELECT COUNT(*) FROM src."{table}";')
        src_count = cur.fetchone()[0]
        cur.execute(f'SELECT COUNT(*) FROM main."{table}";')
        dst_count = cur.fetchone()[0]
        print(f"Copied {dst_count} rows into '{table}' (source had {src_count}). pos offset = {args.offset}")
        print(f"Total rows processed: {total_copied}")
        print(f"Destination DB: {dst_path}")

    except Exception as e:
        die(str(e))
    finally:
        try:
            cur.close()
        except Exception:
            pass
        try:
            con.close()
        except Exception:
            pass

if __name__ == "__main__":
    main()
