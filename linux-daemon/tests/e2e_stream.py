# Dev-machine check of the live game stream's signalling against the real daemon and the real stream program (Python never ships).
# Build first: cargo build. Run: LINKD=target/debug/zohara-linkd STREAM=target/debug/zohara-linkd-stream python3 tests/e2e_stream.py
# Uses a test picture (ZOHARA_LINK_STREAM_TEST), so no screen or portal is needed. Where GStreamer's WebRTC part is missing the
# phone must get a clear STREAM_ERROR (EXPECT=missing); where it is installed it must get a real offer (EXPECT=ok, the default).
import socket, ssl, json, os, time, subprocess
W='/tmp/zohara-link-e2e-stream'; H=W+'/home'; S=W+'/z.sock'
subprocess.run(['rm','-rf',W]); os.makedirs(H)
BIN=os.environ['LINKD']; STREAM=os.environ['STREAM']; EXPECT=os.environ.get('EXPECT','ok')
d=subprocess.Popen([BIN,'--port','42996'],env=dict(os.environ,HOME=H,ZOHARA_LINK_SOCKET=S,ZOHARA_LINK_STREAM_BIN=STREAM,ZOHARA_LINK_STREAM_TEST='1'),stderr=open(W+'/d.log','w'))
for _ in range(50):
    if os.path.exists(S): break
    time.sleep(.1)
ctx=ssl.create_default_context(); ctx.check_hostname=False; ctx.verify_mode=ssl.CERT_NONE
def conn():
    s=ctx.wrap_socket(socket.create_connection(('127.0.0.1',42996),5)); s.settimeout(15); return s,s.makefile('rw')
def send(f,o): f.write(json.dumps(o)+'\n'); f.flush()
def rd(f): return json.loads(f.readline())
ok=True
def check(n,c):
    global ok; print(('PASS ' if c else 'FAIL ')+n); ok&=bool(c)
u=socket.socket(socket.AF_UNIX); u.connect(S); u.settimeout(10); uf=u.makefile('rw')
def call(obj):
    send(uf,obj)
    while True:
        l=json.loads(uf.readline())
        if 'event' not in l: return l
def next_event(name):
    while True:
        l=json.loads(uf.readline())
        if l.get('event')==name: return l['data']
def until(f,types):
    while True:
        p=rd(f)
        if p.get('type') in types: return p
def stream_procs():
    out=subprocess.run(['pgrep','-x','zohara-linkd-st'],capture_output=True,text=True).stdout.split()  # the process name is cut to 15 characters
    return out
try:
    call({'command':'OPEN_PAIRING','seconds':120})
    s,f=conn(); send(f,{'type':'PAIR_REQUEST','deviceId':'p1','deviceName':'Phone'}); rd(f)
    pin=next_event('PAIR_REQUEST')['pin']
    send(f,{'type':'PAIR_VERIFY','deviceId':'p1','deviceName':'Phone','pinSas':pin})
    until(f,['PAIR_CONFIRMED']); until(f,['ADDRESSES'])
    send(f,{'type':'STREAM_START','width':640,'height':360,'fps':30,'bitrate':1500})
    check('stream refused without the screen permission',until(f,['ERROR'])['error'].startswith('viewing the screen'))
    check('allow screen',call({'command':'SET_PERMISSION','deviceId':'p1','screen':True})['status']=='OK')
    send(f,{'type':'STREAM_START','width':640,'height':360,'fps':30,'bitrate':1500})
    m=until(f,['STREAM_OFFER','STREAM_ERROR'])
    if EXPECT=='ok':
        check('the phone gets an offer with video and a data channel',m['type']=='STREAM_OFFER' and 'm=video' in m['sdp'] and 'm=application' in m['sdp'] and 'H264' in m['sdp'])
        got_ice=False; end=time.time()+5
        while time.time()<end and not got_ice:
            f.flush(); s.settimeout(1)
            try: p=rd(f)
            except Exception: continue
            if p.get('type')=='STREAM_ICE': got_ice=True
        s.settimeout(15)
        check('candidates follow the offer',got_ice)
        check('the stream program is running',len(stream_procs())>=1)
        send(f,{'type':'STREAM_STOP'}); time.sleep(1)
        check('stopping the stream ends the program',len(stream_procs())==0)
        send(f,{'type':'STREAM_START','width':640,'height':360,'fps':30,'bitrate':1500}); until(f,['STREAM_OFFER'])
        s.shutdown(socket.SHUT_RDWR); s.close(); time.sleep(3)
        check('a phone that vanished takes its stream with it',len(stream_procs())==0)
    else:
        check('missing GStreamer gives a clear error, not a hang',m['type']=='STREAM_ERROR' and 'not installed' in m['error'])
        s.close()
    check('daemon alive',d.poll() is None)
    call({'command':'UNPAIR','deviceId':'p1'})
finally:
    d.terminate(); subprocess.run(['pkill','-x','zohara-linkd-st'])
print('ALL PASS' if ok else 'SOME FAILED'); raise SystemExit(0 if ok else 1)
