# Dev-machine check of the remote desktop and the overlay addresses against the real daemon (Python never ships).
# Build first: cargo build --release. Run: python3 tests/e2e_remote.py   (throwaway HOME and socket, port 42998)
# The screen is faked: ZOHARA_LINK_SCREEN_CMD copies a generated PNG, so no Spectacle/display is needed.
import socket, ssl, json, os, time, subprocess, zlib, struct, base64
W='/tmp/zohara-link-e2e-remote'; H=W+'/home'; S=W+'/z.sock'
subprocess.run(['rm','-rf',W]); os.makedirs(H)
def png(w,h):
    raw=b''.join(b'\0'+b''.join(bytes([(x*3)%256,(y*3)%256,90]) for x in range(w)) for y in range(h))
    def ch(t,d): c=struct.pack('>I',len(d))+t+d; return c+struct.pack('>I',zlib.crc32(t+d)&0xffffffff)
    return b'\x89PNG\r\n\x1a\n'+ch(b'IHDR',struct.pack('>IIBBBBB',w,h,8,2,0,0,0))+ch(b'IDAT',zlib.compress(raw))+ch(b'IEND',b'')
open(W+'/screen.png','wb').write(png(1600,900))
BIN=os.environ.get('LINKD', os.path.join(os.path.dirname(os.path.abspath(__file__)),'..','target','release','zohara-linkd'))
d=subprocess.Popen([BIN,'--port','42998'],env=dict(os.environ,HOME=H,ZOHARA_LINK_SOCKET=S,ZOHARA_LINK_SCREEN_CMD='cp '+W+'/screen.png "$OUT"'),stderr=open(W+'/d.log','w'))
for _ in range(50):
    if os.path.exists(S): break
    time.sleep(.1)
ctx=ssl.create_default_context(); ctx.check_hostname=False; ctx.verify_mode=ssl.CERT_NONE
def conn():
    s=ctx.wrap_socket(socket.create_connection(('127.0.0.1',42998),5)); s.settimeout(15); return s,s.makefile('rw')
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
def until(f,t):
    while True:
        p=rd(f)
        if p.get('type')==t: return p
try:
    call({'command':'OPEN_PAIRING','seconds':120})
    s,f=conn(); send(f,{'type':'PAIR_REQUEST','deviceId':'p1','deviceName':'Phone'}); rd(f)
    pin=next_event('PAIR_REQUEST')['pin']
    send(f,{'type':'PAIR_VERIFY','deviceId':'p1','deviceName':'Phone','pinSas':pin})
    r=until(f,'PAIR_CONFIRMED'); tok=r['sessionToken']
    a=until(f,'ADDRESSES'); check('phone is told the overlay addresses after pairing',isinstance(a['overlays'],list) and a['port']==42998)
    rem=call({'command':'GET_REMOTE'}); check('GET_REMOTE answers with overlays and tailscale state',isinstance(rem['overlays'],list) and 'tailscale' in rem)
    check('REMOTE_LOGIN without tailscale gives no link',call({'command':'REMOTE_LOGIN'})['status']=='NO_LINK')
    # screen is off by default
    send(f,{'type':'SCREEN_START','maxWidth':800,'fps':5}); check('screen refused by default',until(f,'ERROR')['error'].startswith('viewing the screen'))
    check('allow screen',call({'command':'SET_PERMISSION','deviceId':'p1','screen':True})['status']=='OK')
    check('allowing the screen did not allow input',call({'command':'GET_STATUS'})['paired_devices'][0]['allow_input'] is False)
    send(f,{'type':'SCREEN_START','maxWidth':800,'fps':5}); fr=until(f,'SCREEN_FRAME')
    jpg=base64.b64decode(fr['jpegBase64'])
    check('frame is a 800x450 JPEG of a 1600x900 screen',jpg[:2]==b'\xff\xd8' and (fr['width'],fr['height'])==(800,450) and (fr['screenWidth'],fr['screenHeight'])==(1600,900))
    # the screen does not change: no second frame
    import select
    more=False
    end=time.time()+1.5
    while time.time()<end:
        if select.select([s],[],[],0.3)[0] or s.pending():
            if rd(f).get('type')=='SCREEN_FRAME': more=True
    check('an unchanged screen is not sent again',not more)
    # taking the permission away ends the stream
    call({'command':'SET_PERMISSION','deviceId':'p1','screen':False}); time.sleep(.5)
    send(f,{'type':'SCREEN_START'}); check('stream refused after permission removed',until(f,'ERROR')['error'].startswith('viewing the screen'))
    # new input actions need the input permission
    for pkt in ({'action':'MOVE_TO','x':.5,'y':.5},{'action':'TYPE','text':'hi'},{'action':'KEY','key':'ENTER'},{'action':'SCROLL','lines':3}):
        send(f,dict(pkt,type='INPUT_EVENT')); check('input '+pkt['action']+' refused by default',until(f,'ERROR')['error'].startswith('remote input'))
    s.close()
    # a dropped connection ends its stream (daemon stays healthy)
    call({'command':'SET_PERMISSION','deviceId':'p1','screen':True})
    s,f=conn(); send(f,{'type':'AUTH','deviceId':'p1','token':tok}); until(f,'AUTH_RESULT'); until(f,'ADDRESSES')
    send(f,{'type':'SCREEN_START','fps':5}); until(f,'SCREEN_FRAME'); s.shutdown(socket.SHUT_RDWR); s.close(); time.sleep(.5)
    check('daemon alive after a phone vanished mid-stream',d.poll() is None)
    call({'command':'UNPAIR','deviceId':'p1'})
finally:
    d.terminate()
print('ALL PASS' if ok else 'SOME FAILED'); raise SystemExit(0 if ok else 1)
