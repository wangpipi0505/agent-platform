async page => {
 const response=page.waitForResponse(r=>r.url().includes('/frontend_browser_template/versions/')&&r.url().endsWith('/publish'));page.once('dialog',d=>d.accept());await page.getByRole('button',{name:'发布所选版本'}).click();const result=await(await response).json();if(result.code!=='OK')throw new Error(JSON.stringify(result));return {templatePublished:true,version:1};
}
