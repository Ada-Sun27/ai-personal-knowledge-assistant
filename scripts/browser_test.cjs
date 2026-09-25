/* Browser acceptance test against a running demo stack. */
const path = require('node:path');
const assert = require('node:assert/strict');
const { createRequire } = require('node:module');
const requireWeb = createRequire(path.resolve(__dirname, '../web/package.json'));
const { chromium } = requireWeb('playwright');

(async () => {
  const browser = await chromium.launch({ headless: true, args: ['--no-sandbox'] });
  const page = await browser.newPage({ viewport: { width: 1440, height: 1100 } });
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  try {
    await page.goto(process.env.PLAYWRIGHT_BASE_URL || 'http://127.0.0.1:5173');
    await page.getByText('DEMO', {exact:true}).waitFor();
    await page.getByLabel('Document file').setInputFiles(path.resolve(__dirname, '../examples/refund-policy.md'));
    await page.getByRole('button', {name:'Upload and index'}).click();
    await page.getByRole('status').filter({hasText:'Indexed refund-policy.md'}).waitFor();
    await page.getByLabel('Question', {exact:true}).fill('What is the refund deadline?');
    await page.getByRole('button', {name:'Ask question'}).click();
    await page.getByRole('status').filter({hasText:'Answer saved.'}).waitFor();
    await page.getByRole('button', {name:'[S1]',exact:true}).click();
    assert.equal(await page.locator('#source-S1').evaluate(element => element.open), true);
    await page.getByLabel('Feedback comment').fill('Show a shorter summary.');
    await page.getByRole('button',{name:'👎 Needs work'}).click();
    await page.getByRole('status').filter({hasText:'Feedback saved'}).waitFor();
    await page.getByRole('button',{name:'Load history'}).click();
    await page.getByRole('button',{name:'What is the refund deadline?',exact:true}).waitFor();
    await page.getByRole('button',{name:'Insert example case'}).click();
    await page.getByRole('button',{name:'Run 8 configurations'}).click();
    await page.getByRole('status').filter({hasText:'Evaluation complete'}).waitFor();
    assert.equal(await page.locator('tbody tr').count(), 8);
    assert.deepEqual(errors, []);
    if (process.env.UI_SCREENSHOT) await page.screenshot({path:process.env.UI_SCREENSHOT,fullPage:true});
    console.log(JSON.stringify({passed:6,checks:['upload UI','streamed answer UI','citation navigation','feedback submission','negative review history','evaluation results UI']}));
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode=1; });
