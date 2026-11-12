/** 用户业务源、流程和本体校验共用本端状态；审批之后仍按当前业务权限执行。 */
import seedText from './business-demo.seed.json?raw'
import type { PlanningArtifact, PlanningOntology, ToolCall } from './satellite-planning'

type Row = Record<string, unknown>
interface Actor { id: string; name: string; departmentId: string; roles: string[]; permissions: string[]; status: string }
interface Account { id: string; username: string; name: string; employeeNo: string; departmentId: string; status: string; roleIds: string[]; revision: number }
interface Role { id: string; name: string; status: string; privileged: boolean; conflictsWith: string[]; permissionCodes: string[] }
interface BusinessSeed { sceneVersion: string; clock: string; planningDate: string; classifications: Row[]; businessSystem: Row; departments: {id:string;name:string}[]; actors: Actor[]; accounts: Account[]; roles: Role[]; permissions: {id:string;code:string;name:string}[]; releaseRef: {space:string;spaceCode:string;releaseId:string;version:string;contentDigest:string}; ontology: {space:{name:string}; rules:{id:string;name:string;description:string}[]; instances:{className:string;stableId:string;values:Row}[]} }
export const governanceSeed = JSON.parse(seedText) as BusinessSeed
const copy = <T>(value: T): T => JSON.parse(JSON.stringify(value)) as T
interface WorkflowNode { id: string; label: string; status: string; permission?: string; result?: string; reason?: string }
interface RequestPlan { id: string; actorId: string; action: 'CREATE_ACCOUNT'|'ASSIGN_ROLES'|'DISABLE_ACCOUNT'; username: string; name: string; employeeNo: string; departmentId: string; roleIds: string[]; validityDays: number; expectedRevision: number; status: string; sourceRunId: string; conversationKey: string; createdAt: string; approvals: {actorId:string;stage:string;at:string}[]; resultAccountId?:string }
interface Conversation { requestId?: string; lastRunId?: string }
export interface GovernanceResponse { conversationId:string;runId:string;reply:string;status:string;toolCalls:ToolCall[];ontology:PlanningOntology|null;artifacts:PlanningArtifact[];promptSnapshot:string;responseStyle?:string;workflow:WorkflowNode[];businessChange?:Row;approval?:{runId:string;nodeId:string;status:string;summary:string;action:string};task?:{taskId:string;status:string;stages:string[];artifacts:PlanningArtifact[]} }
interface Pending { requestId: string; stage:'department'|'security'; response:GovernanceResponse }
export const governanceState = { actorId:'operator-a', actors:copy(governanceSeed.actors), accounts:copy(governanceSeed.accounts), requests:{} as Record<string,RequestPlan>, conversations:{} as Record<string,Conversation>, responses:{} as Record<string,GovernanceResponse>, pending:{} as Record<string,Pending>, audits:[] as Row[], sequence:0, permissionRevision:1 }
const storageKey='ap-business-governance-v1'
export function restoreGovernanceState(value:unknown) { if(value&&typeof value==='object') Object.assign(governanceState,value) }
try { if(typeof sessionStorage!=='undefined') { const saved=sessionStorage.getItem(storageKey);if(saved) restoreGovernanceState(JSON.parse(saved)) } } catch { /* 无可恢复状态时使用同版本种子。 */ }
function persist(){try{if(typeof sessionStorage!=='undefined')sessionStorage.setItem(storageKey,JSON.stringify(governanceState))}catch{/* 页面通用状态保存仍会报告容量问题。 */}}
const currentActor=()=>governanceState.actors.find(actor=>actor.id===governanceState.actorId)??governanceState.actors[0]
const at=()=>new Date().toISOString()
function audit(action:string,subjectId:string,detail:unknown){governanceState.audits.unshift({id:'um-audit-'+(++governanceState.sequence),action,subjectId,actorId:currentActor().id,createdAt:at(),detail});persist()}

const toolDefinitions = [
 ['um-identity-resolve','识别当前业务账号',true],['um-permissions-pull','拉取业务角色与权限',true],['um-user-query','查询用户与部门',true],['um-role-query','查询角色及互斥关系',true],['um-ontology-check','本体规则校验',true],['um-request-prepare','生成账号变更计划',true],['um-request-submit','提交账号申请',false],['um-approval-decide','审批账号申请',false],['um-account-create','创建业务账号',false],['um-role-assign','授予业务角色',false],['um-request-readback','读回账号与授权',true],['um-request-receipt','导出授权回执',true],
] as const
export const governanceSkills = toolDefinitions.map(([code,name,readonly])=>({code,name,description:name,kind:'HTTP',type:'HTTP',status:'PUBLISHED',currentVersion:1,version:1,bizCategories:['USER_GOVERNANCE'],businessSystemCode:'user-mgmt',accessPolicy:{visibility:'BUSINESS',viewRoles:['UM_OPERATOR','UM_APPROVER','UM_SECURITY','VIEWER'],configureRoles:['UM_OPERATOR','UM_APPROVER','UM_SECURITY'],invokeRoles:code==='um-approval-decide'?['UM_APPROVER','UM_SECURITY']:readonly?['UM_OPERATOR','UM_APPROVER','UM_SECURITY','VIEWER']:['UM_OPERATOR']},guard:{roles:[]},execution:{readonly,sideEffect:!readonly,idempotent:true,cancellable:false},readonly,sideEffect:!readonly,inputSchema:{type:'object',properties:{requestId:{type:'string'},accountId:{type:'string'},departmentId:{type:'string'}}},outputSchema:{type:'object'},endpoint:`mock://user-mgmt/${code}`,method:readonly?'GET':'POST'}))
export const governanceScenario={planningDate:governanceSeed.planningDate,clock:governanceSeed.clock,tools:governanceSkills,examples:[
 {id:'um-catalog',title:'业务账号与权限',message:'展示用户、部门、角色、权限及当前业务身份'},
 {id:'um-create',title:'员工账号开通',message:'为A部门新员工陈新开通账号 chen.xin，授予业务分析员角色，有效期30天'},
 {id:'um-rules',title:'本体规则校验',message:'检查当前申请的本体规则和权限'},
 {id:'um-submit',title:'提交账号申请',message:'提交当前账号开通申请'},
 {id:'um-result',title:'结果与审计',message:'查询刚才申请的执行结果和审计'},
 {id:'um-export',title:'下载授权回执',message:'导出当前申请的授权回执'},
 {id:'um-conflict',title:'互斥角色阻断',message:'为A部门陈新开通账号 conflict.user，同时授予业务分析员和业务审批员角色'},
 {id:'um-scope',title:'跨部门阻断',message:'为B部门新员工开通账号 cross.user，授予业务分析员角色'},
 {id:'um-admin',title:'高权限双重审批',message:'为A部门陈新开通账号 privileged.user，授予业务管理员角色，有效期7天'},
 {id:'um-self',title:'申请人不能自审批',message:'检查由申请人审批自己的申请是否允许'},
 {id:'um-revoke',title:'审批后权限变化',message:'撤销申请人的角色授予权限，再继续审批'},
 {id:'um-last-admin',title:'最后管理员保护',message:'停用业务账号 business.admin'},
]}
const flowNodes=[
 {id:'start',type:'START',name:'接收账号申请',next:'identity'},
 {id:'identity',type:'SKILL',name:'识别业务身份并拉取权限',skill:'um-permissions-pull',next:'policy'},
 {id:'policy',type:'SKILL',name:'本体前置规则校验',skill:'um-ontology-check',next:'prepare'},
 {id:'prepare',type:'SKILL',name:'生成变更计划',skill:'um-request-prepare',next:'department'},
 {id:'department',type:'APPROVAL',name:'部门负责人审批',next:'security',businessPolicy:{businessSystemCode:'user-mgmt',requiredPermissions:['um.request.approve'],roles:['UM_APPROVER'],checkTiming:'BEFORE_EXECUTION'}},
 {id:'security',type:'CONDITION',name:'高权限安全复核',expression:'request.privileged == true',nextTrue:'security-approval',nextFalse:'execute'},
 {id:'security-approval',type:'APPROVAL',name:'安全岗位复核',next:'execute',businessPolicy:{businessSystemCode:'user-mgmt',requiredPermissions:['um.security.approve'],roles:['UM_SECURITY'],checkTiming:'BEFORE_EXECUTION'}},
 {id:'execute',type:'SKILL',name:'重查权限并执行业务变更',skill:'um-account-create',next:'readback',businessPolicy:{businessSystemCode:'user-mgmt',requiredPermissions:['um.user.create','um.role.assign'],roles:['UM_OPERATOR'],semanticConditions:['um-rule-active','um-rule-permission','um-rule-revision'],checkTiming:'BEFORE_COMMIT'}},
 {id:'readback',type:'SKILL',name:'读回账号和角色',skill:'um-request-readback',next:'end'},
 {id:'end',type:'END',name:'输出授权回执'},
]
export const governanceApp={code:'user-governance-assistant',name:'账号开通与角色授权助手',displayName:'账号开通与角色授权助手',type:'FLOW',status:'PUBLISHED',domain:'用户管理',bizCategory:'USER_GOVERNANCE',tags:['账号开通','权限治理','流程'],description:'从业务权限拉取、本体规则到审批执行及结果读回的账号管理流程。',currentVersionNo:2,draftRev:3,draftId:'draft-user-governance-assistant-r3',createdAt:governanceSeed.clock,updatedAt:governanceSeed.clock,config:{modelBinding:{modelEntryId:'me-001'},promptSource:'DIRECT',systemPrompt:'根据业务系统的当前身份、角色、权限和部门范围处理账号开通。先拉取事实，再调用本体规则。生成变更计划后等待独立审批。审批恢复后重查发起人权限与目标修订，成功执行后读回业务账号和角色。缺事实或约束不满足时停止并说明原因。以Markdown输出流程、规则和结果，不以自然语言自述代替业务读回。',promptRefs:[],skillRefs:governanceSkills.map(tool=>({code:tool.code,version:1})),ontology:governanceSeed.releaseRef,guard:{roles:[]},requiredLabels:['PUBLIC'],flowRef:'flow-user-governance'},definition:{definitionVersion:'1.0',trigger:'CHAT',entry:'start',inputs:[{key:'message',type:'STRING',required:true,description:'账号开通与授权需求'}],outputs:[{key:'receipt',type:'JSON',description:'业务读回与审批回执'}],joins:[],executionRules:{parallel:false,maxParallel:1,cyclesForbidden:true},nodes:flowNodes,edges:flowNodes.flatMap(node=>{const row=node as typeof node&{next?:string;nextTrue?:string;nextFalse?:string};return [row.next,row.nextTrue,row.nextFalse].filter((id):id is string=>!!id).map(id=>({from:node.id,to:id}))})}}

function baseResponse(conversationId:string,runId:string,config:Row):GovernanceResponse{
 const selected=(config.ontology??{}) as Row
 const valid=String(selected.spaceCode??'')==='user-governance'||String(selected.releaseId??'').startsWith('rel-user-governance-')
 const ontology:PlanningOntology|null=selected.releaseId?{name:valid?governanceSeed.ontology.space.name:'所选本体',releaseRef:{spaceCode:String(selected.spaceCode??''),releaseId:String(selected.releaseId),version:String(selected.version??''),contentDigest:String(selected.contentDigest??'')},facts:valid?governanceSeed.ontology.instances.map(instance=>({objectType:instance.className,stableId:instance.stableId,values:copy(instance.values)})):[],relations:valid?['Account → belongsTo → Department','Account → assignedRole → Role → permits → Permission','ManagementRequest → ApprovalRecord → PolicyDecision']:[],decisions:[]}:null
 return {conversationId,runId,reply:'',status:'SUCCEEDED',toolCalls:[],ontology,artifacts:[],promptSnapshot:String(config.systemPrompt??governanceApp.config.systemPrompt),responseStyle:String(config.responseStyle??'detailed'),workflow:[]}
}
function call(result:GovernanceResponse,code:string,input:unknown,output:unknown,status='SUCCEEDED'){
 result.toolCalls.push({id:result.runId+'-call-'+(result.toolCalls.length+1),skillCode:code,name:governanceSkills.find(tool=>tool.code===code)?.name??code,input:copy(input),output:copy(output),status})
}
function actorSnapshot(actor:Actor){return {subjectId:actor.id,name:actor.name,departmentId:actor.departmentId,roles:actor.roles,permissions:actor.permissions,status:actor.status,sourceSystem:'user-mgmt',revision:governanceState.permissionRevision,observedAt:at()}}
function addSourceCalls(result:GovernanceResponse,actor:Actor){call(result,'um-identity-resolve',{sourceSystem:'user-mgmt'},{subjectId:actor.id,status:actor.status});call(result,'um-permissions-pull',{subjectId:actor.id},actorSnapshot(actor))}
function workflowFor(request:RequestPlan,status:string):WorkflowNode[]{
 const waiting=request.status==='WAITING_SECURITY_APPROVAL'?'security':'department'
 return [
  {id:'identity',label:'识别业务身份',status:'SUCCEEDED',result:governanceState.actors.find(actor=>actor.id===request.actorId)?.name},
  {id:'permissions',label:'拉取业务角色与权限',status:'SUCCEEDED',result:'业务源 user-mgmt · 权限修订 '+governanceState.permissionRevision},
  {id:'policy',label:'本体规则校验',status:status==='BLOCKED'?'BLOCKED':'SUCCEEDED'},
  {id:'prepare',label:'生成变更计划',status:status==='BLOCKED'?'PENDING':'SUCCEEDED'},
  {id:'department',label:'部门负责人审批',status:request.approvals.some(item=>item.stage==='department')?'SUCCEEDED':status==='WAITING_APPROVAL'&&waiting==='department'?'WAITING_APPROVAL':'PENDING',permission:'um.request.approve'},
  ...(request.roleIds.includes('ROLE_ADMIN')?[{id:'security',label:'安全岗位复核',status:request.approvals.some(item=>item.stage==='security')?'SUCCEEDED':status==='WAITING_APPROVAL'&&waiting==='security'?'WAITING_APPROVAL':'PENDING',permission:'um.security.approve'}]:[]),
  {id:'execute',label:'重查权限并执行业务变更',status:status==='SUCCEEDED'&&request.status==='SUCCEEDED'?'SUCCEEDED':status==='BLOCKED'?'BLOCKED':'PENDING'},
  {id:'readback',label:'读回账号与角色',status:request.status==='SUCCEEDED'?'SUCCEEDED':'PENDING'},
 ]
}
function checkRequest(result:GovernanceResponse,request:RequestPlan,stage:string){
 const actor=governanceState.actors.find(item=>item.id===request.actorId)
 const existing=governanceState.accounts.find(item=>item.username===request.username)
 const selectedRules: {ruleId:string;good:boolean;reason:string}[]=[]
 const add=(ruleId:string,good:boolean,reason:string)=>selectedRules.push({ruleId,good,reason})
 const matched=!!result.ontology&&result.ontology.releaseRef.spaceCode==='user-governance'
 add('um-rule-active',!!actor&&actor.status==='ACTIVE','业务主体 '+(actor?.name??request.actorId)+' / '+(actor?.status??'未登记'))
 add('um-rule-scope',!!actor&&actor.departmentId===request.departmentId,'操作人部门 '+(actor?.departmentId??'未知')+' / 目标部门 '+request.departmentId)
 const required=request.action==='DISABLE_ACCOUNT'?['um.user.disable']:request.action==='ASSIGN_ROLES'?['um.role.assign']:['um.user.create','um.role.assign']
 add('um-rule-permission',!!actor&&required.every(code=>actor.permissions.includes(code)),'实际写入前读取修订 '+governanceState.permissionRevision+'，所需权限：'+required.join('、'))
 add('um-rule-role-active',request.roleIds.every(id=>governanceSeed.roles.some(role=>role.id===id&&role.status==='ACTIVE')),'请求角色：'+request.roleIds.join('、'))
 const combined=[...new Set([...(existing?.roleIds??[]),...request.roleIds])]
 add('um-rule-conflict',!combined.some(id=>governanceSeed.roles.find(role=>role.id===id)?.conflictsWith.some(other=>combined.includes(other))),'分析员与审批员职责互斥；当前角色组合 '+combined.join('、'))
 if(request.action==='DISABLE_ACCOUNT'){
 const activeAdmins=governanceState.accounts.filter(item=>item.status==='ACTIVE'&&item.roleIds.includes('ROLE_ADMIN'))
 add('um-rule-last-admin',!(existing?.roleIds.includes('ROLE_ADMIN')&&activeAdmins.length<=1),'当前有效业务管理员 '+activeAdmins.length+' 人')
 }
 add('um-rule-revision',request.action==='CREATE_ACCOUNT'?!existing||existing.id===request.resultAccountId:!!existing&&existing.revision===request.expectedRevision,request.action==='CREATE_ACCOUNT'?(existing?'账号已存在，需核对业务意图':'账号名尚未占用'):'目标修订 '+existing?.revision+' / 计划修订 '+request.expectedRevision)
 const maxDays=result.ontology?.releaseRef.version==='v2'?7:30
 add('um-rule-revision',request.validityDays>0&&request.validityDays<=maxDays,'授权期限 '+request.validityDays+'天 / 本体 '+(result.ontology?.releaseRef.version??'未选择')+' 上限 '+maxDays+'天')
 if(result.ontology&&matched)result.ontology.decisions=selectedRules.map(rule=>({ruleId:rule.ruleId,name:governanceSeed.ontology.rules.find(item=>item.id===rule.ruleId)?.name??rule.ruleId,result:rule.good?'HOLDS':'NOT_HOLDS',reason:rule.reason}))
 call(result,'um-ontology-check',{requestId:request.id,stage,releaseRef:result.ontology?.releaseRef??null},{result:matched&&selectedRules.every(item=>item.good)?'HOLDS':'NOT_HOLDS',checks:selectedRules,sourceRef:{sourceSystem:'user-mgmt',permissionRevision:governanceState.permissionRevision},evaluatedAt:at()})
 return [...(!matched?['本流程需要关联用户与权限治理本体']:[]),...selectedRules.filter(rule=>!rule.good).map(rule=>rule.reason)]
}
function bindRequest(result:GovernanceResponse,request:RequestPlan){result.workflow=workflowFor(request,result.status);result.businessChange={...result.businessChange,requestId:request.id,action:request.action,target:request.username,department:governanceSeed.departments.find(item=>item.id===request.departmentId)?.name??request.departmentId,roles:request.roleIds.map(id=>governanceSeed.roles.find(role=>role.id===id)?.name??id),requester:request.actorId,validityDays:request.validityDays,status:request.status};}
function artifactsFor(request:RequestPlan,result:GovernanceResponse):PlanningArtifact[]{
 const account=governanceState.accounts.find(item=>item.id===request.resultAccountId)
 const data={requestId:request.id,status:request.status,account:account??null,approvals:request.approvals,ontology:result.ontology?.releaseRef,permissionRevision:governanceState.permissionRevision,workflow:result.workflow}
 return [{name:'账号授权回执.json',mediaType:'application/json',content:JSON.stringify(data,null,2)},{name:'账号授权清单.csv',mediaType:'text/csv',content:'\uFEFFrequestId,username,department,status,roles\r\n'+[request.id,request.username,request.departmentId,request.status,request.roleIds.join('|')].join(',')}].map((artifact,index)=>({...artifact,id:result.runId+'-um-file-'+index,size:new TextEncoder().encode(artifact.content).length,downloadUrl:'data:'+artifact.mediaType+';charset=utf-8,'+encodeURIComponent(artifact.content)}))
}
function finish(result:GovernanceResponse){if(result.responseStyle==='compact'&&result.status==='SUCCEEDED'&&result.businessChange){const change=result.businessChange;result.reply='### 账号申请摘要\n\n- 申请：`'+change.requestId+'`\n- 账号：`'+change.target+'`\n- 部门：'+change.department+'\n- 角色：'+(change.roles as string[]).join('、')+'\n- 状态：**'+change.status+'**\n\n'+(change.status==='DRAFT'?'请选择“提交账号申请”，再由独立业务审批人确认。':'流程和规则依据见结构化结果。')}governanceState.responses[result.runId]=copy(result);persist();return result}
export function governanceTurn(input:{appCode:string;conversationId:string;message:string;runId:string;config:Row;parameters?:Row}):GovernanceResponse{
 const {appCode,conversationId,message,runId,config}=input
 const key=appCode+':'+conversationId
 const conversation=governanceState.conversations[key]??=( {})
 const result=baseResponse(conversationId,runId,config)
 const actor=currentActor();addSourceCalls(result,actor)
 if(actor.status!=='ACTIVE'){result.status='BLOCKED';result.reply='当前业务账号已停用，不能查询或执行业务操作。请切换到有效业务身份。';return finish(result)}
 const parameters=input.parameters
 let request=governanceState.requests[String(parameters?.requestId??conversation.requestId??'')]
 if(/撤销.*权限|收回.*权限/.test(message)){
  const subject=governanceState.actors.find(item=>item.id===(request?.actorId??actor.id))!
  subject.permissions=subject.permissions.filter(code=>code!=='um.role.assign');governanceState.permissionRevision++;audit('BUSINESS_PERMISSION_REVOKE',subject.id,{permissionCode:'um.role.assign'})
  result.reply='### 业务权限已更新\n\n已撤销 **'+subject.name+'** 的 `um.role.assign`。\n\n审批恢复时会重新拉取业务权限并执行本体检查；已有批准不会绕过这次变化。'
 }else if(/自.*审批|申请人审批|申请人.*批准/.test(message)){
  result.status='BLOCKED';result.reply='### 审批职责检查\n\n**申请人不能审批自己的申请。**\n\n请切换到部门负责人李主任；涉及业务管理员角色时，还需安全复核员王安独立批准。'
  if(result.ontology)result.ontology.decisions=[{ruleId:'um-rule-no-self-approval',name:'申请审批职责分离',result:'NOT_HOLDS',reason:'申请人与审批人必须是不同业务主体'}]
 }else if(/展示|目录|列出|哪些|有哪些/.test(message)&&!/开通|创建|授予|停用/.test(message)){
  call(result,'um-user-query',{departmentId:actor.departmentId},{accounts:governanceState.accounts.filter(account=>account.departmentId===actor.departmentId),departments:governanceSeed.departments})
  call(result,'um-role-query',{}, {roles:governanceSeed.roles})
  result.reply='## 业务账号与权限目录\n\n当前业务身份：**'+actor.name+'**。来源：业务用户管理系统；权限修订 **'+governanceState.permissionRevision+'**。\n\n| 账号 | 姓名 | 部门 | 状态 | 角色 |\n|---|---|---|---|---|\n'+governanceState.accounts.filter(account=>account.departmentId===actor.departmentId).map(account=>'| '+[account.username,account.name,account.departmentId,account.status,account.roleIds.join('、')||'无'].join(' | ')+' |').join('\n')+'\n\n### 角色\n\n'+governanceSeed.roles.map(role=>'- **'+role.name+'**（`'+role.id+'`）：'+role.status+(role.privileged?'，需要安全复核':'')).join('\n')+'\n\n### 当前权限\n\n'+actor.permissions.map(code=>'`'+code+'`').join('、')+'\n\n可从“员工账号开通”开始生成计划。'
 }else if(/结果|审计|回执|导出|下载/.test(message)){
  if(!request){result.status='NEEDS_INPUT';result.reply='请先生成并提交一份账号申请。'}
  else{
   bindRequest(result,request)
   const account=governanceState.accounts.find(item=>item.id===request?.resultAccountId)
   call(result,'um-request-readback',{requestId:request.id},{request:copy(request),account:account??null,audits:governanceState.audits.filter(item=>item.subjectId===request!.id)})
   result.reply='## 账号申请结果\n\n- 申请：`'+request.id+'`\n- 状态：**'+request.status+'**\n- 目标账号：`'+request.username+'`\n- 审批记录：'+request.approvals.length+' 条\n\n'+(account?'业务读回：**'+account.name+' / '+account.status+'**，角色 `'+account.roleIds.join('、')+'`，修订 '+account.revision+'。':'尚未产生账号变更，等待必要审批或条件满足。')
   if(request.status==='SUCCEEDED'){result.artifacts=artifactsFor(request,result);result.task={taskId:request.id,status:'SUCCEEDED',stages:['PREPARED','APPROVED','EXECUTED','READBACK_VERIFIED'],artifacts:result.artifacts};call(result,'um-request-receipt',{requestId:request.id},{files:result.artifacts.map(file=>({id:file.id,name:file.name,bytes:file.size}))})}
  }
 }else if(/规则|本体|检查|校验/.test(message)&&!/开通|创建|停用/.test(message)){
  if(!request){result.reply='### 用户与权限治理规则\n\n'+governanceSeed.ontology.rules.map(rule=>'- **'+rule.name+'**：'+rule.description).join('\n')+'\n\n生成账号计划后，会结合当前业务权限和目标账号逐项判定。'}
  else{const failures=checkRequest(result,request,'BEFORE_EXECUTION');result.status=failures.length?'BLOCKED':'SUCCEEDED';bindRequest(result,request);result.reply='### 本体规则检查\n\n'+(failures.length?failures.map(reason=>'- '+reason).join('\n'):'当前账号、部门、角色、权限及目标状态满足申请条件。')}
 }else if(/提交|执行当前|确认这/.test(message)){
  if(!request){result.status='NEEDS_INPUT';result.reply='请先说明目标员工、部门及角色，生成账号变更计划。'}
  else if(request.status==='SUCCEEDED'){result.reply='申请 `'+request.id+'` 已执行完成；相同业务意图不会重复创建账号。';bindRequest(result,request)}
  else{
   const existing=Object.values(governanceState.pending).find(item=>item.requestId===request!.id)
   if(existing)return copy(existing.response)
   const failures=checkRequest(result,request,'BEFORE_APPROVAL')
   if(failures.length){request.status='BLOCKED';result.status='BLOCKED';result.reply='### 申请被规则阻断\n\n'+failures.map(reason=>'- '+reason).join('\n')}
   else{request.status='WAITING_DEPARTMENT_APPROVAL';result.status='WAITING_APPROVAL';result.approval={runId,nodeId:'um-department-approval',status:'PENDING',summary:'为 '+request.name+'（'+request.username+'）'+(request.action==='DISABLE_ACCOUNT'?'停用账号':'开通/授予 '+request.roleIds.join('、')),action:'USER_GOVERNANCE'};result.reply='## 申请已提交，等待部门审批\n\n申请 `'+request.id+'` 尚未产生业务写入。\n\n请在“业务身份”切换为 **李主任 · A部门负责人** 后批准。'+(request.roleIds.includes('ROLE_ADMIN')?'\n\n业务管理员角色还需要安全复核员王安独立复核。':'')+'\n\n批准后会重新读取申请人当前权限和目标状态，再执行业务变更。';governanceState.pending[runId]={requestId:request.id,stage:'department',response:copy(result)};call(result,'um-request-submit',{requestId:request.id},{status:request.status,approvalNodeId:result.approval.nodeId});audit('REQUEST_SUBMIT',request.id,{runId})}
   bindRequest(result,request);if(governanceState.pending[runId])governanceState.pending[runId].response=copy(result)
  }
 }else if(/开通|创建|授予|停用/.test(message)){
  const departmentId=parameters?String(parameters.departmentId??actor.departmentId):/B部门|数据应用部|dept-b/i.test(message)?'dept-b':'dept-a'
  const roleIds:string[]=parameters&&Array.isArray(parameters.roleIds)?parameters.roleIds.map(String):[];if(!parameters){if(/业务分析员|ROLE_ANALYST/i.test(message))roleIds.push('ROLE_ANALYST');if(/业务审批员|ROLE_APPROVER/i.test(message))roleIds.push('ROLE_APPROVER');if(/业务管理员|ROLE_ADMIN/i.test(message))roleIds.push('ROLE_ADMIN');if(!roleIds.length&&!/停用/.test(message))roleIds.push('ROLE_ANALYST')}
  const username=parameters?String(parameters.username??''):message.match(/(?:账号|用户名)\s*([a-z][a-z0-9._-]+)/i)?.[1]??(/停用/.test(message)?'business.admin':'chen.xin')
  const target=governanceState.accounts.find(account=>account.username===username)
  const action:RequestPlan['action']=parameters?parameters.action as RequestPlan['action']:/停用/.test(message)?'DISABLE_ACCOUNT':/授予/.test(message)&&!(/开通|创建/.test(message))&&target?'ASSIGN_ROLES':'CREATE_ACCOUNT'
  request={id:'UM-REQ-'+String(++governanceState.sequence).padStart(4,'0'),actorId:actor.id,action,username,name:parameters?String(action==='CREATE_ACCOUNT'?parameters.name??'':target?.name??''):target?.name??(/陈新/.test(message)?'陈新':'新业务员工'),employeeNo:parameters?String(action==='CREATE_ACCOUNT'?parameters.employeeNo??'':target?.employeeNo??''):target?.employeeNo??'EMP-'+String(300+governanceState.sequence),departmentId,roleIds:parameters&&action==='DISABLE_ACCOUNT'?target?.roleIds??[]:roleIds.length?roleIds:target?.roleIds??[],validityDays:parameters?Number(parameters.validityDays??7):Number(message.match(/(?:有效期|期限)\s*(\d+)\s*天/)?.[1]??30),expectedRevision:target?.revision??0,status:'DRAFT',sourceRunId:runId,conversationKey:key,createdAt:at(),approvals:[]}
  governanceState.requests[request.id]=request;conversation.requestId=request.id
  const failures=checkRequest(result,request,'BEFORE_EXECUTION')
  if(failures.length){request.status='BLOCKED';result.status='BLOCKED'}
  call(result,'um-request-prepare',{requestId:request.id},{action:request.action,target:request.username,departmentId:request.departmentId,roleIds:request.roleIds,status:request.status})
  bindRequest(result,request)
  result.reply='## 账号变更计划\n\n| 项目 | 内容 |\n|---|---|\n| 申请编号 | `'+request.id+'` |\n| 目标账号 | `'+request.username+'` |\n| 姓名 | '+request.name+' |\n| 部门 | '+(governanceSeed.departments.find(item=>item.id===departmentId)?.name??departmentId)+' |\n| 角色 | '+request.roleIds.map(id=>governanceSeed.roles.find(role=>role.id===id)?.name??id).join('、')+' |\n| 有效期 | '+request.validityDays+'天 |\n\n'+(failures.length?'### 规则阻断\n\n'+failures.map(reason=>'- '+reason).join('\n'):'规则检查通过。请选择 **提交账号申请**，再由独立业务审批人确认。')
  audit('REQUEST_PREPARE',request.id,{status:request.status,target:request.username})
 }else{result.status='NEEDS_INPUT';result.reply='可以查询业务账号和角色、为员工生成账号开通计划、提交审批、检查本体规则，或查询并下载授权回执。请选择上方业务示例。'}
 conversation.lastRunId=runId
 return finish(result)
}

/** Selected tools use typed inputs while sharing the same request, approvals and commit checks as chat. */
export function governanceSelectedTool(input:{appCode:string;conversationId:string;runId:string;config:Row},code:string,parameters:Row):GovernanceResponse{
 if(code==='um-approval-decide')throw Error('请通过当前申请的既有审批接口提交决定；不能直接调用技能绕过审批节点。')
 if(!governanceSkills.some(skill=>skill.code===code))throw Error('未找到用户治理技能 '+code)
 const actor=currentActor()
 const result=baseResponse(input.conversationId,input.runId,input.config)
 const blocked=(message:string,status='BLOCKED')=>{result.status=status;result.reply=message;return finish(result)}
 if(actor.status!=='ACTIVE')return blocked('当前业务账号已停用，请切换到有效业务身份。')
 if(parameters.releaseRef){
  const reference=parameters.releaseRef as Row
  const configured=input.config.ontology as Row|undefined
  if(!configured||['spaceCode','releaseId','version','contentDigest'].some(key=>reference[key]!==configured[key]))return blocked('releaseRef与当前应用所选本体正式版本不一致，请重新载入参数模板。')
 }
 if(code==='um-identity-resolve'){call(result,code,{},actorSnapshot(actor));result.reply='当前业务账号：'+actor.name+'（'+actor.id+'），部门 '+actor.departmentId+'。';return finish(result)}
 addSourceCalls(result,actor)
 if(code==='um-permissions-pull'){result.reply='业务源 user-mgmt 当前权限修订 '+governanceState.permissionRevision+'：\n\n角色：'+actor.roles.join('、')+'\n权限：'+actor.permissions.join('、');return finish(result)}
 const requiredPermission=code==='um-role-query'?'um.role.read':code==='um-request-prepare'||code==='um-request-submit'||code==='um-account-create'?'um.request.create':code==='um-role-assign'?'um.role.assign':code==='um-request-receipt'?'file.download':'um.user.read'
 if(!actor.permissions.includes(requiredPermission))return blocked('当前业务身份缺少 '+requiredPermission+'，请在业务源确认权限。')
 if(code==='um-user-query'||code==='um-role-query'){
  const department=String(parameters.departmentId??actor.departmentId)
  if(code==='um-user-query'&&department!==actor.departmentId)return blocked('当前业务身份不能查询其他部门的账号。')
  const keyword=String(parameters.keyword??'').trim().toLowerCase()
  const rows=code==='um-user-query'?governanceState.accounts.filter(account=>account.departmentId===department&&(!keyword||[account.id,account.username,account.name,account.employeeNo].some(value=>value.toLowerCase().includes(keyword)))):governanceSeed.roles.filter(role=>!keyword||[role.id,role.name].some(value=>value.toLowerCase().includes(keyword)))
  call(result,code,parameters,code==='um-user-query'?{accounts:copy(rows),departments:governanceSeed.departments.filter(item=>item.id===department)}:{roles:copy(rows)})
  result.reply=(code==='um-user-query'?'业务账号':'业务角色')+'查询完成，共 '+rows.length+' 条。\n\n```json\n'+JSON.stringify(rows,null,2)+'\n```';return finish(result)
 }
 if(code==='um-request-prepare'){
  const action=String(parameters.action??'CREATE_ACCOUNT')
  if(!['CREATE_ACCOUNT','ASSIGN_ROLES','DISABLE_ACCOUNT'].includes(action))return blocked('请选择有效的账号变更操作。','NEEDS_INPUT')
  if(!/^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/.test(String(parameters.username??'')))return blocked('请填写有效的业务账号名。','NEEDS_INPUT')
  if(parameters.departmentId!==undefined&&parameters.departmentId!==actor.departmentId)return blocked('当前业务身份不能为其他部门生成账号申请。')
  const target=governanceState.accounts.find(account=>account.username===parameters.username)
  if(action!=='CREATE_ACCOUNT'&&(!target||target.departmentId!==actor.departmentId))return blocked('请从当前业务部门选择要变更的账号。','NEEDS_INPUT')
  if(action==='CREATE_ACCOUNT'&&(!String(parameters.name??'').trim()||!String(parameters.employeeNo??'').trim()))return blocked('创建账号需要填写员工姓名和员工编号。','NEEDS_INPUT')
  if(action!=='DISABLE_ACCOUNT'&&(!Array.isArray(parameters.roleIds)||!parameters.roleIds.length||parameters.roleIds.some(id=>!governanceSeed.roles.some(role=>role.id===id&&role.status==='ACTIVE'))))return blocked('请从业务角色目录选择至少一个有效角色。','NEEDS_INPUT')
  if(!Number.isInteger(Number(parameters.validityDays??7))||Number(parameters.validityDays??7)<1||Number(parameters.validityDays??7)>30)return blocked('授权有效期必须为1至30天的整数。','NEEDS_INPUT')
  return governanceTurn({...input,message:'创建账号变更计划',parameters:{...parameters,action}})
 }
 const key=input.appCode+':'+input.conversationId
 const id=String(parameters.requestId??governanceState.conversations[key]?.requestId??'')
 const request=governanceState.requests[id]
 if(!request)return blocked('请先生成账号变更申请，并提供有效的 requestId。','NEEDS_INPUT')
 if(request.departmentId!==actor.departmentId)return blocked('该申请不属于当前业务身份的部门。')
 if(code==='um-request-receipt'&&request.status!=='SUCCEEDED')return blocked('申请尚未执行完成，当前没有可导出的授权回执；请先完成必要审批。','NEEDS_INPUT')
 if(code==='um-request-submit'||code==='um-account-create'||code==='um-role-assign'){
  if(actor.id!==request.actorId)return blocked('请由申请发起人的业务身份提交或执行当前申请。')
  if(request.status==='REJECTED')return blocked('该申请已拒绝；请重新生成变更计划。')
 }
 const account=governanceState.accounts.find(item=>item.username===request.username)
 if(parameters.accountId&&parameters.accountId!==account?.id)return blocked('accountId与当前申请目标账号不一致。')
 if(code==='um-account-create'||code==='um-role-assign'){
  if(code==='um-account-create'&&request.action!=='CREATE_ACCOUNT')return blocked('当前申请不是创建账号操作。')
  if(code==='um-role-assign'&&request.action==='DISABLE_ACCOUNT')return blocked('停用申请不能授予角色。')
  for(const field of ['username','name','employeeNo','departmentId'] as const)if(parameters[field]!==undefined&&parameters[field]!==request[field])return blocked(field+'与已冻结申请不一致，请重新生成计划。')
  if(parameters.roleIds&&(!Array.isArray(parameters.roleIds)||JSON.stringify([...parameters.roleIds].sort())!==JSON.stringify([...request.roleIds].sort())))return blocked('roleIds与已冻结申请不一致，请重新生成计划。')
 }
 if(code==='um-ontology-check'){
  const failures=checkRequest(result,request,'BEFORE_EXECUTION');result.status=failures.length?'BLOCKED':'SUCCEEDED';bindRequest(result,request);result.reply=failures.length?'规则校验未通过：\n'+failures.join('\n'):'当前申请的业务权限、部门范围、角色与目标状态满足规则。';return finish(result)
 }
 const response=governanceTurn({...input,message:code==='um-request-submit'||code==='um-account-create'||code==='um-role-assign'?'提交当前账号申请':'查询当前申请结果和回执',parameters:{requestId:request.id}})
 if((code==='um-account-create'||code==='um-role-assign')&&!response.toolCalls.some(tool=>tool.skillCode===code)){call(response,code,parameters,{requestId:request.id,status:request.status,executed:false,reason:request.status==='SUCCEEDED'?'批准计划已执行；本次仅返回既有结果':'须通过当前申请的必要审批；没有直接写入账号或角色'},response.status==='WAITING_APPROVAL'?'WAITING_APPROVAL':response.status);return finish(response)}
 return response
}

export function decideGovernanceRun(runId:string,approved:boolean):{response?:GovernanceResponse;conflict?:string}|undefined{
 const pending=governanceState.pending[runId]
 if(!pending)return governanceState.responses[runId]?.approval?{conflict:'该审批已处理，不能覆盖原决定。'}:undefined
 const request=governanceState.requests[pending.requestId]
 const result=copy(pending.response)
 const reviewer=currentActor()
 const permission=pending.stage==='department'?'um.request.approve':'um.security.approve'
 if(reviewer.status!=='ACTIVE'||!reviewer.permissions.includes(permission))return{conflict:'当前业务身份没有'+permission+'。请切换到对应审批岗位。'}
 if(reviewer.id===request.actorId)return{conflict:'申请人不得审批自己的请求。'}
 if(pending.stage==='department'&&reviewer.departmentId!==request.departmentId)return{conflict:'审批人不属于申请部门。'}
 if(pending.stage==='security'&&request.approvals.some(item=>item.actorId===reviewer.id))return{conflict:'高权限申请需要另一位独立安全复核人。'}
 call(result,'um-approval-decide',{requestId:request.id,stage:pending.stage,approved},{actorId:reviewer.id,permission,decidedAt:at()})
 if(!approved){request.status='REJECTED';result.status='REJECTED';result.approval!.status='REJECTED';result.reply='### 申请已拒绝\n\n`'+request.id+'` 未执行业务变更。';delete governanceState.pending[runId];audit('REQUEST_REJECT',request.id,{actorId:reviewer.id})}
 else{
  request.approvals.push({actorId:reviewer.id,stage:pending.stage,at:at()});audit('REQUEST_APPROVE',request.id,{stage:pending.stage,reviewer:reviewer.id})
  const failures=checkRequest(result,request,'BEFORE_COMMIT')
  if(failures.length){request.status='BLOCKED';result.status='BLOCKED';result.approval!.status='APPROVED';result.reply='### 审批后复核阻断\n\n审批意见已保存，但未进行业务写入：\n\n'+failures.map(reason=>'- '+reason).join('\n')+'\n\n请恢复有效业务权限或重新生成计划。';delete governanceState.pending[runId];audit('EXECUTION_BLOCKED',request.id,{failures})}
  else if(pending.stage==='department'&&request.roleIds.includes('ROLE_ADMIN')){request.status='WAITING_SECURITY_APPROVAL';result.status='WAITING_APPROVAL';result.approval={runId,nodeId:'um-security-approval',status:'PENDING',summary:'独立安全复核：'+request.username+' 的业务管理员授权',action:'USER_GOVERNANCE'};result.reply='### 部门审批已通过\n\n申请包含业务管理员角色，尚未写入。请切换到 **王安 · 安全复核员** 后完成第二次独立批准。';governanceState.pending[runId]={requestId:request.id,stage:'security',response:copy(result)}}
  else{
   let account=governanceState.accounts.find(item=>item.username===request.username)
   const before=account?copy(account):null
   if(request.action==='CREATE_ACCOUNT'){account={id:'biz-created-'+(++governanceState.sequence),username:request.username,name:request.name,employeeNo:request.employeeNo,departmentId:request.departmentId,status:'ACTIVE',roleIds:[...request.roleIds],revision:1};governanceState.accounts.push(account);call(result,'um-account-create',{requestId:request.id,username:request.username,departmentId:request.departmentId},{accountId:account.id,revision:account.revision})}
   else if(account){account.revision++;if(request.action==='DISABLE_ACCOUNT'){account.status='DISABLED'}else{account.roleIds=[...new Set([...account.roleIds,...request.roleIds])]}}
   if(account&&request.action!=='DISABLE_ACCOUNT')call(result,'um-role-assign',{requestId:request.id,accountId:account.id,roleIds:request.roleIds},{roleIds:account.roleIds,revision:account.revision})
   request.resultAccountId=account?.id;request.status='SUCCEEDED';result.status='SUCCEEDED';result.approval!.status='APPROVED';delete governanceState.pending[runId]
   call(result,'um-request-readback',{accountId:account?.id},{account:copy(account),matchesPlan:!!account&&account.username===request.username})
   if(result.ontology)result.ontology.decisions.push({ruleId:'um-rule-readback',name:'业务结果读回',result:'HOLDS',reason:'从业务账号集合重新查询，账号状态和授权角色与批准计划一致'})
   bindRequest(result,request);result.businessChange={...result.businessChange,before,after:copy(account)};result.artifacts=artifactsFor(request,result);result.task={taskId:request.id,status:'SUCCEEDED',stages:['PREPARED','APPROVED','EXECUTED','READBACK_VERIFIED'],artifacts:result.artifacts}
   result.reply='## 账号变更已完成\n\n- 申请：`'+request.id+'`\n- 账号：**'+account?.username+' / '+account?.name+'**\n- 部门：'+account?.departmentId+'\n- 状态：**'+account?.status+'**\n- 角色：'+account?.roleIds.join('、')+'\n- 业务修订：'+account?.revision+'\n\n部门审批'+(request.roleIds.includes('ROLE_ADMIN')?'及独立安全复核':'')+'已记录；执行前重新拉取权限，本体复核通过，业务结果已读回。\n\n可以下载授权回执或继续查询业务目录。';audit('BUSINESS_CHANGE_READBACK',request.id,{accountId:account?.id,before,after:account})
  }
 }
 bindRequest(result,request)
 if(governanceState.pending[runId])governanceState.pending[runId].response=copy(result)
 finish(result);return{response:result}
}
export function governanceRunNodes(runId:string){const result=governanceState.responses[runId];return(result?.workflow??[]).map(node=>({id:node.id,nodeDefId:node.id,label:node.label,status:node.status,duration:node.status==='SUCCEEDED'?100:0,output:node.result??node.reason??'',errorMessage:node.status==='BLOCKED'?result?.reply:undefined}))}
export function governanceRunEvents(runId:string){const response=governanceState.responses[runId];return response?[{type:'run.started',runId},...governanceRunNodes(runId).map(node=>({type:'node.status',runId,nodeId:node.id,status:node.status})),{type:response.status==='WAITING_APPROVAL'?'run.waiting':response.status==='SUCCEEDED'?'run.completed':'run.failed',runId,status:response.status}]:[]}
export function governanceBusinessRequest(path:string,method:string,body:unknown):{data?:unknown;status?:number;error?:string}|undefined{
 const input=(body??{}) as Row
 if(path==='/admin/business/context'&&method==='GET')return{data:{sceneVersion:governanceSeed.sceneVersion,currentActorId:governanceState.actorId,actors:copy(governanceState.actors),users:copy(governanceState.accounts),departments:governanceSeed.departments,roles:governanceSeed.roles,permissions:governanceSeed.permissions,requests:Object.values(governanceState.requests),audits:governanceState.audits,permissionRevision:governanceState.permissionRevision,businessSystem:governanceSeed.businessSystem}}
 if(path==='/admin/business/actor'&&method==='POST'){const actor=governanceState.actors.find(item=>item.id===input.actorId);if(!actor)return{status:404,error:'业务身份不存在'};governanceState.actorId=actor.id;persist();return{data:actorSnapshot(actor)}}
 if(path==='/admin/business/permissions'&&method==='POST'){const actor=governanceState.actors.find(item=>item.id===input.actorId);const code=String(input.permissionCode??'');if(!actor||!governanceSeed.permissions.some(permission=>permission.code===code))return{status:400,error:'请选择有效业务主体和权限'};actor.permissions=input.enabled===true?[...new Set([...actor.permissions,code])]:actor.permissions.filter(item=>item!==code);governanceState.permissionRevision++;audit('BUSINESS_PERMISSION_CHANGE',actor.id,{permission:code,enabled:input.enabled});return{data:actorSnapshot(actor)}}
 if(path==='/admin/business/reset'&&method==='POST'){Object.assign(governanceState,{actorId:'operator-a',actors:copy(governanceSeed.actors),accounts:copy(governanceSeed.accounts),requests:{},conversations:{},responses:{},pending:{},audits:[],sequence:0,permissionRevision:1});persist();return{data:{restored:true}}}
}
