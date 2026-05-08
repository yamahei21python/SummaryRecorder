#!/bin/bash
# E2Eテスト用: エミュレータのGroq API 403対策プロキシ
# エミュレータのNAT IPがCloudflareにブロックされる問題を回避

set -e

# 1. Node.js HTTPS CONNECT proxy起動
PID_FILE="/tmp/e2e_proxy.pid"
if [ -f "$PID_FILE" ] && kill -0 $(cat "$PID_FILE") 2>/dev/null; then
    echo "Proxy already running on PID $(cat $PID_FILE)"
else
    PROXY_SCRIPT="/tmp/proxy.mjs"
    cat > "$PROXY_SCRIPT" << 'NODESCRIPT'
import http from 'http';
import https from 'https';
import { URL } from 'url';
import net from 'net';

const server = http.createServer((req, res) => {
  const target = new URL(req.url);
  const options = {
    hostname: target.hostname,
    port: target.port || 80,
    path: target.pathname + target.search,
    method: req.method,
    headers: req.headers,
  };
  const proxyReq = http.request(options, (proxyRes) => {
    res.writeHead(proxyRes.statusCode, proxyRes.headers);
    proxyRes.pipe(res);
  });
  proxyReq.on('error', (e) => { res.writeHead(502); res.end(); });
  req.pipe(proxyReq);
});

server.on('connect', (req, clientSocket, head) => {
  const [host, port] = req.url.split(':');
  const targetPort = parseInt(port) || 443;
  const serverSocket = net.connect(targetPort, host, () => {
    clientSocket.write('HTTP/1.1 200 Connection Established\r\n\r\n');
    serverSocket.write(head);
    serverSocket.pipe(clientSocket);
    clientSocket.pipe(serverSocket);
  });
  serverSocket.on('error', () => clientSocket.end());
  clientSocket.on('error', () => serverSocket.end());
});

server.listen(8888, '0.0.0.0', () => {
  console.log('E2E proxy ready on :8888');
});
NODESCRIPT

    node "$PROXY_SCRIPT" > /dev/null 2>&1 &
    echo $! > "$PID_FILE"
    sleep 1
    echo "Proxy started on PID $(cat $PID_FILE)"
fi

# 2. エミュレータにプロキシ設定
adb shell "settings put global http_proxy 10.0.2.2:8888"
echo "Emulator proxy set: 10.0.2.2:8888"

# 3. 確認
CUR=$(adb shell "settings get global http_proxy" | tr -d '\r')
echo "Current: $CUR"
