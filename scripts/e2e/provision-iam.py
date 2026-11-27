#!/usr/bin/env python3
"""Provision an E2-side namespace containing a disposable IAM scenario stack."""
from pathlib import Path
import base64
import datetime
import hashlib
import json
import os
import re
import secrets
import shlex
import shutil
import signal
import socket
import socketserver
import subprocess
import sys
import threading
import time
import urllib.parse
import urllib.request

script = Path(__file__).resolve()
if len(sys.argv) != 4:
    raise SystemExit('usage: provision-iam.py <start|cleanup> <candidate-tag> <absolute-namespace-directory>')
action, candidate, workspace = sys.argv[1:]
if not re.fullmatch(r'cand-\d{8}-[a-f0-9]{8}', candidate) or not Path(workspace).is_absolute():
    raise SystemExit('invalid candidate image tag or namespace directory')
root = Path(workspace).resolve()
revision = candidate.rsplit('-', 1)[-1]
prefix = 'agent-platform-s4-' + revision
owner_label = 'agent-platform.s4-owner'
owner = hashlib.sha256(str(root).encode()).hexdigest()
owner_option = ['--label', owner_label + '=' + owner]
source_prefix = 'agent-platform'
pg = prefix + '-pg'
database = 's4_e2e'
roles = ('USER', 'BUILDER', 'OPERATOR')
services = ('authority', 'app', 'engine', 'skill', 'model', 'monitor', 'gateway')
ports = (19070, 18990)
key_state = root / 'issued.json'
namespace_marker = root / '.s4-namespace'


def call(args, **kwargs):
    return subprocess.run(args, check=True, universal_newlines=True, stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE, **kwargs).stdout.strip()


def docker(*args, **kwargs):
    return call(['docker', *args], **kwargs)


def inspect(name):
    return json.loads(docker('inspect', name))[0]


def environment(container):
    return dict(line.split('=', 1) for line in container['Config']['Env'])


def psql(name, db, sql):
    return docker('exec', '-i', name, 'psql', '-XAt', '-U', 'agentplat', '-d', db,
                  '-v', 'ON_ERROR_STOP=1', input=sql)


def identify(path, method='GET', body=None):
    deployment = environment(inspect(source_prefix + '-authority'))
    req = urllib.request.Request('http://127.0.0.1:19070' + path, method=method,
                                 headers={'X-API-Key': deployment['AGENT_SECRET_USER_MGMT_SUBJECT_LOOKUP'],
                                          'Content-Type': 'application/json'},
                                 data=json.dumps(body).encode() if body is not None else None)
    with urllib.request.urlopen(req, timeout=12) as response:
        payload = json.load(response)
    if payload.get('code') != 200:
        raise RuntimeError('user-mgmt API failed for ' + path.split('?')[0])
    return payload


def issue_keys():
    data = {'USER': 'operatorA'}
    # Role identities are scoped to the isolated platform; only approved directory test accounts are used.
    for role in ('BUILDER', 'OPERATOR'):
        subject = os.environ.get('E2E_'+role+'_SUBJECT', '').strip()
        if subject:
            data[role] = subject
    if len(set(data.values())) != len(data) or 'admin' in data.values():
        raise ValueError('additional directory subjects must be distinct test accounts')
    expires = (datetime.datetime.utcnow() + datetime.timedelta(minutes=105)).strftime('%Y-%m-%dT%H:%M:%SZ')
    issued = {}
    try:
        for role, subject in data.items():
            actor = identify('/system/apikeys/identity/' + urllib.parse.quote(subject, safe=''))['data']
            assert actor['userName'] == subject and str(actor['status']) == '0'
            key = identify('/system/apikeys', 'POST', {'userName': subject, 'expiresAt': expires,
                                                       'remark': 'E2 S4 isolated scenarios'})['data']
            issued[role] = int(key['id'])
            key_state.write_text(json.dumps(issued))
            data[role] = {'subject': subject, 'secret': key['apiKey']}
    except Exception:
        revoke_keys()
        raise
    return data


def revoke_keys():
    if not key_state.exists():
        return
    issued = json.loads(key_state.read_text())
    for role, key_id in list(issued.items()):
        identify('/system/apikeys/{}/revoke'.format(int(key_id)), 'PUT')
        issued.pop(role)
        key_state.write_text(json.dumps(issued))
        print('Revoked temporary directory key:', role, key_id, flush=True)
    key_state.unlink()


def relay(direction):
    class Pipe(socketserver.BaseRequestHandler):
        def handle(self):
            try:
                if direction == 'host':
                    upstream = socket.create_connection(('127.0.0.1', self.server.port), timeout=10)
                else:
                    upstream = socket.socket(socket.AF_UNIX)
                    upstream.connect('p{}.sock'.format(self.server.port))
                with upstream:
                    upstream.settimeout(None)
                    def pump(source, target):
                        try:
                            while True:
                                data = source.recv(65536)
                                if not data:
                                    break
                                target.sendall(data)
                        except OSError:
                            pass
                        finally:
                            try:
                                target.shutdown(socket.SHUT_WR)
                            except OSError:
                                pass
                    reply = threading.Thread(target=pump, args=(upstream, self.request), daemon=True)
                    reply.start()
                    pump(self.request, upstream)
                    reply.join()
            except OSError:
                pass

    class Tcp(socketserver.ThreadingTCPServer):
        allow_reuse_address = True
        daemon_threads = True

    class Unix(socketserver.ThreadingMixIn, socketserver.UnixStreamServer):
        daemon_threads = True

    servers = []
    for port in ports:
        if direction == 'host':
            address = root / 'p{}.sock'.format(port)
            if address.exists():
                address.unlink()
            server = Unix(address.name, Pipe)
        else:
            server = Tcp(('127.0.0.1', port), Pipe)
        server.port = port
        servers.append(server)
        threading.Thread(target=server.serve_forever, daemon=True).start()
    print(direction, 'relay ready for approved endpoints', ports, flush=True)
    while True:
        time.sleep(60)


def start_relay(pid):
    for direction, argv in [('host', []), ('isolated', ['nsenter','--target',str(pid),'--net'])]:
        with (root / (direction + '-relay.log')).open('w') as receipt:
            process = subprocess.Popen(argv + [sys.executable, str(script), direction + '-relay', candidate, str(root)],
                                       cwd=str(root), stdout=receipt, stderr=receipt, start_new_session=True)
        with (root / 'relay-pids').open('a') as state:
            state.write(str(process.pid)+'\n')
    wait(lambda: (root/'host-relay.log').read_text().startswith('host relay ready')
         and (root/'isolated-relay.log').read_text().startswith('isolated relay ready'), 'loopback relays')


def wait(ready, label, retries=100):
    for _ in range(retries):
        try:
            if ready():
                print('Ready:', label, flush=True)
                return
        except (OSError, subprocess.CalledProcessError):
            pass
        time.sleep(2)
    raise RuntimeError(label + ' did not become ready')


def isolated_http(pid, port, path, method='GET', body=None, extra=None, timeout=15):
    args = ['nsenter','--target',str(pid),'--net','curl','-sS','--max-time',str(timeout),
             '-X',method,'-w','\n%{http_code}']
    for key, value in (extra or {}).items():
        args += ['-H', key+': '+value]
    data = None
    if body is not None:
        args += ['-H','Content-Type: application/json','-d','@-']
        data = json.dumps(body)
    raw = call(args+['http://127.0.0.1:{}{}'.format(port,path)], input=data)
    response, status = raw.rsplit('\n', 1)
    try:
        decoded = json.loads(response)
    except ValueError:
        decoded = {}
    return int(status), decoded


def seed():
    if psql(pg, database, 'select current_database();') != database:
        raise RuntimeError('unexpected database')
    allowed = "'r22e2-real-v2','satellite-planning-assistant','user-governance-assistant'"
    imports = [
        ('model.provider',"tenant_id='demo' and code='deepseek' and id=1", 'ON CONFLICT DO NOTHING'),
        ('model.entry',"tenant_id='demo' and provider_id=1 and id=1", 'ON CONFLICT DO NOTHING'),
        ('model.binding_snapshot',"tenant_id='demo' and provider_id=1 and model_entry_id=1", 'ON CONFLICT DO NOTHING'),
        ('app.application',"tenant_id='demo' and code in ("+allowed+")",
         'ON CONFLICT(tenant_id,code) DO UPDATE SET current_version_no=EXCLUDED.current_version_no'),
        ('app.app_draft',"tenant_id='demo' and app_code in ("+allowed+")",
         'ON CONFLICT DO NOTHING'),
        ('app.app_version',"tenant_id='demo' and app_code in ("+allowed+")"
         " and version_no=(select current_version_no from app.application a where a.tenant_id=t.tenant_id and a.code=t.app_code)",
         'ON CONFLICT(tenant_id,app_code,version_no) DO NOTHING'),
    ]
    statements = []
    for table, condition, conflict in imports:
        rows = psql(source_prefix+'-pg', 'agent_platform',
                    "begin read only; select row_to_json(t) from "+table+" t where "+condition+"; rollback;")
        decoded = [row for row in rows.splitlines() if row.startswith('{')]
        if not decoded and table != 'model.binding_snapshot':
            raise RuntimeError('existing test reference not found: ' + table)
        for row in decoded:
            escaped = row.replace("'", "''")
            records = "json_populate_record(null::"+table+",'"+escaped+"'::json) as r"
            if table.startswith('app.'):
                # Retain application keys and release snapshots while rebinding numeric fixture IDs.
                columns = psql(pg, database, "select column_name from information_schema.columns"
                               " where table_schema='app' and table_name='"+table.split('.')[1]+"'"
                               " and column_name<>'id' order by ordinal_position;").splitlines()
                projections = {'app_id': 'a.id', 'app_draft_id': 'd.id'}
                sql = "insert into "+table+"("+','.join(columns)+") select "
                sql += ','.join(projections.get(c, 'r.'+c) for c in columns) + " from " + records
                if 'app_id' in columns:
                    sql += ' join app.application a on a.tenant_id=r.tenant_id and a.code=r.app_code'
                if 'app_draft_id' in columns:
                    sql += (' join lateral (select id from app.app_draft where tenant_id=r.tenant_id'
                            ' and app_code=r.app_code order by draft_no desc limit 1) d on true')
                statements.append(sql+" "+conflict+";")
            else:
                statements.append("insert into "+table+" overriding system value select * from "+
                                  records+" "+conflict+";")
        print('Imported scoped source rows:', table, len(decoded), flush=True)
    psql(pg, database, "\n".join(['begin;', *statements, 'commit;']))


def start():
    if key_state.exists() or (root/'relay-pids').exists():
        raise RuntimeError('existing sandbox state, clean up before starting a new run')
    keys = issue_keys()
    sql_password = secrets.token_urlsafe(24)
    redis_password = secrets.token_urlsafe(24)
    common = dict(AGENT_DB_PASSWORD=sql_password, AGENT_REDIS_PASSWORD=redis_password,
                  AGENT_SYSTEM_REDIS_ADDRESS='redis://:'+redis_password+'@127.0.0.1:16379')
    pg_env = root / 'pg.env'
    pg_env.write_text('POSTGRES_USER=agentplat\nPOSTGRES_PASSWORD='+sql_password+
                      '\nPOSTGRES_DB='+database+'\nPGPORT=15432\n')
    print('PostgreSQL namespace id:',docker('run','-d','--network','none',*owner_option,
                                            '--name',pg,'--env-file',str(pg_env),'postgres:16'),flush=True)
    pg_env.unlink()
    pid = inspect(pg)['State']['Pid']
    (root/'namespace.pid').write_text(str(pid))
    wait(lambda: psql(pg,database,'select current_database();')==database, database)
    start_relay(pid)
    docker('run','-d','--network','container:'+pg,*owner_option,'--name',prefix+'-redis','redis:7','redis-server',
           '--bind','127.0.0.1','--port','16379','--requirepass',redis_password,'--appendonly','no')
    signing_key = root/'authority-s4.pem'
    call(['openssl','genpkey','-algorithm','RSA','-out',str(signing_key),'-pkeyopt','rsa_keygen_bits:2048'])
    os.chown(str(signing_key),10001,10001)
    signing_key.chmod(0o400)
    vault = root/'vault'
    vault.mkdir()
    os.chown(str(vault),10001,10001)
    for service in services:
        container = inspect(source_prefix+'-'+service)
        if container['Config']['Image'].rsplit(':', 1)[-1] != candidate:
            raise RuntimeError('E2 main candidate mismatch: ' + service)
        runtime = environment(container)
        runtime.update(common)
        for key in ('AGENT_DB_URL','AGENT_DB_JDBC_BASE','AGENT_AUTHORITY_REVOCATION_DB'):
            if runtime.get(key):
                runtime[key] = runtime[key].replace('/agent_platform','/'+database)
        runtime['JAVA_TOOL_OPTIONS'] = '-Xms128m -Xmx640m'
        runtime['AGENT_DEV_AUTH'] = 'false'
        mounts = []
        for item in container['Mounts']:
            source, dest, read_only = item['Source'], item['Destination'], True
            if dest == '/opt/agent/app-vault':
                source, read_only = str(vault), False
            if service == 'authority' and dest == '/run/secrets/authority-rsa.pem':
                if runtime.get('AGENT_AUTHORITY_INTEGRATION_POLICY_KEY_PATH') == dest:
                    mounts += ['-v',source+':/run/secrets/authority-policy.pem:ro']
                    runtime['AGENT_AUTHORITY_INTEGRATION_POLICY_KEY_PATH'] = '/run/secrets/authority-policy.pem'
                source = str(signing_key)
            mounts += ['-v',source+':'+dest+(':ro' if read_only else ':rw')]
        if service == 'authority':
            for role, entry in keys.items():
                runtime['AGENT_SECRET_S4_DIRECTORY_'+role] = entry['secret']
            runtime['AGENT_AUTHORITY_TEST_TICKET_ENABLED'] = 'false'
        health = []
        if service == 'authority':
            probe = container['Config']['Healthcheck']['Test']
            if probe[0] != 'CMD':
                raise RuntimeError('unexpected authority readiness probe')
            health = ['--health-cmd', ' '.join(shlex.quote(arg) for arg in probe[1:])]
        path = root/(service+'.env')
        assert all('\n' not in value and '\r' not in value for value in runtime.values())
        path.write_text('\n'.join(key+'='+value for key,value in sorted(runtime.items()))+'\n')
        try:
            docker('run','-d','--network','container:'+pg,*owner_option,'--name',prefix+'-'+service,
                   '--env-file',str(path),*health,*mounts,container['Config']['Image'])
        finally:
            path.unlink()
    wait(lambda: isolated_http(pid,18086,'/.well-known/jwks.json')[0]==200,'isolated real authority')
    for service, port in [('app',18181),('engine',18182),('skill',18183),('model',18184),('monitor',18185),('gateway',18180)]:
        wait(lambda: isolated_http(pid,port,'/actuator/health/readiness')[0]==200,service)
    complete(pid, keys)


def complete(pid, keys):
    seed()
    psql(pg, database, """
        insert into app.platform_user_role(tenant_id,subject,platform_role,granted_by)
        values('demo','admin','ADMIN','s4-isolated-bootstrap')
        on conflict(tenant_id,subject,platform_role) do nothing;
        """)
    client_secret = environment(inspect(prefix+'-authority'))['AGENT_SECRET_IAM_GATEWAY_CLIENT']
    basic = 'Basic '+base64.b64encode(('gateway:'+client_secret).encode()).decode()
    role_refs = {'ADMIN': ('admin','AGENT_SECRET_USER_MGMT_SUBJECT_LOOKUP')}
    for role, item in keys.items():
        role_refs[role] = (item['subject'],'AGENT_SECRET_S4_DIRECTORY_'+role)
    out = {}
    for role, (subject, ref) in role_refs.items():
        http, token = isolated_http(pid,18086,'/oauth/token','POST',
                                   {'operation':'session','api_key_ref':'env:'+ref},{'Authorization':basic})
        if http != 200 or not token.get('access_token'):
            raise RuntimeError('Real authority did not issue portal session for '+role+' HTTP '+str(http))
        out[role] = {'subject':subject,'token':token['access_token']}
    grant_path = '/admin/platform-authorization/grants'
    for role in roles:
        if role not in out:
            continue
        body = {'subject':out[role]['subject'],'role':role,'appScope':['r22e2-real-v2','satellite-planning-assistant']}
        status, grant = isolated_http(pid,18090,grant_path,'PUT',body,
                                      {'Authorization':'Bearer '+out['ADMIN']['token']})
        if status != 200:
            raise RuntimeError('isolated role grant failed: '+role+' HTTP '+str(status)+' '+str(grant.get('code')))
    (root/'identities.json').write_text(json.dumps(out))
    model_name = psql(pg,database,"select model_name from model.entry where id=1;")
    assert model_name and len(model_name.splitlines())==1
    (root/'runtime-config.json').write_text(json.dumps({'modelName':model_name,'database':database,
                                                       'revision':revision,'isolation':'separate PG and Redis namespace'}))
    print('Isolated IAM roles:', ','.join(out), 'model entry:', model_name, flush=True)


def cleanup():
    if not namespace_marker.is_file() or namespace_marker.is_symlink() or namespace_marker.read_text() != candidate:
        print('No matching private S4 namespace to clean', flush=True)
        return
    problems = []
    # Revoke before stopping the namespace so all temporary directory keys can be audited.
    identities_path = root/'identities.json'
    if identities_path.exists() and (root/'namespace.pid').exists():
        pid = int((root/'namespace.pid').read_text())
        jwks, _ = isolated_http(pid,18086,'/.well-known/jwks.json')
        print('Isolated authority JWKS HTTP', jwks, flush=True)
        for role, entry in json.loads(identities_path.read_text()).items():
            try:
                status, _ = isolated_http(pid,18086,'/oauth/session/revoke','POST',
                                          extra={'Authorization':'Bearer '+entry['token']})
                print('Isolated portal session revoked:', role, status, flush=True)
                if status not in (200, 401):
                    problems.append('isolated portal session revoke '+role)
            except subprocess.CalledProcessError:
                problems.append('isolated portal session revoke '+role)
    for name in ('identities.json','runtime-config.json'):
        path = root/name
        if path.exists():
            path.unlink()
    try:
        revoke_keys()
    except Exception:
        problems.append('user-mgmt temporary key revocation')
    for service in (*reversed(services),'redis','pg'):
        name = prefix+'-'+service
        if docker('ps','-a','--filter','name=^/'+name+'$','--format','{{.Names}}'):
            labels = inspect(name)['Config']['Labels'] or {}
            if labels.get(owner_label) != owner:
                problems.append('namespace owner differs for ' + name)
                continue
            try:
                docker('rm','-fv',name)
            except subprocess.CalledProcessError:
                problems.append(name)
    pid_state = root/'relay-pids'
    if pid_state.exists():
        for line in pid_state.read_text().splitlines():
            number = int(line)
            try:
                command = (Path('/proc')/line/'cmdline').read_bytes().split(b'\0')
                if str(script).encode() in command and str(root).encode() in command and (
                        b'host-relay' in command or b'isolated-relay' in command):
                    os.kill(number,signal.SIGTERM)
            except FileNotFoundError:
                pass
        pid_state.unlink()
    for filename in ('authority-s4.pem', 'p19070.sock', 'p18990.sock', 'namespace.pid'):
        path = root/filename
        if path.exists():
            path.unlink()
    vault = root/'vault'
    if vault.is_dir() and not vault.is_symlink() and vault.resolve().parent == root:
        shutil.rmtree(str(vault))
    if problems:
        raise RuntimeError(', '.join(problems))
    if docker('ps','-a','--filter','name=^/'+prefix+'-','--format','{{.Names}}'):
        raise RuntimeError('isolated containers still exist')
    namespace_marker.unlink()
    print('Isolated services, database, and directory keys cleaned',flush=True)


if __name__ == '__main__':
    os.umask(0o077)
    try:
        if action == 'start':
            if docker('ps','-a','--filter','name=^/'+prefix+'-','--format','{{.Names}}'):
                raise RuntimeError('the candidate already has an isolated stack')
            root.mkdir(mode=0o700, parents=True, exist_ok=False)
            namespace_marker.write_text(candidate)
            try:
                start()
            except Exception:
                cleanup()
                raise
        elif action == 'cleanup':
            cleanup()
        elif action in ('host-relay','isolated-relay'):
            relay(action.split('-')[0])
        else:
            raise ValueError('unknown operation')
    except subprocess.CalledProcessError as error:
        print('Sandbox command failed: command family {} exit {}'.format(error.cmd[0],error.returncode),flush=True)
        raise SystemExit(error.returncode)
