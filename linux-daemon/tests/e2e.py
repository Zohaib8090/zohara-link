# Dev-machine check of the real daemon over real TLS (Python is fine here: it never ships). Build first: cargo build --release.
# Run: python3 tests/e2e.py   (uses a throwaway HOME and socket, port 42999)
import socket, ssl, json, os, time, subprocess, sys
H='/tmp/zohara-link-e2e/home'; S='/tmp/zohara-link-e2e/z.sock'
subprocess.run(['rm','-rf',H,S]); os.makedirs(H)
env=dict(os.environ, HOME=H, ZOHARA_LINK_SOCKET=S, RUST_LOG='info')
d=subprocess.Popen([os.environ.get('LINKD', os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'target', 'release', 'zohara-linkd')),'--port','42999'],env=env,stderr=open('/tmp/zohara-link-e2e/d.log','w'))
for _ in range(50):
    if os.path.exists(S): break
    time.sleep(.1)
ctx=ssl.create_default_context(); ctx.check_hostname=False; ctx.verify_mode=ssl.CERT_NONE
def conn():
    s=ctx.wrap_socket(socket.create_connection(('127.0.0.1',42999),5)); s.settimeout(5); return s,s.makefile('rw')
def send(f,o): f.write(json.dumps(o)+'\n'); f.flush()
def rd(f): return json.loads(f.readline())
def ipc(cmd):
    u=socket.socket(socket.AF_UNIX); u.connect(S); u.settimeout(5); f=u.makefile('rw'); return u,f
ok=True
def check(n,c):
    global ok; print(('PASS ' if c else 'FAIL ')+n); ok&=bool(c)
try:
    u,uf=ipc(None); 
    send(uf,{'command':'GET_IDENTITY'}); ident=rd(uf); check('identity has 64-hex fingerprint',len(ident['fingerprint'])==64)
    def pair(pin_fn, dev='phone1'):
        s,f=conn(); send(f,{'type':'PAIR_REQUEST','deviceId':dev,'deviceName':'Test Phone'}); ch=rd(f)
        ev=None
        while True:
            ev=json.loads(uf.readline())
            if ev.get('event')=='PAIR_REQUEST': break
        return s,f,ch,ev['data']
    s,f,ch,data=pair(None)
    check('challenge has the cert fingerprint matching identity',ch['certFingerprint']==ident['fingerprint'])
    check('PIN not echoed in challenge','pin' not in json.dumps(ch).lower() or 'pinSas' not in ch)
    # 3 wrong tries kill the PIN, even the right one afterwards
    for i in range(3):
        send(f,{'type':'PAIR_VERIFY','deviceId':'phone1','pinSas':'000000'}); r=rd(f)
    check('3rd wrong try reports expired','expired' in r.get('error',''))
    send(f,{'type':'PAIR_VERIFY','deviceId':'phone1','pinSas':data['pin']}); r=rd(f)
    check('right PIN after 3 wrong is refused',r['status']=='FAILED')
    s.close()
    # correct pairing
    s,f,ch,data=pair(None)
    send(f,{'type':'PAIR_VERIFY','deviceId':'phone1','deviceName':'Test Phone','pinSas':data['pin']}); r=rd(f)
    check('right PIN pairs and returns a token',r['status']=='SUCCESS' and len(r['sessionToken'])==64)
    tok=r['sessionToken']
    send(f,{'type':'CLIPBOARD_SYNC','text':'hi'}); 
    send(uf,{'command':'GET_STATUS'})
    while True:
        l=json.loads(uf.readline())
        if 'paired_devices' in l: st=l; break
    check('status lists device as connected',st['connected_device_ids']==['phone1'])
    check('status never leaks the token hash','token_hash' not in json.dumps(st))
    check('token hash stored on disk, not the token',tok not in open(H+'/.config/zohara-link/paired_devices.json').read() and 'token_hash' in open(H+'/.config/zohara-link/paired_devices.json').read())
    s.close()
    # reconnect with token
    s,f=conn(); send(f,{'type':'CLIPBOARD_SYNC','text':'x'}); r=rd(f); check('unauthenticated packet refused',r.get('type')=='ERROR')
    send(f,{'type':'AUTH','deviceId':'phone1','token':'0'*64}); r=rd(f); check('wrong token refused',r['status']=='FAILED')
    send(f,{'type':'AUTH','deviceId':'phone1','token':tok}); r=rd(f); check('right token accepted',r['status']=='SUCCESS')
    send(f,{'type':'CLIPBOARD_SYNC','text':'y'}); time.sleep(.3)
    # unpair kicks it
    send(uf,{'command':'UNPAIR','deviceId':'phone1'})
    while True:
        l=json.loads(uf.readline())
        if l.get('status') in ('OK','NOT_FOUND'): break
    check('unpair OK',l['status']=='OK')
    send(f,{'type':'CLIPBOARD_SYNC','text':'z'}); r=rd(f); check('unpaired connection loses access at once',r.get('type')=='ERROR')
    s2,f2=conn(); send(f2,{'type':'AUTH','deviceId':'phone1','token':tok}); r=rd(f2); check('old token useless after unpair',r['status']=='FAILED')
finally:
    d.terminate()
print('ALL PASS' if ok else 'SOME FAILED'); sys.exit(0 if ok else 1)
