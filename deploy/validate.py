"""Offline safety + official Kubernetes v1.33.0 API schema checks. Never reads kubeconfig."""
from pathlib import Path
import copy
import argparse
import hashlib
import json
import yaml
from jsonschema import Draft4Validator

ROOT = Path(__file__).resolve().parent
SCHEMA = ROOT.parent / '.tools/deployment/kubernetes-v1.33.0-swagger.json'
BACKENDS = {'platform-gateway', 'app-management', 'engine-runtime', 'skill-registry', 'model-gateway', 'monitor'}


def require(value, message):
    if not value:
        raise ValueError(message)


def check_workload(doc):
    pod = doc['spec']['template']['spec']
    require(pod['automountServiceAccountToken'] is False, 'service account token must be disabled')
    require(pod['securityContext']['runAsNonRoot'] is True, 'non-root pod required')
    for container in pod['containers']:
        security = container['securityContext']
        require(security['readOnlyRootFilesystem'] is True, 'read-only rootfs required')
        require(security['allowPrivilegeEscalation'] is False, 'privilege escalation forbidden')
        require('ALL' in security['capabilities']['drop'], 'capabilities must be dropped')
        require(container['resources']['limits'].get('memory'), 'memory limit required')
        require(container.get('readinessProbe') and container.get('livenessProbe'), 'probes required')
        if container['name'] in BACKENDS:
            env = {x['name']: x.get('value') for x in container['env']}
            require(env.get('AGENT_DEV_AUTH') == 'false', 'dev-auth must be false')
            require(all(x in env['JAVA_TOOL_OPTIONS'] for x in ['+UseZGC', '+ZGenerational', 'MaxRAMPercentage=70', 'MaxDirectMemorySize=128m']), 'JVM memory policy missing')
            require(env.get('SERVER_SHUTDOWN') == 'graceful', 'graceful shutdown required')
            require(pod['terminationGracePeriodSeconds'] >= 60, 'termination grace must allow Spring shutdown')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, help='Validate an offline kustomize rendered candidate instead of source manifests')
    args = parser.parse_args()
    require(hashlib.sha256(SCHEMA.read_bytes()).hexdigest() == '2af5abf98554af1599e516f31f7f1698085e6faa12b816401f569c097b9f7ef6', 'official API schema checksum mismatch')
    definitions = json.loads(SCHEMA.read_text(encoding='utf-8'))['definitions']
    # Swagger v2 represents Kubernetes IntOrString as a string plus this format;
    # JSON Schema needs the documented union explicitly (ports and rollingUpdate).
    def normalize(node):
        if isinstance(node, dict):
            if node.get('format') == 'int-or-string':
                node['type'] = ['integer', 'string']
            for child in node.values():
                normalize(child)
        elif isinstance(node, list):
            for child in node:
                normalize(child)
    normalize(definitions)
    documents = []
    for path in ([args.manifest] if args.manifest else [ROOT / 'k8s/base.yaml', ROOT / 'k8s/ops.yaml']):
        documents.extend(yaml.safe_load_all(path.read_text(encoding='utf-8')))
    workloads = {}
    for doc in documents:
        matches = [key for key, definition in definitions.items() if {'group': doc['apiVersion'].split('/')[0] if '/' in doc['apiVersion'] else '', 'version': doc['apiVersion'].split('/')[-1], 'kind': doc['kind']} in definition.get('x-kubernetes-group-version-kind', [])]
        require(len(matches) == 1, f'official schema missing/ambiguous: {doc["kind"]}')
        schema = {'$ref': '#/definitions/' + matches[0], 'definitions': definitions}
        errors = list(Draft4Validator(schema).iter_errors(doc))
        require(not errors, '\n'.join(str(error) for error in errors))
        if doc['kind'] == 'Deployment':
            check_workload(doc)
            workloads[doc['metadata']['name']] = doc
        if doc['kind'] == 'Service':
            require(doc['spec']['type'] == 'ClusterIP', 'no public Kubernetes Service allowed')
    require(set(workloads) == BACKENDS | {'frontend', 'otel-collector', 'prometheus', 'grafana', 'loki', 'tempo'}, '6 backends + frontend + all 5 ops components required')
    require(workloads['engine-runtime']['spec']['replicas'] >= 2, 'engine requires >= 2 replicas')
    namespaces = {doc['metadata']['name'] for doc in documents if doc['kind'] == 'Namespace'}
    require(namespaces == {'agent-platform-v12', 'agent-platform-v12-ops'}, 'dedicated namespaces required')
    require(any(doc['kind'] == 'ConfigMap' and doc.get('data', {}).get('AGENT_RELEASE_GATE') == 'BLOCKED_IAM_SCOPE_ACCEPTANCE' for doc in documents), 'default release must stay blocked')
    compose = yaml.safe_load((ROOT / 'compose.yaml').read_text(encoding='utf-8'))
    for name, service in compose['services'].items():
        require(service['read_only'] is True and service['user'] == '10001:10001', f'Compose hardening missing: {name}')
        require(':?' in service['image'], f'explicit release image required: {name}')
        for port in service.get('ports', []):
            require(port.startswith('127.0.0.1:'), 'published Compose ports must be loopback only')
    require(compose['services']['engine-runtime']['deploy']['replicas'] >= 2, 'Compose engine requires >=2 replicas')
    for name in ['otel-collector', 'prometheus', 'grafana', 'loki', 'tempo']:
        require(compose['services'][name]['profiles'] == ['ops'], 'ops profile required')
    # Negative security regressions: the check must reject all these mutations.
    for mutation in ['rootfs', 'dev-auth', 'memory']:
        bad = copy.deepcopy(workloads['engine-runtime'])
        c = bad['spec']['template']['spec']['containers'][0]
        if mutation == 'rootfs':
            c['securityContext']['readOnlyRootFilesystem'] = False
        elif mutation == 'dev-auth':
            next(v for v in c['env'] if v['name'] == 'AGENT_DEV_AUTH')['value'] = 'true'
        else:
            del c['resources']['limits']['memory']
        try:
            check_workload(bad)
        except (ValueError, KeyError):
            continue
        raise ValueError(f'negative test accepted {mutation}')
    for path in (ROOT / 'ops').glob('*.yaml'):
        yaml.safe_load(path.read_text(encoding='utf-8'))
    print(f'PASS: {len(documents)} Kubernetes objects match official v1.33.0 API schema; 12 workloads hardened; Compose policy; 3 negative safety tests.')
    print('This checks offline API structure and safety only; runtime/cluster acceptance is still pending. See deployment evidence for native component checks.')


if __name__ == '__main__':
    main()
