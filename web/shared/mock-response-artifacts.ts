/** 将业务结果的文件内容登记成通用制品；聊天和当前运行详情使用同一下载记录。 */
import { registerMockArtifact } from './mock-artifacts'
import { mockExtensionState } from './mock-state-extensions'

interface ResultFile { id:string;name:string;mediaType:string;content:string;size:number;downloadUrl:string }
export async function materializeResponseArtifacts<T extends {runId:string;artifacts:ResultFile[];task?:{artifacts:ResultFile[]}}>(result:T):Promise<T>{
 const registry=mockExtensionState.artifactRefs??=( {})
 const files:ResultFile[]=[]
 for(const artifact of result.artifacts??[]){
  const sourceArtifactId=(artifact as ResultFile&{sourceArtifactId?:string}).sourceArtifactId??artifact.id
  const key=result.runId+':'+sourceArtifactId
  const reference=registry[key]??=await registerMockArtifact({name:artifact.name,mediaType:artifact.mediaType,content:artifact.content,runId:result.runId})
  files.push({...artifact,...reference,sourceArtifactId})
 }
 result.artifacts=files
 if(result.task)result.task.artifacts=files
 return result
}
