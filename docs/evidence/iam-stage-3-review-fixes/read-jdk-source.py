from pathlib import Path
import zipfile,hashlib,json
z=Path('C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot/lib/src.zip');o=Path('docs/evidence/iam-stage-3-review-fixes');e=[]
with zipfile.ZipFile(z) as archive:
 for name,terms in [('java.base/java/lang/ThreadLocal.java',['public T get()','public void remove()','ThreadLocalMap getMap']),('java.base/java/util/concurrent/atomic/AtomicInteger.java',['boolean compareAndSet','void set(']),('java.base/java/util/concurrent/ScheduledThreadPoolExecutor.java',['scheduleAtFixedRate(','void delayedExecute','List<Runnable> shutdownNow'])]:
  data=archive.read(name);lines=data.decode().splitlines();snips=[]
  for term in terms:
   for i,line in enumerate(lines):
    if term in line:
     snips.append('\n'.join(f'{k+1}: {lines[k]}' for k in range(max(0,i-2),min(len(lines),i+20))))
     break
  text='\n\n'.join(snips);(o/(Path(name).stem+'-source-excerpts.txt')).write_text(text,encoding='utf8');print(name+'\n'+text)
  e.append({'archive':str(z),'entry':name,'sha256':hashlib.sha256(data).hexdigest()})
(o/'open-source-read.json').write_text(json.dumps(e,indent=2),encoding='utf8')
