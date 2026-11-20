"""Seeds the LOCAL Oyla stack (127.0.0.1:8083) with a test centre, people and device activation codes."""
import json
import secrets
import subprocess
import sys
import urllib.request

BASE = "http://127.0.0.1:8083"
ROOT = sys.argv[1] if len(sys.argv) > 1 else "."  # repo root
EMAIL = "owner@oyla.test"
CREDS = f"{ROOT}/deploy/.env.local-test"


def call(method, path, body=None, token=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(req, timeout=20) as resp:
        raw = resp.read()
        return json.loads(raw) if raw else None


password = None
try:
    for line in open(CREDS, encoding="utf-8"):
        if line.startswith("OWNER_PASSWORD="):
            password = line.split("=", 1)[1].strip()
except FileNotFoundError:
    pass

if password is None:
    password = secrets.token_urlsafe(16)
    cmd = ["docker", "compose", "-f", f"{ROOT}/deploy/docker-compose.yml", "--env-file", f"{ROOT}/deploy/.env",
           "exec", "-T", "oyla-server", "/app/bin/server", "create-center",
           "--center-name", "Тестовый центр", "--owner-first-name", "Тест", "--owner-email", EMAIL,
           "--timezone", "Asia/Almaty", "--password-stdin"]
    result = subprocess.run(cmd, input=password + "\n", text=True, capture_output=True, encoding="utf-8")
    print(result.stdout.strip(), result.stderr.strip())
    if result.returncode != 0:
        sys.exit("create-center failed")
    with open(CREDS, "w", encoding="utf-8") as f:
        f.write(f"# Local test stack only\nOWNER_EMAIL={EMAIL}\nOWNER_PASSWORD={password}\n")

auth = call("POST", "/api/v1/auth/login", {"email": EMAIL, "password": password})
token = auth["accessToken"]
if not auth.get("activeCenter"):
    center_id = auth["centers"][0]["centerId"] if "centerId" in auth["centers"][0] else auth["centers"][0]["center"]["id"]
    token = call("POST", f"/api/v1/centers/{center_id}/select", token=token)["accessToken"]

if not call("GET", "/api/v1/specialists", token=token):
    call("POST", "/api/v1/specialists", {"firstName": "Айгерим", "lastName": "Серикова", "specialization": "Логопед"}, token)
if not call("GET", "/api/v1/children", token=token):
    call("POST", "/api/v1/children", {"firstName": "Амир", "lastName": "Б."}, token)

spec = call("POST", "/api/v1/devices/activation-codes", {"deviceName": "Эмулятор специалиста", "deviceRole": "SPECIALIST"}, token)
child = call("POST", "/api/v1/devices/activation-codes", {"deviceName": "Планшет A16", "deviceRole": "CHILD"}, token)
print("SPECIALIST_CODE", spec["activationCode"])
print("CHILD_CODE", child["activationCode"])
