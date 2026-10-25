async page=>{await page.getByRole('button',{name:'运行到结束',exact:true}).click();await page.getByText('SUCCEEDED',{exact:true}).first().waitFor({timeout:20000});return {completed:true};}
