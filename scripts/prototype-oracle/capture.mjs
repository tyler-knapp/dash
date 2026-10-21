// Runs the approved prototype (reference/prototype.html) in headless Chromium on the kit's
// sample data, with the clock frozen, and records what its Budget view and summary strip show
// for a set of periods. The JSON goes to backend/src/test/resources/prototype-oracle/, where
// BudgetMatchesPrototypeTest checks /api/summary against it.
//
//   cd scripts/prototype-oracle && npm install && npm run capture
//   (set CHROMIUM_PATH if Playwright's bundled browser isn't installed)
import { chromium } from 'playwright-core'
import { readFileSync, readdirSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..')
const sample = join(root, 'reference', 'sample-data')
const TODAY = '2026-10-21'
const out = join(root, 'backend', 'src', 'test', 'resources', 'prototype-oracle', `budget-${TODAY}.json`)

const months = Object.fromEntries(
  readdirSync(sample).filter((f) => /^\d{4}-\d{2}\.json$/.test(f)).sort()
    .map((f) => [f.slice(0, 7), JSON.parse(readFileSync(join(sample, f), 'utf8'))]),
)
const budgets = JSON.parse(readFileSync(join(sample, 'budgets.json'), 'utf8'))
const balances = JSON.parse(readFileSync(join(sample, 'balances.json'), 'utf8'))

// Stand-in for the artifact runtime's claude.use("db"), serving the sample files read-only.
const fakeRuntime = `<script>
const __M=${JSON.stringify(months)}, __B=${JSON.stringify(budgets)}, __BAL=${JSON.stringify(balances)};
window.claude={use:async()=>({
  collection:()=>({onSnapshot:(cb)=>cb({docs:Object.entries(__M).map(([id,v])=>({id,data:()=>v}))})}),
  doc:(p)=>({onSnapshot:(cb)=>cb({exists:true,data:()=>p==='config/budgets'?__B:__BAL}),set:async()=>{}})
})};
</script>`
const html = fakeRuntime + readFileSync(join(root, 'reference', 'prototype.html'), 'utf8')

const SCENARIOS = [
  ['month-current', { mode: 'month', anchor: '2026-10' }],
  ['month-past', { mode: 'month', anchor: '2026-09' }],
  ['month-july-version', { mode: 'month', anchor: '2026-07' }],
  ['month-june', { mode: 'month', anchor: '2026-06' }],
  ['quarter-past', { mode: 'quarter', anchor: '2026-08' }],
  ['quarter-current', { mode: 'quarter', anchor: '2026-10' }],
  ['ytd-current', { mode: 'ytd', anchor: '2026-10' }],
  ['year-current', { mode: 'year', anchor: '2026-10' }],
  ['year-past-empty', { mode: 'year', anchor: '2025-10' }],
  ['custom-may-oct', { mode: 'custom', anchor: '2026-10', from: '2026-05', to: '2026-10' }],
  ['custom-reversed', { mode: 'custom', anchor: '2026-10', from: '2026-09', to: '2026-02' }],
]

const browser = await chromium.launch(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {})
const ctx = await browser.newContext({ timezoneId: 'America/New_York', viewport: { width: 980, height: 900 } })
const result = { today: TODAY, source: 'reference/prototype.html', scenarios: {} }

for (const [name, p] of SCENARIOS) {
  const page = await ctx.newPage()
  await page.clock.setFixedTime(new Date(`${TODAY}T12:00:00-04:00`))
  const period = { mode: p.mode, anchor: p.anchor, from: p.from ?? '2026-05', to: p.to ?? '2026-10' }
  await page.addInitScript((v) => localStorage.setItem('pf-view', JSON.stringify({ period: v })), period)
  await page.route('http://proto.local/', (r) => r.fulfill({ contentType: 'text/html; charset=utf-8', body: html }))
  await page.goto('http://proto.local/')
  await page.waitForSelector('.cat')
  // Expand every category to read subcategory totals.
  const names = await page.locator('.cat-name').allInnerTexts()
  for (let i = 0; i < names.length; i++) await page.locator('.cat-name').nth(i).click()
  const data = await page.evaluate(() => {
    const t = (sel, el = document) => el.querySelector(sel)?.textContent.trim() ?? null
    return {
      months: periodMonths(), // the prototype's own top-level function
      title: t('#pTitle'),
      sub: t('#pSub'),
      summary: { income: t('#sIncome'), spent: t('#sSpent'), budgeted: t('#sBudget'), net: t('#sNet') },
      note: t('#budgetNote'),
      editable: !!document.querySelector('.budget-in'),
      categories: [...document.querySelectorAll('.cat')].map((c) => ({
        name: c.querySelector('.cat-name').textContent.replace('▶', '').trim(),
        status: [...c.querySelector('.state').classList].find((k) => k !== 'state'),
        statusLabel: t('.state', c),
        spent: t('.figs .spent', c),
        budget: c.querySelector('.budget-in')?.value ?? t('.figs .num:not(.spent)', c),
        barWidth: c.querySelector('.bar i').style.width,
        barClass: c.querySelector('.bar i').className,
        paceLeft: c.querySelector('.bar b')?.style.left ?? null,
        subs: [...c.querySelectorAll('.subrow')].map((r) => [r.children[0].textContent, r.children[1].textContent]),
      })),
    }
  })
  result.scenarios[name] = { period, ...data }
  await page.close()
}
await browser.close()
writeFileSync(out, JSON.stringify(result, null, 2) + '\n')
console.log('wrote', out, Object.keys(result.scenarios).length, 'scenarios')
