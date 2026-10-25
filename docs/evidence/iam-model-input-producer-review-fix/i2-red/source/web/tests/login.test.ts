import { it, expect, vi } from "vitest";
import { login } from "../shared/api";
import {useAuth} from '../shared/api';
import {createPinia,setActivePinia} from 'pinia';
it("login explains unavailable gateway instead of leaking JSON parser error", async () => {
  vi.stubGlobal("fetch", async () => new Response("", { status: 502 }));
  await expect(login()).rejects.toThrow("网关登录服务不可用");
  vi.unstubAllGlobals();
});
it('D4b late development login cannot mutate a later identity',async()=>{
 setActivePinia(createPinia());const auth=useAuth();let resolve!:(x:Response)=>void;
 vi.stubGlobal('fetch',()=>new Promise<Response>(r=>resolve=r));const pending=login();auth.invalidate(auth.generation);auth.token='new';auth.roles=['EDITOR'];auth.user={principalId:'new'};
 resolve(new Response(JSON.stringify({code:'OK',data:{token:'old',user:{roles:['ADMIN']}}})));
 await expect(pending).rejects.toMatchObject({name:'AbortError'});expect(auth.token).toBe('new');expect(auth.roles).toEqual(['EDITOR']);vi.unstubAllGlobals();
});
