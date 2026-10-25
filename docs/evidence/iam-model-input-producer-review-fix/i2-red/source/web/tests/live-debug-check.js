async page => {
 await page.getByRole('link',{name:'调试',exact:true}).click();await page.getByRole('button',{name:'开始调试'}).waitFor();const response=page.waitForResponse(r=>r.url().endsWith('/admin/debug-runs')&&r.request().method()==='POST');await page.getByRole('button',{name:'开始调试'}).click();const data=await (await response).json();return {code:data.code,data:data.data,message:data.message};
}
