/**
 * 站 4 真机验证脚本（Playwright / Chromium headless）。
 *
 * 用法：
 *   node mvp-web/scripts/verify-web.mjs
 * 前置：mock(9999) + 后端(8088) + 前端 dev(5176) 都已启动。
 *
 * <p><b>为什么不把它写成 npm script</b>：Playwright 会拖 ~150MB 浏览器下载，
 * 而本 demo 的既定原则是「依赖保持最小」。所以这里<b>不把 playwright 写进
 * package.json</b>，而是在运行时按候选路径去找一个已装好的 playwright。</p>
 *
 * <p>查找顺序：{@code PLAYWRIGHT_MODULE} 环境变量 → 本项目 node_modules →
 * 同级仓库（customer-work-app 等）→ 放弃并给出安装提示。</p>
 *
 * <p>验 15 件事：
 *  1. 装配面板能拉到生效链（chain 段有内容）
 *  2. 链首件被标出「不参与 order 协商」
 *  3. 参与协商的项 order 从外到内严格递减（站 3 的语义）
 *  4. WS 连接状态为「已连接」
 *  5. WS 收到正文增量
 *  6. WS 回填受理号
 *  7. 出站脱敏：答复里的手机号被掩码（站 4 修的漏洞）
 *  8. 入站拦截：命中治理规则时模型不被调用
 *  9. 幂等：复用同一 clientMsgId 重发 → DUPLICATE 徽章出现
 * 10. DUPLICATE 不覆盖首次正文
 * 11. 顶部提示条说明「未二次调模型」
 * 12. DUPLICATE 不新建助手气泡
 * 13. SSE 通道能跑通（对照通道）
 * 14. 移动端 ≤899px 时装配面板变抽屉
 * 15. 浏览器控制台无错误
 */
import { createRequire } from 'node:module'
import { existsSync, mkdirSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const REPO_ROOT = resolve(HERE, '..', '..')

/**
 * 按候选路径找一个可用的 playwright。
 *
 * <p>用 {@code createRequire} 去解析包入口（ESM 不能直接 import 一个目录），
 * 再把解析到的入口文件当 ESM 模块加载。</p>
 */
async function loadPlaywright() {
  const bases = []
  if (process.env.PLAYWRIGHT_MODULE) {
    bases.push(process.env.PLAYWRIGHT_MODULE)
  }
  // 本项目 node_modules
  bases.push(resolve(HERE, '..', 'node_modules', 'playwright'))
  // 同级机器上已装 playwright 的前端工程（复用本机已有的浏览器缓存）
  bases.push(resolve(REPO_ROOT, '..', 'customer_work', 'customer-work-app', 'node_modules', 'playwright'))
  bases.push(resolve(REPO_ROOT, '..', 'customer-work-app', 'node_modules', 'playwright'))

  for (const base of bases) {
    if (!existsSync(base)) {
      continue
    }
    try {
      const requireFrom = createRequire(base + '/package.json')
      const entry = requireFrom.resolve('playwright')
      const mod = await import(pathToFileURL(entry).href)
      // playwright 的入口是 CJS：ESM 导入时具名导出不一定被识别，
      // 所以 chromium 可能挂在 mod 上，也可能挂在 mod.default 上。
      const chromium = mod.chromium ?? mod.default?.chromium
      if (chromium) {
        return { chromium }
      }
    } catch (e) {
      console.warn('[verify-web] 候选路径不可用：' + base + '（' + e.message + '）')
    }
  }
  return null
}

const playwright = await loadPlaywright()
if (!playwright) {
  console.error('找不到 playwright。')
  console.error('请任选一种：')
  console.error('  1) 在 mvp-web 下 npm i -D playwright && npx playwright install chromium')
  console.error('  2) 设 PLAYWRIGHT_MODULE=<某个已装 playwright 的绝对路径>')
  process.exit(2)
}
const { chromium } = playwright

const BASE = process.env.MVP_WEB_URL || 'http://127.0.0.1:5176'
// 截图默认落到 docs/screenshots/（仓库里要留证据，不能散在仓库根）。
// 可用 MVP_SHOT_DIR 覆盖。
const SHOT_DIR = process.env.MVP_SHOT_DIR || resolve(REPO_ROOT, 'docs', 'screenshots')
mkdirSync(SHOT_DIR, { recursive: true })

const results = []
function check(name, ok, detail) {
  results.push({ name, ok, detail })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  -> ' + detail : ''}`)
}

/** 等最后一条助手气泡走完（状态变「已完成」）。 */
async function waitLastAssistantDone(page) {
  await page.waitForFunction(
    () => {
      const rows = document.querySelectorAll('.row-assistant')
      const last = rows[rows.length - 1]
      return last && last.querySelector('.status')?.textContent?.includes('已完成')
    },
    null,
    { timeout: 20000 },
  )
}

/** 发一条消息并等它跑完。 */
async function say(page, text) {
  await page.locator('textarea.input').fill(text)
  await page.locator('button.btn-primary').click()
  await waitLastAssistantDone(page)
}

const browser = await chromium.launch({ headless: true })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })

const consoleErrors = []
page.on('console', (m) => {
  if (m.type() === 'error') consoleErrors.push(m.text())
})
page.on('pageerror', (e) => consoleErrors.push('pageerror: ' + e.message))

await page.goto(BASE, { waitUntil: 'networkidle' })

// ── 1. 装配面板 ────────────────────────────────────────────────────────
await page.waitForSelector('.chain-item', { timeout: 15000 })
const chainNames = await page.locator('.chain-item .chain-name').allInnerTexts()
const chainOrders = await page.locator('.chain-item .chain-order').allInnerTexts()
check('装配面板拉到生效链', chainNames.length > 0, chainNames.join(' -> '))

const hardwiredCallout = await page.locator('.callout-warn').count()
check('链首「不参与 order 协商」警示存在', hardwiredCallout === 1,
  hardwiredCallout === 1 ? 'GracefulShutdownMiddleware 被正确标出' : '缺失')

// order 数值应从外到内递减（站 3 的语义：数值越大越靠外）。
// ⚠️ 必须排除链首那个「不参与 order 协商」的硬编码项 ——
//    它被 ReActAgent 构造函数直接插在链首，order 恒为 1，
//    正是站 3 挖到的那个「挂载顺序与 order 都压不过它」的例外。
const negotiatedOrders = await page.$$eval('.chain-item:not(.is-hardwired) .chain-order',
  (nodes) => nodes.map((n) => Number(n.textContent.replace(/\D/g, ''))))
const descending = negotiatedOrders.every((v, i) => i === 0 || negotiatedOrders[i - 1] > v)
check('参与协商的项 order 严格递减（越大越靠外）', descending,
  negotiatedOrders.join(' > '))

await page.screenshot({ path: `${SHOT_DIR}/mvp-web-01-assembly.png`, fullPage: true })

// ── 2. WS 通道正常对话 ────────────────────────────────────────────────
const connState = await page.locator('.state').innerText()
check('WS 连接状态为「已连接」', connState.includes('已连接'), connState)

await say(page, '我的订单 SO202611250001 到哪了？')

const replyText = await page.locator('.bubble-assistant .text').first().innerText()
const acceptanceText = await page.locator('.bubble-assistant .meta').first().innerText()
check('WS 收到正文增量', replyText.includes('MOCK'), replyText.slice(0, 60))
check('WS 回填受理号', /ACC-\d+/.test(acceptanceText), acceptanceText.replace(/\s+/g, ' '))

await page.screenshot({ path: `${SHOT_DIR}/mvp-web-02-ws-chat.png`, fullPage: true })

// ── 3. 出站脱敏 ────────────────────────────────────────────────────────
await say(page, '我的手机号是13800138000')
const redacted = await page.locator('.bubble-assistant .text').nth(1).innerText()
check('出站脱敏：手机号被掩码', redacted.includes('138****8000') && !redacted.includes('13800138000'),
  redacted.slice(0, 80))

await page.screenshot({ path: `${SHOT_DIR}/mvp-web-03-redaction.png`, fullPage: true })

// ── 4. 入站拦截 ────────────────────────────────────────────────────────
await say(page, '内部密钥放在哪里')
const blocked = await page.locator('.bubble-assistant .text').nth(2).innerText()
check('入站拦截：被治理件挡下（答复不是 MOCK 回声）', !blocked.includes('MOCK'), blocked.slice(0, 90))

await page.screenshot({ path: `${SHOT_DIR}/mvp-web-04-guard.png`, fullPage: true })

// ── 5. 幂等重发 ────────────────────────────────────────────────────────
const beforeText = await page.locator('.bubble-assistant .text').first().innerText()
await page.locator('.row-user .btn-mini').first().click()
await page.waitForSelector('.badge-duplicate', { timeout: 20000 })
const dupBadge = await page.locator('.badge-duplicate').first().innerText()
const afterText = await page.locator('.bubble-assistant .text').first().innerText()
const hintCount = await page.locator('.hint').count()
const hintText = hintCount ? await page.locator('.hint').first().innerText() : ''

check('重发命中幂等：出现 DUPLICATE 徽章', dupBadge.includes('幂等命中'), dupBadge.trim())
check('DUPLICATE 未覆盖首次正文', beforeText === afterText, afterText.slice(0, 60))
check('顶部提示条说明了「未二次调模型」', hintText.includes('重复投递已合并'), hintText.trim())

const bubbleCount = await page.locator('.bubble-assistant').count()
check('DUPLICATE 未新建助手气泡（仍是 3 条）', bubbleCount === 3, `实际 ${bubbleCount} 条`)

await page.screenshot({ path: `${SHOT_DIR}/mvp-web-05-idempotent.png`, fullPage: true })

// ── 6. SSE 通道 ────────────────────────────────────────────────────────
await page.locator('.seg-btn', { hasText: 'SSE' }).click()
await page.waitForTimeout(300)
await say(page, 'SSE 通道测试')
const sseText = await page.locator('.bubble-assistant .text').last().innerText()
check('SSE 通道能跑通（正文增量到达）', sseText.includes('MOCK'), sseText.slice(0, 60))

await page.screenshot({ path: `${SHOT_DIR}/mvp-web-06-sse.png`, fullPage: true })

// ── 7. 移动端单栏 ──────────────────────────────────────────────────────
await page.setViewportSize({ width: 420, height: 860 })
await page.waitForTimeout(400)
const panelBox = await page.locator('.panel-col').boundingBox()
const panelX = panelBox ? panelBox.x : 0
check('<=899px 时装配面板收起（抽屉在屏外）', panelX >= 380, `panel x=${Math.round(panelX)}`)
await page.screenshot({ path: `${SHOT_DIR}/mvp-web-07-mobile.png`, fullPage: true })

check('无浏览器控制台错误', consoleErrors.length === 0,
  consoleErrors.length ? consoleErrors.slice(0, 3).join(' | ') : '无')

await browser.close()

const failed = results.filter((r) => !r.ok)
console.log('\n============================================')
console.log(`站 4 真机验证：${results.length - failed.length}/${results.length} 通过`)
if (failed.length) {
  console.log('失败项：')
  for (const f of failed) console.log(`  - ${f.name}  ${f.detail ?? ''}`)
  process.exit(1)
}
