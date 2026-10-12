# Dev-machine check of the phone's media buttons and now-playing push against a real MPRIS player on a real session bus.
# Needs: a session bus (run under `dbus-run-session`), a player that serves org.mpris.MediaPlayer2.* and logs its calls to
# $FAKE_PLAYER_LOG (see the "fakeplayer" helper described in docs), and `cargo build --release`.
import socket, ssl, json, os, time, subprocess, sys
W='/tmp/zohara-link-e2e-media'; H=W+'/home'; S=W+'/z.sock'; LOG=os.environ['FAKE_PLAYER_LOG']
subprocess.run(['rm','-rf',W]); os.makedirs(H)
BIN=os.environ.get('LINKD', os.path.join(os.path.dirname(os.path.abspath(__file__)),'..','target','release','zohara-linkd'))
d=subprocess.Popen([BIN,'--port','42995'],env=dict(os.environ,HOME=H,ZOHARA_LINK_SOCKET=S),stderr=open(W+'/d.log','w'))
for _ in range(50):
    if os.path.exists(S): break
    time.sleep(.1)
ctx=ssl.create_default_context(); ctx.check_hostname=False; ctx.verify_mode=ssl.CERT_NONE
def conn():
    s=ctx.wrap_socket(socket.create_connection(('127.0.0.1',42995),5)); s.settimeout(8); return s,s.makefile('rw')
def send(f,o): f.write(json.dumps(o)+'\n'); f.flush()
ok=True
def check(n,c):
    global ok; print(('PASS ' if c else 'FAIL ')+n); ok&=bool(c)
u=socket.socket(socket.AF_UNIX); u.connect(S); u.settimeout(8); uf=u.makefile('rw')
def call(o):
    send(uf,o)
    while True:
        l=json.loads(uf.readline())
        if 'event' not in l: return l
def calls(): return open(LOG).read().split() if os.path.exists(LOG) else []
try:
    call({'command':'OPEN_PAIRING'})
    s,f=conn(); send(f,{'type':'PAIR_REQUEST','deviceId':'p','deviceName':'Phone'}); f.readline()
    pin=call({'command':'GET_STATUS'})['pending_pairings'][0]['pin']
    send(f,{'type':'PAIR_VERIFY','deviceId':'p','deviceName':'Phone','pinSas':pin}); f.readline()
    # the daemon pushes what is playing within a few seconds
    got=None; t0=time.time()
    while time.time()-t0<10:
        l=json.loads(f.readline())
        if l.get('type')=='MEDIA_CONTROL': got=l; break
    check('now-playing pushed to the phone with the real title and artist', got and got['title']=='Test Song' and got['artist']=='Test Artist' and got['isPlaying'] is True)
    for action,expect in [('PLAY_PAUSE','PlayPause'),('NEXT','Next'),('PREVIOUS','Previous')]:
        before=len(calls()); send(f,{'type':'MEDIA_CONTROL','action':action}); time.sleep(.8)
        check(f'{action} reached the player', calls()[before:]==[expect])
    # play/pause flips the state and the phone hears about it
    paused=None; t0=time.time()
    while time.time()-t0<8:
        l=json.loads(f.readline())
        if l.get('type')=='MEDIA_CONTROL' and l.get('isPlaying') is False: paused=l; break
    check('phone is told when the player pauses', paused is not None)
    # a burst of mouse moves must not stall the connection (each used to start a process and wait for it)
    call({'command':'SET_PERMISSION','deviceId':'p','input':True})
    t0=time.time()
    for _ in range(600): send(f,{'type':'INPUT_EVENT','action':'MOVE','dx':3,'dy':2})
    send(f,{'type':'MEDIA_CONTROL','action':'NEXT'}); before=len(calls())
    t1=time.time()
    while time.time()-t1<3 and len(calls())==before: time.sleep(.05)
    check('600 queued mouse moves do not hold up the next command (%.2fs)'%(time.time()-t0), len(calls())>before and time.time()-t0<3)
finally:
    d.terminate()
print('ALL PASS' if ok else 'SOME FAILED'); sys.exit(0 if ok else 1)
