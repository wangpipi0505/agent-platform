async page=>{await page.getByText('断点与技能 Mock',{exact:true}).click();return {text:await page.locator('main').innerText()};}
