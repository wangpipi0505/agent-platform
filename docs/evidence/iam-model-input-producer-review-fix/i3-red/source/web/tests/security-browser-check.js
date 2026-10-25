async page => {
 const result=await page.evaluate(()=>{const node=document.createElement('div');node.innerHTML=window.safeMarkdown('[unsafe](javascript:alert(1))\n<img src="https://outside/a.png" onerror="alert(1)"><script>alert(1)</script><iframe src="https://outside"></iframe>');return {script:!!node.querySelector('script,iframe,img'),handlers:!!node.querySelector('[onerror],[onclick]'),javascript:!!node.querySelector('[href^="javascript:"]')};});if(result.script||result.handlers||result.javascript)throw new Error(JSON.stringify(result));return result;
}
