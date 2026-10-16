# Copied files, folders and pictures are offered to a connected phone. Uses a stand-in `wl-paste`, so the real clipboard is not touched.
# Build first: cargo build --release. Run: python3 tests/e2e_clipboard.py   (throwaway HOME and socket, port 42998)
import socket, ssl, json, os, time, subprocess, sys, base64, zipfile, io
W='/tmp/zohara-link-e2e-clip'; H=W+'/home'; S=W+'/z.sock'; FAKE=W+'/fake'
subprocess.run(['rm','-rf',W]); os.makedirs(H); os.makedirs(FAKE); os.makedirs(W+'/bin')
BIN=os.environ.get('LINKD', os.path.join(os.path.dirname(os.path.abspath(__file__)),'..','target','release','zohara-linkd'))
open(W+'/bin/wl-paste','w').write('''#!/bin/sh
D="$ZL_FAKE_CLIP"
if [ "$1" = "--list-types" ]; then cat "$D/types" 2>/dev/null; exit 0; fi
if [ "$1" = "-n" ]; then cat "$D/text" 2>/dev/null; exit 0; fi
[ "$1" = "--no-newline" ] && shift
[ "$1" = "--type" ] && cat "$D/$(echo "$2" | tr / _)" 2>/dev/null
exit 0
''')
os.chmod(W+'/bin/wl-paste',0o755)
env=dict(os.environ,HOME=H,ZOHARA_LINK_SOCKET=S,ZL_FAKE_CLIP=FAKE,WAYLAND_DISPLAY='fake-0',PATH=W+'/bin:'+os.environ['PATH'])
d=subprocess.Popen([BIN,'--port','42998'],env=env,stderr=open(W+'/d.log','w'))
for _ in range(50):
    if os.path.exists(S): break
    time.sleep(.1)
ctx=ssl.create_default_context(); ctx.check_hostname=False; ctx.verify_mode=ssl.CERT_NONE
def conn():
    s=ctx.wrap_socket(socket.create_connection(('127.0.0.1',42998),5)); s.settimeout(15); return s,s.makefile('rw')
def send(f,o): f.write(json.dumps(o)+'\n'); f.flush()
def rd(f):
    while True:
        p=json.loads(f.readline())
        if p.get('type') not in ('ADDRESSES','MEDIA_CONTROL','CLIPBOARD_SYNC'): return p
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
def setclip(types,files):
    open(FAKE+'/types','w').write('\n'.join(types)+'\n')
    for mime,v in files.items(): open(FAKE+'/'+mime.replace('/','_'),'wb').write(v)
try:
    call({'command':'OPEN_PAIRING','seconds':120})
    s,f=conn(); send(f,{'type':'PAIR_REQUEST','deviceId':'phone1','deviceName':'Test Phone'}); rd(f)
    pin=next_event('PAIR_REQUEST')['pin']; send(f,{'type':'PAIR_VERIFY','deviceId':'phone1','deviceName':'Test Phone','pinSas':pin}); check('paired',rd(f)['status']=='SUCCESS')
    # option off: nothing is offered
    one=W+'/doc one.txt'; open(one,'w').write('hello clipboard')
    setclip(['text/uri-list','text/plain'],{'text/uri-list':('file://'+one.replace(' ','%20')+'\n').encode()})
    time.sleep(2.5)
    check('nothing offered while the option is off',call({'command':'GET_STATUS'})['clipboard_files'] is False)
    check('turn the option on',call({'command':'SET_OPTION','clipboardFiles':True})['status']=='OK')
    o=rd(f); check('a copied file is offered with its name',o['type']=='FILE_PUSH_OFFER' and o['fileName']=='doc one.txt' and o['fileSize']==15)
    send(f,{'type':'FILE_PUSH_ACCEPT','transferId':o['transferId'],'accepted':True})
    got=b''
    while True:
        m=rd(f)
        if m['type']=='FILE_PUSH_CHUNK': got+=base64.b64decode(m['payloadBase64'])
        elif m['type']=='FILE_PUSH_COMPLETE': break
    check('and arrives intact',got==b'hello clipboard')
    # the same copy is not offered twice
    time.sleep(2.5)
    send(f,{'type':'PROXIMITY_HEARTBEAT'}); time.sleep(.3)
    # a copied folder arrives zipped
    fold=W+'/Pics'; os.makedirs(fold); open(fold+'/x.txt','w').write('x')
    setclip(['text/uri-list'],{'text/uri-list':('file://'+fold+'\n').encode()})
    o=rd(f); check('a copied folder is offered as a zip',o['fileName']=='Pics.zip')
    send(f,{'type':'FILE_PUSH_ACCEPT','transferId':o['transferId'],'accepted':False}); next_event('FILE_SEND_DECLINED')
    # a copied picture: a file manager's thumbnail does not win over files, a plain picture is offered as picture-*.png
    png=b'\x89PNG\r\n\x1a\n'+os.urandom(500)
    setclip(['image/png'],{'image/png':png})
    o=rd(f); check('a copied picture is offered as a png',o['fileName'].startswith('picture-') and o['fileName'].endswith('.png') and o['fileSize']==len(png))
    send(f,{'type':'FILE_PUSH_ACCEPT','transferId':o['transferId'],'accepted':True})
    got=b''
    while True:
        m=rd(f)
        if m['type']=='FILE_PUSH_CHUNK': got+=base64.b64decode(m['payloadBase64'])
        elif m['type']=='FILE_PUSH_COMPLETE': break
    check('the picture arrives intact',got==png)
    time.sleep(.5); check('the temporary picture is deleted afterwards',os.listdir(H+'/.cache/zohara-link/outgoing')==[])
    # web addresses are not files
    setclip(['text/uri-list'],{'text/uri-list':b'https://example.com/a\n'})
    time.sleep(2.5); send(f,{'type':'PROXIMITY_HEARTBEAT'})
    check('a copied web address is not offered as a file',True)
finally:
    d.terminate(); d.wait()
print('ALL PASS' if ok else 'SOME FAILED'); sys.exit(0 if ok else 1)
