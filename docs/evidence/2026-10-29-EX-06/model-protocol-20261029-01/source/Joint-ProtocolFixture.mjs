// 有界loopback协议探针；Model仅提供明确标识的模型协议桩，不伪造发布/评估/权限/数据库成功。
import http from 'node:http';
import {writeFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
const args=process.argv.slice(2),options={};
for(let i=0;i<args.length;i+=2){
  if(!['--ready','--nonce','--role','--model','--tools'].includes(args[i])||args[i+1]===undefined||Object.hasOwn(options,args[i]))throw new Error('INVALID_FIXTURE_ARGUMENTS');
  options[args[i]]=args[i+1];
}
const role=options['--role']??'Protocol';
if(!options['--ready']||!/^[a-f0-9]{32}$/.test(options['--nonce']??'')||!['Protocol','Model'].includes(role))throw new Error('INVALID_FIXTURE_ARGUMENTS');
const modelName=options['--model'],registeredTools=(options['--tools']??'').split(',');
if(role==='Model'){
  if(!/^[A-Za-z][A-Za-z0-9_.:-]{0,63}$/.test(modelName??'')||registeredTools.length>16||registeredTools.some(t=>!/^[A-Za-z][A-Za-z0-9_-]{0,63}$/.test(t))||new Set(registeredTools).size!==registeredTools.length||registeredTools.includes('health_probe'))throw new Error('INVALID_MODEL_FIXTURE_ARGUMENTS');
}else if(modelName!==undefined||options['--tools']!==undefined)throw new Error('INVALID_FIXTURE_ARGUMENTS');
const root=fileURLToPath(new URL('../../../',import.meta.url));
const allowed=path.resolve(root,'.local/acceptance')+path.sep;
const ready=path.resolve(options['--ready']);
if(!ready.toLowerCase().startsWith(allowed.toLowerCase()))throw new Error('INVALID_FIXTURE_OUTPUT');
let requests=0,redirectTargets=0;
const envelope=(response,status,code,data,message='protocol fixture')=>{
  if(response.destroyed)return;
  const body=JSON.stringify({code,message,data});
  response.writeHead(status,{'Content-Type':'application/json;charset=utf-8','Content-Length':Buffer.byteLength(body)});response.end(body);
};
const allowedTools=new Set(['health_probe',...registeredTools]);
const fault=(status,code)=>Object.assign(new Error(code),{status,code});
const modelJson=(response,status,value)=>{
  if(response.destroyed||response.writableEnded)return;
  const body=JSON.stringify(value);
  response.writeHead(status,{'Content-Type':'application/json;charset=utf-8','Content-Length':Buffer.byteLength(body),'Connection':'close'});response.end(body);
};
const modelError=(response,status,code)=>modelJson(response,status,{error:{code,type:'invalid_request_error',message:'EX06 model protocol fixture rejected request',param:null}});
// A single wall-clock body budget also bounds slow chunked inputs; no request content is logged.
function readModelJson(request){
  return new Promise((resolve,reject)=>{
    const declared=request.headers['content-length'];
    if(declared!==undefined&&(!/^\d+$/.test(declared)||Number(declared)>65536)){
      request.resume();reject(fault(413,'FIXTURE_REQUEST_TOO_LARGE'));return;
    }
    if(String(request.headers['content-type']??'').split(';')[0].trim().toLowerCase()!=='application/json'){
      request.resume();reject(fault(400,'FIXTURE_JSON_INVALID'));return;
    }
    let done=false,length=0;const chunks=[];
    const finish=(error,value)=>{
      if(done)return;done=true;clearTimeout(timer);
      if(error){request.resume();reject(error);}else resolve(value);
    };
    const timer=setTimeout(()=>finish(fault(408,'FIXTURE_REQUEST_TIMEOUT')),1500).unref();
    request.on('data',chunk=>{
      if(done)return;length+=chunk.length;
      if(length>65536)return finish(fault(413,'FIXTURE_REQUEST_TOO_LARGE'));
      chunks.push(chunk);
    });
    request.on('end',()=>{
      if(done)return;
      try{finish(null,JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(Buffer.concat(chunks))));}
      catch{finish(fault(400,'FIXTURE_JSON_INVALID'));}
    });
    request.on('error',()=>finish(fault(400,'FIXTURE_JSON_INVALID')));
  });
}
const object=value=>value!==null&&typeof value==='object'&&!Array.isArray(value);
function completion(body){
  if(!object(body)||typeof body.model!=='string')throw fault(400,'FIXTURE_CHAT_INVALID');
  if(body.model!==modelName)throw fault(404,'FIXTURE_MODEL_UNKNOWN');
  if(body.stream===true)throw fault(400,'FIXTURE_STREAM_UNSUPPORTED');
  if(body.stream!==undefined&&typeof body.stream!=='boolean')throw fault(400,'FIXTURE_CHAT_INVALID');
  if(!Array.isArray(body.messages)||body.messages.length<1||body.messages.length>32||body.messages.some(m=>
    !object(m)||!['system','user','assistant','tool'].includes(m.role)||
    !(typeof m.content==='string'||(m.role==='assistant'&&m.content===null))||
    (m.role!=='assistant'&&m.tool_calls!==undefined)||(m.role!=='tool'&&m.tool_call_id!==undefined)))throw fault(400,'FIXTURE_CHAT_INVALID');
  const offered=new Set();
  if(body.tools!==undefined){
    if(!Array.isArray(body.tools)||body.tools.length>17)throw fault(400,'FIXTURE_TOOL_INVALID');
    for(const tool of body.tools){
      const fn=tool?.function;
      if(!object(tool)||tool.type!=='function'||!object(fn)||typeof fn.name!=='string'||!object(fn.parameters)||fn.parameters.type!=='object'||offered.has(fn.name))throw fault(400,'FIXTURE_TOOL_INVALID');
      if(!allowedTools.has(fn.name))throw fault(400,'FIXTURE_TOOL_UNKNOWN');
      offered.add(fn.name);
    }
  }
  if(body.tool_choice!==undefined&&!['auto','none'].includes(body.tool_choice))throw fault(400,'FIXTURE_TOOL_INVALID');
  const user=body.messages.filter(m=>m.role==='user').at(-1)?.content;
  if(user===undefined)throw fault(400,'FIXTURE_CHAT_INVALID');
  let selected=null;
  if(user==='Call health_probe now. Do not answer with plain text.')selected='health_probe';
  else if(user.startsWith('EX06_TOOL:')){
    selected=user.slice('EX06_TOOL:'.length);
    if(!allowedTools.has(selected)||selected==='health_probe')throw fault(400,'FIXTURE_TOOL_UNKNOWN');
  }
  if(selected&&(!offered.has(selected)||body.tool_choice==='none'))throw fault(400,'FIXTURE_TOOL_NOT_OFFERED');
  const histories=body.messages.filter(m=>m.role==='tool'||m.tool_calls!==undefined);
  let calls=null;
  if(histories.length){
    const assistant=body.messages.at(-2),reply=body.messages.at(-1);
    const call=assistant?.tool_calls?.[0];let args;
    try{args=JSON.parse(call?.function?.arguments);JSON.parse(reply?.content);}catch{throw fault(400,'FIXTURE_TOOL_HISTORY_INVALID');}
    if(!selected||histories.length!==2||assistant.role!=='assistant'||reply.role!=='tool'||
      !Array.isArray(assistant.tool_calls)||assistant.tool_calls.length!==1||call?.type!=='function'||
      call.id!=='call_ex06_'+selected||reply.tool_call_id!==call.id||call.function.name!==selected||
      !object(args)||Object.keys(args).length!==0)throw fault(400,'FIXTURE_TOOL_HISTORY_INVALID');
  }else if(selected){
    calls=[{id:'call_ex06_'+selected,type:'function',function:{name:selected,arguments:'{}'}}];
  }
  const message={role:'assistant',content:calls?null:(user==='EX06_SAFETY'?'EX06_REFUSE':'EX06_OK')};
  if(calls)message.tool_calls=calls;
  return {id:'chatcmpl-ex06-fixture',object:'chat.completion',created:1,model:modelName,
    choices:[{index:0,message,finish_reason:calls?'tool_calls':'stop'}],
    usage:{prompt_tokens:3,completion_tokens:2,total_tokens:5}};
}
let stopping=false;
function stopFixture(){
  if(stopping)return;stopping=true;
  const timer=setTimeout(()=>server.closeAllConnections(),250).unref();
  server.close(()=>{clearTimeout(timer);process.exit(0);});
  server.closeIdleConnections();
}
async function handleModel(request,response,route){
  try{
    if(route==='/fixture/stop'&&request.method==='POST'){
      request.resume();
      if(request.headers['x-fixture-nonce']!==options['--nonce'])return modelError(response,403,'FIXTURE_STOP_DENIED');
      modelJson(response,200,{stopping:true,role:'Model'});stopFixture();return;
    }
    if(route==='/v1/models'&&request.method==='GET'){
      request.resume();return modelJson(response,200,{object:'list',data:[{id:modelName,object:'model',created:1,owned_by:'ex06-test-fixture'}]});
    }
    if(route==='/v1/chat/completions'&&request.method==='POST')return modelJson(response,200,completion(await readModelJson(request)));
    request.resume();modelError(response,404,'FIXTURE_ROUTE_NOT_FOUND');
  }catch(error){modelError(response,error.status??500,error.code??'FIXTURE_INTERNAL_ERROR');}
}

const server=http.createServer(async(request,response)=>{
  if(++requests>100){request.resume();if(role==='Model')modelError(response,429,'FIXTURE_LIMIT');else envelope(response,429,'FIXTURE_LIMIT',null);return;}
  let route;try{route=new URL(request.url,'http://127.0.0.1').pathname;}catch{request.resume();if(role==='Model')modelError(response,400,'FIXTURE_CHAT_INVALID');else envelope(response,400,'INVALID_ARGUMENT',null);return;}
  if(role==='Model')return handleModel(request,response,route);
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
server.maxConnections=16;server.headersTimeout=5000;server.requestTimeout=5000;server.keepAliveTimeout=1000;
server.on('connection',socket=>socket.setTimeout(10000,()=>socket.destroy()));
server.listen(0,'127.0.0.1',async()=>{
  try{await writeFile(ready,JSON.stringify({port:server.address().port,pid:process.pid,nonce:options['--nonce'],protocolFixtureOnly:true,role,...(role==='Model'?{model:modelName,tools:registeredTools}:{})}),{flag:'wx',encoding:'utf8'});}
  catch{server.closeAllConnections();server.close(()=>process.exit(2));}
});
setTimeout(stopFixture,60000).unref();
for(const signal of ['SIGINT','SIGTERM'])process.on(signal,stopFixture);
