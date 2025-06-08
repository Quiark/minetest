from http.server import BaseHTTPRequestHandler, HTTPServer
import sqlite3
import struct
import io
import os.path
import sys
import subprocess
import shutil
import json
import urllib.parse
import mimetypes

# Configuration
DATA_PATH = "blocksave_worlds"
PORT = 7007
ANDROID_WORLDS_PATH = "/storage/emulated/0/Android/data/net.minetest.minetest/files/Minetest/worlds/"

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
    def do_GET(self):
        try:
            # Check if this is a request for static files
            if self.path.startswith('/files/'):
                self.serve_static_file()
            else:
                self.send_error(404, "Not found")
        except Exception as e:
            self.send_error(500, f"Internal server error: {str(e)}")
            print(f"Error: {str(e)}")

    def serve_static_file(self):
        """Serve static files from local folder or directory listings as JSON"""
        # Remove '/files/' prefix and decode URL
        rel_path = urllib.parse.unquote(self.path[7:])  # Remove '/files/'
        
        # Security check - prevent directory traversal
        if '..' in rel_path or rel_path.startswith('/'):
            self.send_error(403, "Forbidden")
            return
            
        # Construct full local path
        local_path = os.path.join(DATA_PATH, rel_path) if rel_path else DATA_PATH
        
        if not os.path.exists(local_path):
            self.send_error(404, "File not found")
            return
            
        if os.path.isdir(local_path):
            # Serve directory listing as JSON
            self.serve_directory_json(local_path)
        else:
            # Serve file content
            self.serve_file_content(local_path)
    
    def serve_directory_json(self, dir_path):
        """Serve directory contents as JSON array"""
        try:
            entries = []
            for item in os.listdir(dir_path):
                item_path = os.path.join(dir_path, item)
                stat = os.stat(item_path)
                
                entry = {
                    "name": item,
                    "is_dir": os.path.isdir(item_path),
                    "size": stat.st_size if not os.path.isdir(item_path) else -1,
                    "mtime": int(stat.st_mtime)
                }
                entries.append(entry)
            
            # Sort entries: directories first, then by name
            entries.sort(key=lambda x: (not x["is_dir"], x["name"]))
            
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.end_headers()
            self.wfile.write(json.dumps(entries).encode())
            
        except Exception as e:
            self.send_error(500, f"Error reading directory: {str(e)}")
    
    def serve_file_content(self, file_path):
        """Serve file content with appropriate MIME type"""
        try:
            # Guess MIME type
            mime_type, _ = mimetypes.guess_type(file_path)
            if mime_type is None:
                mime_type = 'application/octet-stream'
            
            with open(file_path, 'rb') as f:
                content = f.read()
            
            self.send_response(200)
            self.send_header('Content-Type', mime_type)
            self.send_header('Content-Length', str(len(content)))
            self.end_headers()
            self.wfile.write(content)
            
        except Exception as e:
            self.send_error(500, f"Error reading file: {str(e)}")

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


def copy_from_android(world):
    """Copy world files from Android device using ADB"""
    try:
        # Check if ADB is available
        subprocess.run(["adb", "version"], check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        
        
        # Create local directory if it doesn't exist
        os.makedirs(DATA_PATH, exist_ok=True)
        
        # Copy each world
        world_path = f"{ANDROID_WORLDS_PATH}{world}/"
        
        print(f"Copying world '{world}' from Android device...")
        
        # Create local world directory
        #os.makedirs(local_path, exist_ok=True)
        
        # Pull all files
        subprocess.run(
            ["adb", "pull", world_path, DATA_PATH],
            check=True
        )
        
        
    except subprocess.CalledProcessError as e:
        print(f"Error executing ADB command: {e}")
        print(f"Error output: {e.stderr}")
    except Exception as e:
        print(f"Error copying worlds: {e}")


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "copy":
        copy_from_android('kok')
    else:
        run_server()
