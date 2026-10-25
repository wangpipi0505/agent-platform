async page => {
 await page.getByRole('link',{name:'调试',exact:true}).click();await page.getByPlaceholder('输入 JSON').fill('{"stage2Count":7}');await page.getByText('断点与技能 Mock',{exact:true}).click();await page.locator('details .el-select').first().click();await page.getByRole('option',{name:'START',exact:true}).click();await page.keyboard.press('Escape');
 await page.getByRole('button',{name:'开始调试',exact:true}).click();await page.getByText('WAITING_STEP',{exact:true}).first().waitFor({timeout:20000});
 const result={breakpoint:true,paused:await page.locator('pre').first().textContent()};await page.screenshot({path:'../docs/evidence/frontend-live-breakpoint-2560.png',fullPage:true});return result;
}
