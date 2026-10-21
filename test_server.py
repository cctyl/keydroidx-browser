import http.server
import socketserver
import time
import os

PORT = 8088
CHUNK_SIZE = 16 * 1024 # 16KB per chunk
DELAY = 0.1 # 100ms delay per chunk -> ~160KB/s speed limit

FILE_PATH = "test_download.bin"
if not os.path.exists(FILE_PATH):
    with open(FILE_PATH, "wb") as f:
        f.write(os.urandom(10 * 1024 * 1024)) # 10MB

HTML_PAGE = """<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>下载速度测试</title>
<style>
body { background: #0B162C; color: #fff; font-family: sans-serif; text-align: center; padding: 20px; }
h2 { font-size: 16px; margin-bottom: 20px; }
a.btn {
    display: inline-block; padding: 12px 20px; background: #2196F3; color: #fff;
    text-decoration: none; border-radius: 6px; font-size: 14px; font-weight: bold;
}
a.btn:focus, a.btn:hover { background: #FDE047; color: #000; }
</style>
</head>
<body>
<h2>10MB 限速下载测试</h2>
<p style="font-size:12px;color:#90CAF9;margin-bottom:20px;">限速约 160 KB/s，全过程约 60 秒</p>
<a class="btn" href="/test_download.bin">点击开始下载 (10MB)</a>
</body>
</html>
"""

class ThrottledHandler(http.server.SimpleHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/" or self.path == "/index.html":
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(HTML_PAGE.encode('utf-8'))))
            self.end_headers()
            self.wfile.write(HTML_PAGE.encode('utf-8'))
            return

        if self.path.startswith("/test_download.bin"):
            if not os.path.exists(FILE_PATH):
                with open(FILE_PATH, "wb") as f:
                    f.write(os.urandom(10 * 1024 * 1024))
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

class ReuseTCPServer(socketserver.TCPServer):
    allow_reuse_address = True

if __name__ == "__main__":
    print(f"Starting throttled server on port {PORT}...")
    with ReuseTCPServer(("0.0.0.0", PORT), ThrottledHandler) as httpd:
        httpd.serve_forever()
