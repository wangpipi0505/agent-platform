async page=>{return {panel:await page.locator('.el-collapse .panel').innerText(),advanced:await page.getByPlaceholder('已有真实调用标识').inputValue()};}
