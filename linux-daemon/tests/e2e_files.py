# Dev-machine check of file transfers the way the phone does them: a real 20 MB file with its hash, a file whose size the phone
# did not know (declared 0), and transfers cut off by a dropped connection (they must not pile up and block later files).
# Build first: cargo build --release. Run: python3 tests/e2e_files.py
import socket, ssl, json, os, time, subprocess, sys, base64, hashlib
W='/tmp/zohara-link-e2e-files'; H=W+'/home'; S=W+'/z.sock'
subprocess.run(['rm','-rf',W]); os.makedirs(H)
d=subprocess.Popen([os.environ.get('LINKD', os.path.join(os.path.dirname(os.path.abspath(__file__)),'..','target','release','zohara-linkd')),'--port','42994'],env=dict(os.environ,HOME=H,ZOHARA_LINK_SOCKET=S),stderr=open(W+'/d.log','w'))
for _ in range(50):
    if os.path.exists(S): break
    time.sleep(.1)
ctx=ssl.create_default_context(); ctx.check_hostname=False; ctx.verify_mode=ssl.CERT_NONE
def conn():
    s=ctx.wrap_socket(socket.create_connection(('127.0.0.1',42994),5)); s.settimeout(20); return s,s.makefile('rw')
def send(f,o): f.write(json.dumps(o)+'\n'); f.flush()
u=socket.socket(socket.AF_UNIX); u.connect(S); u.settimeout(8); uf=u.makefile('rw')
ok=True
def check(n,c):
    global ok; print(('PASS ' if c else 'FAIL ')+n); ok&=bool(c)
def call(o):
    send(uf,o)
    while True:
        l=json.loads(uf.readline())
        if 'event' not in l: return l
call({'command':'OPEN_PAIRING'})
s,f=conn(); send(f,{'type':'PAIR_REQUEST','deviceId':'p','deviceName':'Phone'}); f.readline()
pin=call({'command':'GET_STATUS'})['pending_pairings'][0]['pin']
send(f,{'type':'PAIR_VERIFY','deviceId':'p','deviceName':'Phone','pinSas':pin}); f.readline()
dl=H+'/Downloads/ZoharaLink'
def upload(name,data,declared,tid):
    send(f,{'type':'FILE_OFFER','transferId':tid,'fileName':name,'fileSize':declared})
    for i in range(0,len(data),32768):
        send(f,{'type':'FILE_CHUNK','transferId':tid,'payloadBase64':base64.b64encode(data[i:i+32768]).decode()})
    send(f,{'type':'FILE_COMPLETE','transferId':tid,'status':'SUCCESS'})
    time.sleep(1.5)
data=os.urandom(20*1024*1024); h=hashlib.sha256(data).hexdigest()
t0=time.time(); upload('big.bin',data,len(data),'t1')
got=os.path.exists(dl+'/big.bin') and hashlib.sha256(open(dl+'/big.bin','rb').read()).hexdigest()==h
check('20 MB file arrives byte for byte (%.1fs)'%(time.time()-t0), got)
small=os.urandom(100000); upload('nosize.bin',small,0,'t2')
check('a file of unknown size (declared 0) arrives whole', os.path.exists(dl+'/nosize.bin') and os.path.getsize(dl+'/nosize.bin')==len(small))
# real leak test: authenticated connection that starts transfers then dies
st=call({'command':'OPEN_PAIRING'})
for i in range(5):
    s3,f3=conn(); send(f3,{'type':'PAIR_REQUEST','deviceId':'q%d'%i,'deviceName':'P'}); f3.readline()
    pin=call({'command':'GET_STATUS'})['pending_pairings'][0]['pin']
    send(f3,{'type':'PAIR_VERIFY','deviceId':'q%d'%i,'deviceName':'P','pinSas':pin}); f3.readline()
    send(f3,{'type':'FILE_OFFER','transferId':'leak%d'%i,'fileName':'cut%d.bin'%i,'fileSize':1000000})
    send(f3,{'type':'FILE_CHUNK','transferId':'leak%d'%i,'payloadBase64':base64.b64encode(b'x'*1000).decode()})
    time.sleep(.3)
    try: s3.shutdown(socket.SHUT_RDWR)
    except Exception: pass
    s3.close(); time.sleep(.5)  # the phone dropping off the Wi-Fi: the connection really ends
    call({'command':'OPEN_PAIRING'})
send(f,{'type':'FILE_OFFER','transferId':'after','fileName':'after.bin','fileSize':10})
r=json.loads(f.readline())
check('after 5 cut-off transfers a new offer is still accepted', r.get('accepted') is True)
check('no half-finished transfer is left behind', call({'command':'GET_STATUS'})['active_transfers']==[])
check('the cut-off partial files were deleted', not any(n.startswith('cut') for n in os.listdir(dl)))
d.terminate()
print('ALL PASS' if ok else 'SOME FAILED'); sys.exit(0 if ok else 1)
