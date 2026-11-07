# Dev-machine check of phone -> daemon -> ZO-AI over real TLS, with a stand-in for ZO-AI's socket (Python is fine here: it never ships).
# Build first: cargo build --release. Run: python3 tests/e2e_zoai.py   (throwaway HOME and sockets, port 42998)
import socket, ssl, json, os, time, subprocess, threading
W='/tmp/zohara-link-e2e-zoai'; H=W+'/home'; S=W+'/z.sock'; R=W+'/run'
subprocess.run(['rm','-rf',W]); os.makedirs(H); os.makedirs(R+'/zo-ai')
BIN=os.environ.get('LINKD', os.path.join(os.path.dirname(os.path.abspath(__file__)),'..','target','release','zohara-linkd'))
d=subprocess.Popen([BIN,'--port','42998'],env=dict(os.environ,HOME=H,ZOHARA_LINK_SOCKET=S,XDG_RUNTIME_DIR=R),stderr=open(W+'/d.log','w'))
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
        if p.get('type') not in ('ADDRESSES','MEDIA_CONTROL'): return p
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

# a stand-in for ZO-AI: answers "status", and for "send" asks for an approval, waits for the answer, then says what it got
seen=[]
agent=socket.socket(socket.AF_UNIX); agent.bind(R+'/zo-ai/agent.sock'); agent.listen(5)
def serve(c):
    f=c.makefile('rw'); req=json.loads(f.readline()); seen.append(req)
    if req['op']=='send':
        f.write(json.dumps({'event':'chat','id':'c1','title':'t'})+'\n'); f.flush()
        f.write(json.dumps({'event':'approval','id':1,'tool':'zohara_shell','summary':'rm -rf x'})+'\n'); f.flush()
        ans=json.loads(f.readline())
        f.write(json.dumps({'event':'text','text':'approved' if ans.get('yes') else 'refused'})+'\n'); f.write(json.dumps({'event':'done'})+'\n'); f.flush()
    else:
        f.write(json.dumps({'ok':True,'provider':'test'})+'\n'); f.flush()
    c.close()
def loop():
    while True:
        c,_=agent.accept(); threading.Thread(target=serve,args=(c,),daemon=True).start()
threading.Thread(target=loop,daemon=True).start()

try:
    call({'command':'OPEN_PAIRING','seconds':120})
    s,f=conn(); send(f,{'type':'PAIR_REQUEST','deviceId':'phone1','deviceName':'Test Phone'}); rd(f)
    pin=next_event('PAIR_REQUEST')['pin']
    send(f,{'type':'PAIR_VERIFY','deviceId':'phone1','deviceName':'Test Phone','pinSas':pin}); r=rd(f); check('paired',r['status']=='SUCCESS')
    # off by default
    send(f,{'type':'ZOAI_REQ','rid':'a','op':'status'}); m=rd(f)
    check('refused until allowed on the computer',m['type']=='ZOAI_END' and 'not allowed' in m['error'])
    check('allow ZO-AI for the phone',call({'command':'SET_PERMISSION','deviceId':'phone1','zoai':True})['status']=='OK')
    # a plain request comes back as an event and an end
    send(f,{'type':'ZOAI_REQ','rid':'b','op':'status'}); m=rd(f)
    check('status answer is carried back',m['type']=='ZOAI_EVT' and m['rid']=='b' and m['data']['provider']=='test')
    check('then the request ends',rd(f)['type']=='ZOAI_END')
    check('the wrapper fields are not passed on',seen[-1]=={'op':'status'})
    # unknown requests and Bypass are refused by the daemon itself
    send(f,{'type':'ZOAI_REQ','rid':'c','op':'format_disk'}); m=rd(f); check('unknown request refused',m['type']=='ZOAI_END' and 'unknown' in m['error'])
    send(f,{'type':'ZOAI_REQ','rid':'d','op':'send','text':'x','mode':'bypass'}); m=rd(f); check('Bypass refused',m['type']=='ZOAI_END' and 'Bypass' in m['error'])
    n=len(seen)
    # an approval travels to the phone and its answer travels back
    send(f,{'type':'ZOAI_REQ','rid':'e','op':'send','text':'delete things'})
    m=rd(f); check('chat event',m['data']['event']=='chat')
    m=rd(f); check('approval reaches the phone',m['data']['event']=='approval' and m['data']['summary']=='rm -rf x')
    send(f,{'type':'ZOAI_LINE','rid':'e','op':'approve','id':1,'yes':True})
    m=rd(f); check('the answer reaches ZO-AI',m['data']['event']=='text' and m['data']['text']=='approved')
    check('done then end',rd(f)['data']['event']=='done' and rd(f)['type']=='ZOAI_END')
    # a line that is not an approval or stop is dropped
    send(f,{'type':'ZOAI_REQ','rid':'f','op':'send','text':'again'}); rd(f); rd(f)
    send(f,{'type':'ZOAI_LINE','rid':'f','op':'send','text':'sneaky'}); send(f,{'type':'ZOAI_LINE','rid':'f','op':'approve','id':1,'yes':False})
    m=rd(f); check('only approvals pass, a refusal is a refusal',m['data']['text']=='refused')
    # taking the permission away ends the work
    send(f,{'type':'ZOAI_REQ','rid':'g','op':'send','text':'once more'}); rd(f); rd(f)
    call({'command':'SET_PERMISSION','deviceId':'phone1','zoai':False})
    send(f,{'type':'ZOAI_LINE','rid':'g','op':'approve','id':1,'yes':True})
    m=rd(f); check('switching it off ends the request',m['type']=='ZOAI_END' and 'switched off' in m.get('error',''))
    send(f,{'type':'ZOAI_REQ','rid':'h','op':'status'}); check('and new requests are refused',rd(f)['type']=='ZOAI_END')
    # no ZO-AI running
    call({'command':'SET_PERMISSION','deviceId':'phone1','zoai':True})
    agent.close(); os.remove(R+'/zo-ai/agent.sock')
    send(f,{'type':'ZOAI_REQ','rid':'i','op':'status'}); m=rd(f); check('a clear message when ZO-AI is not running',m['type']=='ZOAI_END' and 'not running' in m['error'])
finally:
    d.terminate()
print('ALL PASS' if ok else 'SOME FAILED'); raise SystemExit(0 if ok else 1)
