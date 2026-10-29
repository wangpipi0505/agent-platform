// 有界loopback协议探针，不模拟本体发布、模型、权限或数据库成功。
import http from 'node:http';
import {writeFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
const args=process.argv.slice(2);
if(args.length!==4||args[0]!=='--ready'||args[2]!=='--nonce'||!/^[a-f0-9]{32}$/.test(args[3]))throw new Error('INVALID_FIXTURE_ARGUMENTS');
const root=fileURLToPath(new URL('../../../',import.meta.url));
const allowed=path.resolve(root,'.local/acceptance')+path.sep;
const ready=path.resolve(args[1]);
if(!ready.toLowerCase().startsWith(allowed.toLowerCase()))throw new Error('INVALID_FIXTURE_OUTPUT');
let requests=0,redirectTargets=0;
const envelope=(response,status,code,data,message='protocol fixture')=>{
  if(response.destroyed)return;
  const body=JSON.stringify({code,message,data});
  response.writeHead(status,{'Content-Type':'application/json;charset=utf-8','Content-Length':Buffer.byteLength(body)});response.end(body);
};
const server=http.createServer(async(request,response)=>{
  if(++requests>100){envelope(response,429,'FIXTURE_LIMIT',null);return;}
  const route=new URL(request.url,'http://127.0.0.1').pathname;
  if(route==='/protocol/ok')return envelope(response,200,'OK',{probe:'protocol-only'});
  if(route==='/protocol/denied')return envelope(response,403,'NO_PERMISSION',null,'synthetic-raw-value');
  if(route==='/protocol/malformed'){response.writeHead(200,{'Content-Type':'application/json'});response.end('{synthetic-raw-value');return;}
  if(route==='/protocol/error-envelope')return envelope(response,200,'INVALID_ARGUMENT',null,'synthetic-raw-value');
  if(route==='/protocol/redirect'){response.writeHead(302,{Location:'/protocol/redirect-target'});response.end();return;}
  if(route==='/protocol/redirect-target'){redirectTargets++;return envelope(response,200,'OK',null);}
  if(route==='/protocol/not-ready')return envelope(response,200,'OK',{status:'VALIDATING',artifacts:[]});
  if(route==='/protocol/state')return envelope(response,200,'OK',{redirectTargets});
  if(route==='/protocol/oversized')return envelope(response,200,'OK',{padding:'x'.repeat(4096)});
  if(route==='/protocol/delay'){setTimeout(()=>envelope(response,200,'OK',null),2500).unref();return;}
  if(route==='/protocol/auth')return envelope(response,200,'OK',{hasApiKey:Boolean(request.headers['x-api-key']),hasAuthorization:Boolean(request.headers.authorization)});
  if(route==='/protocol/echo'&&request.method==='POST'){
    let length=0;const chunks=[];
    try{for await(const chunk of request){length+=chunk.length;if(length>65536)throw new Error('LIMIT');chunks.push(chunk);}
      const value=JSON.parse(Buffer.concat(chunks).toString('utf8'));
      return envelope(response,200,'OK',{text:value.text});
    }catch{return envelope(response,400,'INVALID_ARGUMENT',null);}
  }
  envelope(response,404,'FIXTURE_ROUTE_NOT_FOUND',null);
});
server.on('connection',socket=>socket.setTimeout(10000,()=>socket.destroy()));
server.listen(0,'127.0.0.1',async()=>{
  try{await writeFile(ready,JSON.stringify({port:server.address().port,pid:process.pid,nonce:args[3],protocolFixtureOnly:true}),{flag:'wx',encoding:'utf8'});}
  catch{server.closeAllConnections();server.close(()=>process.exit(2));}
});
setTimeout(()=>{server.closeAllConnections();server.close(()=>process.exit(0));},60000).unref();
for(const signal of ['SIGINT','SIGTERM'])process.on(signal,()=>{server.closeAllConnections();server.close(()=>process.exit(0));});
