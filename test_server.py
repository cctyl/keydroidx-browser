import http.server
import socketserver
import time
import os

PORT = 8088
CHUNK_SIZE = 16 * 1024 # 16KB per chunk
DELAY = 0.1 # 100ms delay per chunk -> ~160KB/s speed limit

# Create a test file of 10MB if not exists
FILE_PATH = "test_download.bin"
if not os.path.exists(FILE_PATH):
    with open(FILE_PATH, "wb") as f:
        f.write(os.urandom(10 * 1024 * 1024)) # 10MB file

class ThrottledHandler(http.server.SimpleHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/test_download.bin":
            file_size = os.path.getsize(FILE_PATH)
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Disposition", 'attachment; filename="test_download.bin"')
            self.send_header("Content-Length", str(file_size))
            self.end_headers()
            with open(FILE_PATH, "rb") as f:
                while chunk := f.read(CHUNK_SIZE):
                    self.wfile.write(chunk)
                    time.sleep(DELAY)
            return
        super().do_GET()

print(f"Starting throttled server on port {PORT}...")
with socketserver.TCPServer(("0.0.0.0", PORT), ThrottledHandler) as httpd:
    httpd.serve_forever()
