# Phone -> real zohara-linkd (real TLS) -> real ZO-AI service (zo-core's `serve-for-test` example, with a fake model).
# Build first: cargo build --release (here) and `cargo build -p zo-core --example serve-for-test` in the ZO-AI repository, then
#   ZOAI_SERVE=/path/to/serve-for-test python3 tests/e2e_zoai_real.py
import socket, ssl, json, os, time, subprocess, sys
SERVE=os.environ.get('ZOAI_SERVE')
if not SERVE: print('set ZOAI_SERVE to the serve-for-test binary'); raise SystemExit(2)
W='/tmp/zohara-link-e2e-zoai-real'; H=W+'/home'; S=W+'/z.sock'; R=W+'/run'
subprocess.run(['rm','-rf',W,'/tmp/zo-e2e-touched']); os.makedirs(H); os.makedirs(R,mode=0o700)
env=dict(os.environ,HOME=H,ZOHARA_LINK_SOCKET=S,XDG_RUNTIME_DIR=R,XDG_CONFIG_HOME=W+'/cfg',XDG_DATA_HOME=W+'/data')
ai=subprocess.Popen([SERVE],env=env,stdout=subprocess.PIPE,stderr=open(W+'/ai.log','w'))
assert ai.stdout.readline().startswith(b'READY')
d=subprocess.Popen([os.path.join(os.path.dirname(os.path.abspath(__file__)),'..','target','release','zohara-linkd'),'--port','42997'],env=env,stderr=open(W+'/d.log','w'))
for _ in range(50):
    if os.path.exists(S): break
    time.sleep(.1)
ctx=ssl.create_default_context(); ctx.check_hostname=False; ctx.verify_mode=ssl.CERT_NONE
def conn():
    s=ctx.wrap_socket(socket.create_connection(('127.0.0.1',42997),5)); s.settimeout(30); return s,s.makefile('rw')
def send(f,o): f.write(json.dumps(o)+'\n'); f.flush()
def rd(f):
    while True:
        p=json.loads(f.readline())
        if p.get('type') not in ('ADDRESSES','MEDIA_CONTROL'): return p
ok=True
def check(n,c):
    global ok; print(('PASS ' if c else 'FAIL ')+n); ok&=bool(c)
u=socket.socket(socket.AF_UNIX); u.connect(S); u.settimeout(10); uf=u.makefile('rw')
def call(o):
    send(uf,o)
    while True:
        l=json.loads(uf.readline())
        if 'event' not in l: return l
def next_event(n):
    while True:
        l=json.loads(uf.readline())
        if l.get('event')==n: return l['data']
try:
    call({'command':'OPEN_PAIRING','seconds':120})
    s,f=conn(); send(f,{'type':'PAIR_REQUEST','deviceId':'p','deviceName':'Phone'}); rd(f)
    pin=next_event('PAIR_REQUEST')['pin']; send(f,{'type':'PAIR_VERIFY','deviceId':'p','deviceName':'Phone','pinSas':pin}); check('paired',rd(f)['status']=='SUCCESS')
    call({'command':'SET_PERMISSION','deviceId':'p','zoai':True})
    send(f,{'type':'ZOAI_REQ','rid':'1','op':'status'}); m=rd(f)['data']; check('real ZO-AI answers status (Manual mode, fake model)',m['ok'] and m['mode']=='Manual' and m['provider']=='ollama')
    rd(f)
    # a message: the model asks to run a command; in Manual mode ZO-AI asks the phone first
    send(f,{'type':'ZOAI_REQ','rid':'2','op':'send','text':'run the test command'})
    ev=[]
    while True:
        m=rd(f)
        if m['type']=='ZOAI_END': break
        e=m['data']; ev.append(e); print('  <-',e['event'])
        if e['event']=='approval':
            check('the question reaches the phone with the command in it','echo from-the-laptop' in e['summary'])
            check('nothing has run before the answer',not os.path.exists('/tmp/zo-e2e-touched'))
            send(f,{'type':'ZOAI_LINE','rid':'2','op':'approve','id':e['id'],'yes':True})
    kinds=[e['event'] for e in ev]
    check('events: chat, tool, done',kinds[0]=='chat' and 'tool_start' in kinds and kinds[-1]=='done')
    check('the command really ran on the computer',os.path.exists('/tmp/zo-e2e-touched'))
    check('the model saw its output and answered',any(e['event']=='text' and 'from-the-laptop' in e['text'] for e in ev))
    chat_id=ev[0]['id']
    # the chat is saved and can be listed and read back
    send(f,{'type':'ZOAI_REQ','rid':'3','op':'chats'}); m=rd(f)['data']; rd(f)
    check('the chat is listed',any(c['id']==chat_id for c in m['chats']))
    send(f,{'type':'ZOAI_REQ','rid':'4','op':'chat','id':chat_id}); m=rd(f)['data']; rd(f)
    check('and can be opened again with its messages',any(e['kind']=='msg-user' for e in m['entries']) and any(e['kind']=='msg-ai' for e in m['entries']))
    # refusing: the command must not run
    subprocess.run(['rm','-f','/tmp/zo-e2e-touched'])
    send(f,{'type':'ZOAI_REQ','rid':'5','op':'send','text':'again please'})
    while True:
        m=rd(f)
        if m['type']=='ZOAI_END': break
        if m['data']['event']=='approval': send(f,{'type':'ZOAI_LINE','rid':'5','op':'approve','id':m['data']['id'],'yes':False})
    check('a refusal keeps the command from running',not os.path.exists('/tmp/zo-e2e-touched'))
    # MCP servers: list and an import of a bad config reports an error instead of crashing
    send(f,{'type':'ZOAI_REQ','rid':'6','op':'mcp_list'}); m=rd(f)['data']; rd(f); check('MCP list answers',m['ok'] and m['servers']==[])
    send(f,{'type':'ZOAI_REQ','rid':'7','op':'mcp_import','config':'not json'}); m=rd(f)['data']; rd(f); check('a bad MCP config is reported',m['ok'] is False)
finally:
    d.terminate(); ai.terminate()
print('ALL PASS' if ok else 'SOME FAILED'); raise SystemExit(0 if ok else 1)
