# Dev-machine check of the real daemon over real TLS (Python is fine here: it never ships).
# Build first: cargo build --release. Run: python3 tests/e2e.py   (throwaway HOME and socket, port 42999)
import socket, ssl, json, os, time, subprocess, sys, stat, base64
W='/tmp/zohara-link-e2e'; H=W+'/home'; S=W+'/z.sock'
subprocess.run(['rm','-rf',W]); os.makedirs(H)
BIN=os.environ.get('LINKD', os.path.join(os.path.dirname(os.path.abspath(__file__)),'..','target','release','zohara-linkd'))
d=subprocess.Popen([BIN,'--port','42999'],env=dict(os.environ,HOME=H,ZOHARA_LINK_SOCKET=S),stderr=open(W+'/d.log','w'))
for _ in range(50):
    if os.path.exists(S): break
    time.sleep(.1)
ctx=ssl.create_default_context(); ctx.check_hostname=False; ctx.verify_mode=ssl.CERT_NONE
def conn():
    s=ctx.wrap_socket(socket.create_connection(('127.0.0.1',42999),5)); s.settimeout(20); return s,s.makefile('rw')
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
def pair(dev='phone1', pin=None, wrong=0):
    s,f=conn(); send(f,{'type':'PAIR_REQUEST','deviceId':dev,'deviceName':'Test Phone'}); ch=rd(f)
    data=next_event('PAIR_REQUEST')
    for _ in range(wrong): send(f,{'type':'PAIR_VERIFY','deviceId':dev,'pinSas':'000000'}); last=rd(f)
    if wrong: return s,f,ch,data,last
    send(f,{'type':'PAIR_VERIFY','deviceId':dev,'deviceName':'Test Phone','pinSas':data['pin']}); return s,f,ch,data,rd(f)
try:
    ident=call({'command':'GET_IDENTITY'}); check('identity has 64-hex fingerprint',len(ident['fingerprint'])==64)
    cfg=H+'/.config/zohara-link'
    check('private key is mode 600',stat.S_IMODE(os.stat(cfg+'/daemon.key').st_mode)==0o600)
    check('config folder is mode 700',stat.S_IMODE(os.stat(cfg).st_mode)==0o700)
    check('IPC socket is mode 600',stat.S_IMODE(os.stat(S).st_mode)==0o600)
    # pairing is closed by default
    s0,f0=conn(); send(f0,{'type':'PAIR_REQUEST','deviceId':'stranger','deviceName':'x'}); r0=rd(f0)
    check('pairing refused while closed',r0.get('status')=='FAILED' and 'off' in r0.get('error',''))
    s0.close()
    check('status says pairing closed',call({'command':'GET_STATUS'})['pairing_open'] is False)
    check('open pairing ok',call({'command':'OPEN_PAIRING','seconds':120})['status']=='OK')
    # 3 wrong tries kill the PIN, even the right one afterwards
    s,f,ch,data,last=pair(wrong=3)
    check('challenge carries the cert fingerprint',ch['certFingerprint']==ident['fingerprint'])
    check('PIN is not in the challenge',data['pin'] not in json.dumps(ch))
    check('3rd wrong try reports expired','expired' in last.get('error',''))
    send(f,{'type':'PAIR_VERIFY','deviceId':'phone1','pinSas':data['pin']}); check('right PIN afterwards refused',rd(f)['status']=='FAILED')
    s.close()
    s,f,ch,data,r=pair()
    check('right PIN pairs and returns a token',r['status']=='SUCCESS' and len(r['sessionToken'])==64)
    tok=r['sessionToken']
    check('window closes after a pairing',call({'command':'GET_STATUS'})['pairing_open'] is False)
    st=call({'command':'GET_STATUS'})
    check('status: device connected, no token hash',st['connected_device_ids']==['phone1'] and 'token_hash' not in json.dumps(st))
    disk=open(cfg+'/paired_devices.json').read()
    check('disk has the token hash, not the token',tok not in disk and 'token_hash' in disk)
    check('paired_devices.json is mode 600',stat.S_IMODE(os.stat(cfg+'/paired_devices.json').st_mode)==0o600)
    # remote input needs its own permission
    send(f,{'type':'INPUT_EVENT','action':'MOVE','dx':1,'dy':1}); check('input refused by default',rd(f).get('type')=='ERROR')
    check('allow input',call({'command':'SET_PERMISSION','deviceId':'phone1','input':True})['status']=='OK')
    send(f,{'type':'INPUT_EVENT','action':'MOVE','dx':1,'dy':1}); send(f,{'type':'PROXIMITY_HEARTBEAT'}); time.sleep(.3)
    # files: no overwrite, no more than declared, no ../
    dl=H+'/Downloads/ZoharaLink'
    def offer(tid,name,size):
        send(f,{'type':'FILE_OFFER','transferId':tid,'fileName':name,'fileSize':size}); return rd(f)
    b=base64.b64encode(b'hello').decode()
    check('offer accepted',offer('t1','a.txt',5)['accepted'] is True)
    send(f,{'type':'FILE_CHUNK','transferId':'t1','payloadBase64':b}); send(f,{'type':'FILE_COMPLETE','transferId':'t1'}); time.sleep(.3)
    check('offer 2 accepted',offer('t2','a.txt',5)['accepted'] is True)
    send(f,{'type':'FILE_CHUNK','transferId':'t2','payloadBase64':b}); send(f,{'type':'FILE_COMPLETE','transferId':'t2'}); time.sleep(.3)
    check('second a.txt did not overwrite the first',sorted(os.listdir(dl))==['a (1).txt','a.txt'])
    r=offer('t3','../../evil.txt',5); send(f,{'type':'FILE_CHUNK','transferId':'t3','payloadBase64':b}); send(f,{'type':'FILE_COMPLETE','transferId':'t3'}); time.sleep(.3)
    check('path traversal stays inside the folder','evil.txt' in os.listdir(dl) and not os.path.exists(H+'/evil.txt'))
    check('huge file refused',offer('t4','big.bin',10**13)['accepted'] is False)
    offer('t5','over.bin',3); send(f,{'type':'FILE_CHUNK','transferId':'t5','payloadBase64':b}); time.sleep(.3)
    check('more than declared is cancelled and deleted','over.bin' not in os.listdir(dl))
    s.close()
    # reconnect with the token
    s,f=conn(); send(f,{'type':'CLIPBOARD_SYNC','text':'x'}); check('unauthenticated packet refused',rd(f).get('type')=='ERROR')
    send(f,{'type':'AUTH','deviceId':'phone1','token':'0'*64}); check('wrong token refused',rd(f)['status']=='FAILED')
    send(f,{'type':'AUTH','deviceId':'phone1','token':tok}); check('right token accepted',rd(f)['status']=='SUCCESS')
    # a line that never ends is cut off, not buffered
    s9,f9=conn(); s9.sendall(b'a'*(300*1024)); time.sleep(.5)
    try: dead = (s9.recv(10)==b'')
    except Exception: dead=True
    check('over-long line drops the connection',dead)
    # unpair kicks it at once
    check('unpair OK',call({'command':'UNPAIR','deviceId':'phone1'})['status']=='OK')
    send(f,{'type':'CLIPBOARD_SYNC','text':'z'}); check('unpaired connection loses access',rd(f).get('type')=='ERROR')
    s2,f2=conn(); send(f2,{'type':'AUTH','deviceId':'phone1','token':tok}); check('old token useless after unpair',rd(f2)['status']=='FAILED')
    # a connection that never authenticates is dropped after the deadline
    s3,f3=conn(); t0=time.time()
    try: gone = (s3.recv(10)==b'')
    except Exception: gone=False
    check('silent connection dropped after ~15s',gone and 13<time.time()-t0<20)
finally:
    d.terminate()
print('ALL PASS' if ok else 'SOME FAILED'); sys.exit(0 if ok else 1)
