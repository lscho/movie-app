#!/usr/bin/env python3
"""局域网 SSDP 探测：复刻 App 内的发现算法，用于在电脑上先验证网络环境。

用途：当 App 搜不到设备时，先跑这个脚本确认是「网络问题」还是「App 问题」。
算法与 native-dlna/SsdpDiscovery.kt 一致：随机端口主 socket + 1900 次 socket、
三目标 M-SEARCH、两轮重发、广播兜底、NOTIFY 支持、按 UDN 去重、坏 LOCATION 回退。

用法:
    python3 tools/dlna-verify/lan-probe.py            # 自动选网卡
    python3 tools/dlna-verify/lan-probe.py 192.168.1.5  # 指定本机网卡 IP
"""
import socket, sys, time, urllib.request, re, subprocess

SSDP_ADDR, SSDP_PORT, MX = '239.255.255.250', 1900, '2'
TARGETS = ['urn:schemas-upnp-org:device:MediaRenderer:1', 'upnp:rootdevice', 'ssdp:all']


def detect_iface() -> str:
    """挑一个非回环 IPv4：优先 wlan/en0/eth"""
    try:
        out = subprocess.run(['ifconfig'], capture_output=True, text=True).stdout
    except Exception:
        return '127.0.0.1'
    best, cur = None, None
    for line in out.splitlines():
        if line and not line[0].isspace():
            cur = line.split(':')[0]
        elif 'inet ' in line and cur:
            ip = line.split('inet ')[1].split(' ')[0]
            if ip.startswith('127.'):
                continue
            prio = 0 if cur.startswith(('wlan', 'en0', 'eth')) else 1
            if best is None or prio < best[0]:
                best = (prio, ip)
    return best[1] if best else '127.0.0.1'


IFACE = sys.argv[1] if len(sys.argv) > 1 else detect_iface()

def mk_primary():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(('', 0))
    s.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_IF, socket.inet_aton(IFACE))
    s.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 4)
    s.settimeout(0.25)
    return s

def mk_secondary():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind(('', 1900))
        s.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP,
                     socket.inet_aton(SSDP_ADDR) + socket.inet_aton(IFACE))
        s.settimeout(0.25)
        return s
    except Exception as e:
        print(f"  [secondary 1900 绑定失败: {e}]")
        return None

def parse(text):
    first = text.split('\r\n')[0].strip()
    is_hit = first.lower().startswith('http/1.1 200') or first.lower().startswith('http/1.0 200')
    is_notify = first.lower().startswith('notify * http')
    if not (is_hit or is_notify): return None
    loc, usn, byebye = None, '', False
    for line in text.split('\r\n'):
        if ':' not in line: continue
        k, v = line.split(':', 1)
        k, v = k.strip().lower(), v.strip()
        if k == 'location': loc = v
        elif k == 'usn': usn = v
        elif k == 'nts' and v.lower() == 'ssdp:byebye': byebye = True
    if byebye or not loc: return None
    return loc, usn.split('::')[0].strip()

def collect(socks, window_ms, out):
    end = time.time() + window_ms/1000
    while time.time() < end:
        got = False
        for s in socks:
            if s is None: continue
            try:
                d, a = s.recvfrom(8192)
                got = True
                r = parse(d.decode('utf-8','ignore'))
                if r and r[0] not in out: out[r[0]] = r[1]
            except socket.timeout:
                pass
        if not got: time.sleep(0.02)

def fetch(url):
    try:
        req = urllib.request.Request(url, headers={'User-Agent':'Linux/3.0 UPnP/1.0 DLNADOC/1.50'})
        with urllib.request.urlopen(req, timeout=2.5) as r:
            return r.read().decode('utf-8','ignore') if r.status == 200 else None
    except Exception:
        return None

def describe(loc, fb):
    xml = fetch(loc)
    if not xml: return None
    if 'serviceType>urn:schemas-upnp-org:service:AVTransport' not in xml: return None
    n = re.search(r'<friendlyName>(.*?)</friendlyName>', xml, re.S)
    u = re.search(r'<UDN>(.*?)</UDN>', xml, re.S)
    return {'name': n.group(1).strip() if n else '未知设备',
            'udn': u.group(1).strip() if u else fb, 'location': loc}

print(f"=== 网卡: {IFACE} ===")
print("=== 阶段1：两轮发送 + 收听（模拟 App 内 6s 流程）===")
socks = [mk_primary(), mk_secondary()]
print(f"  primary=随机端口 OK, secondary(1900)={'OK' if socks[1] else '失败(可容忍)'}")
cand = {}
for rnd in range(2):
    for i, t in enumerate(TARGETS):
        msg = (f'M-SEARCH * HTTP/1.1\r\nHOST: {SSDP_ADDR}:{SSDP_PORT}\r\n'
               f'MAN: "ssdp:discover"\r\nMX: {MX}\r\nST: {t}\r\n'
               f'USER-AGENT: Android/10 UPnP/1.1 DLNADOC/1.50\r\n\r\n').encode()
        for s in socks:
            if s is None: continue
            try: s.sendto(msg, (SSDP_ADDR, SSDP_PORT))
            except Exception: pass
            try: s.sendto(msg, ('255.255.255.255', SSDP_PORT))
            except Exception: pass
        if i < len(TARGETS)-1: time.sleep(0.12)
    collect(socks, 1500, cand)
    print(f"  第 {rnd+1} 轮候选: {list(cand.keys())}")
for s in socks:
    if s: s.close()
print(f"  候选总数: {len(cand)}")

print("\n=== 阶段2：拉描述 + UDN 去重 ===")
by_udn = {}
for loc, usn in cand.items():
    d = describe(loc, usn)
    print(f"  {loc} -> " + (f"OK {d['name']}" if d else "跳过"))
    if d and d['udn'] not in by_udn: by_udn[d['udn']] = d

print(f"\n=== 最终: {len(by_udn)} 台设备 ===")
for d in by_udn.values():
    print(f"  ✔ {d['name']}  {d['location']}")
if len(by_udn) == 0:
    print("\n结果: 未发现设备")
    print("  排查方向：路由器 AP 隔离 / 访客网络 / 电视投屏开关未开 / 电脑与电视不同网段")
    sys.exit(1)
print("\n结果: 通过（该网络环境下 App 应能搜到同样的设备）")
