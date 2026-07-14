#!/bin/sh
# aidev-bridge: AIDev 桥接客户端 —— 经本机 TCP loopback 把请求帧推送给宿主 App 的 BridgeSocketServer。
# 失败自动回退文件 drop（兼容旧轮询通道）。
#
# 用法:
#   aidev-bridge send <bridge> '<payload>'   发送一帧（bridge: notify|build|deploy|shizuku|crash）
#   aidev-bridge status                       检查宿主桥接 socket 是否在线
#
# <payload> 格式由调用方决定：shizuku 为 KEY=VALUE 文本，其余为 JSON 文本。
set -u

PORT=14096
HOST=127.0.0.1
TIMEOUT=30

# 桥接 Socket 静态共享密钥：须与宿主 Constants.BRIDGE_SOCKET_TOKEN 一致，否则服务端丢弃请求帧。
TOKEN="aidev-bridge-2026"

send_via_tcp() {
  python3 - "$BRIDGE" "$ID" "$PAYLOAD" "$PORT" "$TOKEN" <<'PY'
import socket, sys, json, struct
bridge, mid, payload, port, token = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4]), sys.argv[5]
try:
    frame = json.dumps({"b": bridge, "i": mid, "p": payload, "a": token}).encode()
    s = socket.create_connection(("127.0.0.1", port), timeout=5)
    s.sendall(struct.pack(">I", len(frame)) + frame)
    hdr = b""
    while len(hdr) < 4:
        c = s.recv(4 - len(hdr))
        if not c:
            break
        hdr += c
    if len(hdr) == 4:
        n = struct.unpack(">I", hdr)[0]
        body = b""
        while len(body) < n:
            c = s.recv(n - len(body))
            if not c:
                break
            body += c
        out = json.loads(body.decode())
        p = out.get("p", "")
        if p:
            print(p)
    s.close()
    sys.exit(0)
except Exception as e:
    sys.stderr.write("tcp send failed: %s\n" % e)
    sys.exit(1)
PY
}

send_via_file() {
  case "$BRIDGE" in
    shizuku)
      REQ_DIR="/host-home/.aidev-shizuku-bridge/request"
      RES_DIR="/host-home/.aidev-shizuku-bridge/result"
      mkdir -p "$REQ_DIR" "$RES_DIR"
      REQ="$REQ_DIR/$ID"; RES="$RES_DIR/$ID"
      printf '%s\n' "$PAYLOAD" > "$REQ"
      ;;
    build|deploy|crash)
      D="/host-home/.aidev-${BRIDGE}-bridge"
      mkdir -p "$D"
      REQ="$D/req-${ID}.json"; RES="$D/result-${ID}.json"
      printf '%s\n' "$PAYLOAD" > "$REQ"
      ;;
    notify)
      D="/host-home/.aidev-notify"
      mkdir -p "$D"
      REQ="$D/${ID}.json"; RES=""
      printf '%s\n' "$PAYLOAD" > "$REQ"
      ;;
    *)
      echo "ERROR: 未知桥: $BRIDGE" >&2
      return 1
      ;;
  esac
  if [ -n "$RES" ]; then
    WAITED=0
    while [ ! -s "$RES" ] && [ "$WAITED" -lt "$TIMEOUT" ]; do
      sleep 1
      WAITED=$((WAITED + 1))
    done
    if [ -s "$RES" ]; then
      cat "$RES"
      rm -f "$REQ" "$RES"
      return 0
    fi
    echo "ERROR: 文件通道超时" >&2
    rm -f "$REQ" "$RES"
    return 1
  else
    sleep 1
    rm -f "$REQ"
    return 0
  fi
}

cmd="${1:-}"
shift 2>/dev/null || true

case "$cmd" in
  send)
    BRIDGE="${1:-}"
    shift 2>/dev/null || true
    PAYLOAD="${1:-}"
    [ -z "$BRIDGE" ] && { echo "用法: aidev-bridge send <bridge> '<payload>'"; exit 2; }
    ID="b_$(date +%s%N)_$$"
    if command -v python3 >/dev/null 2>&1; then
      if send_via_tcp; then exit 0; fi
      echo "aidev-bridge: socket 发送失败，回退文件通道" >&2
    fi
    # TCP 不可用 → 回退文件 drop
    send_via_file
    ;;
  status)
    if command -v python3 >/dev/null 2>&1; then
      python3 - "$PORT" <<'PY'
import socket, sys
try:
    s = socket.create_connection(("127.0.0.1", int(sys.argv[1])), timeout=2)
    s.close()
    print("bridge socket: ONLINE")
except Exception as e:
    print("bridge socket: OFFLINE (%s)" % e)
    sys.exit(1)
PY
    else
      echo "bridge socket: python3 不可用，无法探测（文件通道仍可用）"
      exit 0
    fi
    ;;
  *)
    echo "用法: aidev-bridge <send|status> ..."
    exit 2
    ;;
esac
