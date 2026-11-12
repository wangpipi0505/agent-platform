import { expect, test, vi } from 'vitest'

async function demo() {
  vi.resetModules()
  const store = new Map<string,string>()
  const storage = {getItem:(key:string)=>store.get(key)??null,setItem:(key:string,value:string)=>store.set(key,value),removeItem:(key:string)=>store.delete(key)}
  vi.stubGlobal('sessionStorage',storage);vi.stubGlobal('localStorage',storage)
  const {mockTransport}=await import('../shared/mock-transport')
  const call=async(path:string,method='GET',body?:unknown)=>{
    const response=await mockTransport(path,{method,...(body===undefined?{}:{body:JSON.stringify(body)})})
    return {http:response.status,...await response.json()}
  }
  return {call}
}

test('exact skill schemas drive planning parameters and the stored run, with version and input rejection',async()=>{
  const {call}=await demo(),app='satellite-planning-assistant',conversationId='selected-ground'
  const menu=(await call(`/admin/apps/${app}/available-tools?versionNo=1&conversationId=${conversationId}`)).data
  const ground=menu.find((tool:{code:string})=>tool.code==='planning-ground-plan')
  const query=menu.find((tool:{code:string})=>tool.code==='planning-satellite-query')
  expect(query.inputSchema.properties).not.toHaveProperty('cloudLimit')
  const execute=(parameters:unknown,version=1)=>call(`/admin/apps/${app}/demo-chat`,'POST',{versionNo:1,conversationId,toolSelection:{code:ground.code,version},parameters})
  const success=await execute(ground.parameterTemplate)
  expect(success.http).toBe(200);expect(success.data.plan.windows.map((window:{id:string})=>window.id)).toEqual(['GD-W01'])
  const stored=(await call(`/admin/runs/${success.data.runId}`)).data
  expect(stored.inputs.parameters).toEqual(ground.parameterTemplate)
  expect(stored.versionRefs.skills['planning-ground-plan']).toBe(1)
  const constrained=await execute({...ground.parameterTemplate,cloudLimit:20})
  expect(constrained.data.status).toBe('NO_WINDOW');expect(constrained.data.plan.windows).toEqual([])
  expect((await execute({...ground.parameterTemplate,cloudLimit:101})).http).toBe(400)
  expect((await execute(ground.parameterTemplate,9)).http).toBe(409)
})

test('structured account fields flow through source permissions, independent approval and account readback',async()=>{
  const {call}=await demo(),app='user-governance-assistant',conversationId='selected-account'
  const menu=(await call(`/admin/apps/${app}/available-tools?versionNo=2&conversationId=${conversationId}`)).data
  const prepare=menu.find((tool:{code:string})=>tool.code==='um-request-prepare')
  const execute=(code:string,parameters:unknown)=>call(`/admin/apps/${app}/demo-chat`,'POST',{versionNo:2,conversationId,toolSelection:{code,version:1},parameters})
  const result=await execute(prepare.code,{...prepare.parameterTemplate,username:'structured.user',name:'结构化员工',employeeNo:'EMP-9001',validityDays:7})
  expect(result.http).toBe(200);expect(result.data.businessChange.target).toBe('structured.user')
  const requestId=result.data.businessChange.requestId
  const submitted=await execute('um-request-submit',{requestId})
  expect(submitted.data.status).toBe('WAITING_APPROVAL')
  expect((await call('/admin/business/context')).data.users.some((user:{username:string})=>user.username==='structured.user')).toBe(false)
  expect((await execute('um-approval-decide',{requestId,approved:true})).http).toBe(409)
  await call('/admin/business/actor','POST',{actorId:'manager-a'})
  const approved=await execute('um-approval-decide',{requestId,approved:true,reason:'部门岗位核对通过'})
  expect(approved.http).toBe(200);expect(approved.data.status).toBe('SUCCEEDED')
  expect((await call('/admin/business/context')).data.users.find((user:{username:string})=>user.username==='structured.user')).toMatchObject({name:'结构化员工',employeeNo:'EMP-9001',roleIds:['ROLE_ANALYST']})
})

test('file parsing can read a constraint-only artifact and provide the same file through download',async()=>{
  const {call}=await demo(),app='satellite-planning-assistant'
  const {registerMockArtifact}=await import('../shared/mock-artifacts')
  const artifact=await registerMockArtifact({name:'constraints.json',mediaType:'application/json',content:JSON.stringify({maxCloudPercent:18.5,onlySatelliteId:3150})})
  const execute=(code:string)=>call(`/admin/apps/${app}/demo-chat`,'POST',{conversationId:'selected-files',toolSelection:{code,version:1},parameters:{artifactId:artifact.artifactId}})
  const parsed=await execute('planning-file-parse')
  expect(parsed.http).toBe(200);expect(parsed.data.toolCalls[0].output).toEqual({maxCloudPercent:18.5,onlySatelliteId:3150})
  const downloaded=await execute('planning-file-download')
  expect(downloaded.http).toBe(200);expect(downloaded.data.artifacts[0].name).toBe('constraints.json')
})
