from http.server import BaseHTTPRequestHandler, HTTPServer
import sqlite3
import struct
import io
import os.path
import sys

# Configuration
DATA_PATH = "blocksave_worlds"
PORT = 7007

def initialize_database(path):
    """Create the database and table if they don't exist"""
    dir, name = os.path.split(path)
    os.makedirs(dir, exist_ok=True)
    if not os.path.exists(path):
        conn = sqlite3.connect(path)
        cursor = conn.cursor()
        cursor.execute('''
        CREATE TABLE IF NOT EXISTS blocks (
            pos INTEGER PRIMARY KEY,
            data BLOB,
            mtime INTEGER
        )
        ''')
        conn.commit()
        conn.close()
        print(f"Database initialized at {path}")

class BlocksHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        try:
            # Extract world name from the URL path
            world_name = self.path.strip('/').split('/')[-1]
            if not world_name:
                self.send_error(400, "World name not specified")
                return

            # Read the binary content
            content_length = int(self.headers['Content-Length'])
            binary_data = self.rfile.read(content_length)

            # Process the binary data
            blocks = self.parse_binary_data(binary_data)

            # Save blocks to the database
            self.save_blocks_to_db(world_name, blocks)

            # Send success response
            self.send_response(200)
            self.send_header('Content-Type', 'text/plain')
            self.end_headers()
            self.wfile.write(f"Received {len(blocks)} blocks for world '{world_name}'".encode())
            print(f"Successfully processed {len(blocks)} blocks for world '{world_name}'")

        except Exception as e:
            self.send_error(500, f"Internal server error: {str(e)}")
            print(f"Error: {str(e)}")

    def parse_binary_data(self, binary_data):
        """Parse the binary data format from the client"""
        blocks = []
        data_stream = io.BytesIO(binary_data)

        while data_stream.tell() < len(binary_data):
            try:
                # Read block position (long)
                pos = struct.unpack('>q', data_stream.read(8))[0]

                # Read modification time (long)
                mtime = struct.unpack('>q', data_stream.read(8))[0]

                # Read data length (long)
                data_length = struct.unpack('>q', data_stream.read(8))[0]

                # Read block data (byte array)
                data = data_stream.read(data_length)

                blocks.append({
                    'pos': pos,
                    'mtime': mtime,
                    'data': data
                })
            except struct.error as e:
                print(f"Error unpacking data: {e}")
                break

        return blocks

    def save_blocks_to_db(self, world_name, blocks):
        """Save or update blocks in the database"""
        # Use world-specific database
        db_path = f"{DATA_PATH}/{world_name}/map.sqlite"
        initialize_database(db_path)

        conn = sqlite3.connect(db_path)
        cursor = conn.cursor()


        # Insert or update blocks
        for block in blocks:
            cursor.execute('''
            INSERT OR REPLACE INTO blocks (pos, data, mtime)
            VALUES (?, ?, ?)
            ''', (block['pos'], block['data'], block['mtime']))

        conn.commit()
        conn.close()

def run_server():
    server = HTTPServer(('0.0.0.0', PORT), BlocksHandler)
    print(f"Server started on port {PORT}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("Server stopped by user")
        server.server_close()


if __name__ == "__main__":
    run_server()
