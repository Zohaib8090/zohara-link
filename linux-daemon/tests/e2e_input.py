# Dev-machine check of the phone's mouse, keyboard and game controller against the real daemon (Python never ships).
# Build first: cargo build. Run: LINKD=target/debug/zohara-linkd python3 tests/e2e_input.py   (throwaway HOME and socket, port 42997)
# The input permission is off by default; with it on, a GAMEPAD message must make a real controller appear in the kernel's list
# (needs /dev/uinput access, as the signed-in user has on Zohara) and GAMEPAD_OFF must remove it again.
import socket, ssl, json, os, time, subprocess
W='/tmp/zohara-link-e2e-input'; H=W+'/home'; S=W+'/z.sock'
subprocess.run(['rm','-rf',W]); os.makedirs(H)
BIN=os.environ.get('LINKD', os.path.join(os.path.dirname(os.path.abspath(__file__)),'..','target','release','zohara-linkd'))
d=subprocess.Popen([BIN,'--port','42997'],env=dict(os.environ,HOME=H,ZOHARA_LINK_SOCKET=S),stderr=open(W+'/d.log','w'))
for _ in range(50):
    if os.path.exists(S): break
    time.sleep(.1)
ctx=ssl.create_default_context(); ctx.check_hostname=False; ctx.verify_mode=ssl.CERT_NONE
def conn():
    s=ctx.wrap_socket(socket.create_connection(('127.0.0.1',42997),5)); s.settimeout(15); return s,s.makefile('rw')
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
def devices():
    return open('/proc/bus/input/devices').read()
def has_uinput():
    try: open('/dev/uinput','wb').close(); return True
    except OSError: return False
try:
    call({'command':'OPEN_PAIRING','seconds':120})
    s,f=conn(); send(f,{'type':'PAIR_REQUEST','deviceId':'p1','deviceName':'Phone'}); rd(f)
    pin=next_event('PAIR_REQUEST')['pin']
    send(f,{'type':'PAIR_VERIFY','deviceId':'p1','deviceName':'Phone','pinSas':pin})
    until(f,'PAIR_CONFIRMED'); until(f,'ADDRESSES')
    # without the permission nothing is accepted
    for pkt in ({'action':'GAMEPAD','buttons':1},{'action':'BUTTON','button':'LEFT','down':True},{'action':'KEY_DOWN','code':30},{'action':'GAMEPAD_OFF'}):
        send(f,dict(pkt,type='INPUT_EVENT')); check(pkt['action']+' refused by default',until(f,'ERROR')['error'].startswith('remote input'))
    check('no controller exists without the permission','Microsoft X-Box 360 pad' not in devices())
    check('allow input',call({'command':'SET_PERMISSION','deviceId':'p1','input':True})['status']=='OK')
    if has_uinput():
        send(f,{'type':'INPUT_EVENT','action':'GAMEPAD','buttons':1,'lx':0.5,'ly':-1,'lt':1,'hatX':1})
        for _ in range(40):
            if 'Microsoft X-Box 360 pad' in devices(): break
            time.sleep(.05)
        check('a controller appears when the phone sends its state','Microsoft X-Box 360 pad' in devices())
        send(f,{'type':'INPUT_EVENT','action':'MOVE','dx':1.0,'dy':0.0}); send(f,{'type':'INPUT_EVENT','action':'MOVE','dx':-1.0,'dy':0.0})
        send(f,{'type':'INPUT_EVENT','action':'GAMEPAD','buttons':0xffffffff,'lx':99,'rt':-5,'hatX':9})
        send(f,{'type':'INPUT_EVENT','action':'KEY_DOWN','code':116}); send(f,{'type':'INPUT_EVENT','action':'KEY_DOWN','code':100000})
        time.sleep(.3); check('wild or dangerous values do not stop the daemon',d.poll() is None)
        send(f,{'type':'INPUT_EVENT','action':'GAMEPAD_OFF'})
        for _ in range(40):
            if 'Microsoft X-Box 360 pad' not in devices(): break
            time.sleep(.05)
        check('the controller is removed again',  'Microsoft X-Box 360 pad' not in devices())
    else:
        print('SKIP  /dev/uinput cannot be opened here: the controller checks were not run')
    s.close(); time.sleep(.3)
    check('daemon alive after the phone left',d.poll() is None)
    call({'command':'UNPAIR','deviceId':'p1'})
finally:
    d.terminate()
print('ALL PASS' if ok else 'SOME FAILED'); raise SystemExit(0 if ok else 1)
