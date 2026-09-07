async page => {
  const pageErrors = []
  const consoleErrors = []
  page.on('pageerror', error => pageErrors.push(String(error?.message || error)))
  page.on('console', message => {
    if (message.type() === 'error') consoleErrors.push(message.text())
  })

  const pageUrl = page.url()
  const scenarioMatch = pageUrl.match(/[?&]w7Scenario=([^&#]+)/)
  const scenario = scenarioMatch ? decodeURIComponent(scenarioMatch[1]) : 'unknown'
  const readyScenario = scenario.replace(/-(?:cold|hot)$/, '')
  await page.waitForFunction(currentScenario => {
    if (currentScenario === 'login') {
      return Boolean(document.querySelector('#login-page input[placeholder="用户名"]'))
    }
    if (currentScenario === 'home') {
      return Boolean(document.querySelector('.dashboard-page'))
    }
    if (currentScenario === 'subscriptions') {
      return Boolean(document.querySelector('.subscription-page'))
    }
    if (currentScenario === 'settings') {
      return Boolean(document.querySelector('.config-page'))
    }
    if (currentScenario === 'player') {
      return Boolean(document.querySelector('.art-app'))
    }
    return false
  }, readyScenario)
  await page.waitForTimeout(500)

  const data = await page.evaluate(({scenario, pageErrors, consoleErrors}) => {
    const probe = window.__w7Probe || {}
    const unique = values => [...new Set(values.filter(Boolean))]
    return {
    scenario,
    cacheMode: /-(?:cold|hot)$/.test(scenario) ? scenario.endsWith('-cold') ? 'cold' : 'hot' : 'dynamic',
    url: location.href,
    readyMarker: scenario.startsWith('login') ? '#login-page'
      : scenario.startsWith('home') ? '.dashboard-page'
        : scenario.startsWith('subscriptions') ? '.subscription-page'
          : scenario.startsWith('settings') ? '.config-page' : '.art-app',
    readyAtMs: Number(performance.now().toFixed(3)),
    documentReadyState: document.readyState,
    visibilityState: document.visibilityState,
    uiText: document.body.innerText.slice(0, 480),
    pageErrors: unique([...(probe.pageErrors || []), ...pageErrors]),
    consoleErrors: unique([...(probe.consoleErrors || []), ...consoleErrors]),
    chunk404s: unique(probe.chunk404s || []),
    resourceEntries: performance.getEntriesByType('resource')
      .filter(entry => /\.(?:js|css)$/.test(new URL(entry.name).pathname))
      .map(entry => ({
        url: entry.name,
        initiatorType: entry.initiatorType,
        startTime: Number(entry.startTime.toFixed(3)),
        responseEnd: Number(entry.responseEnd.toFixed(3)),
        durationMs: Number(entry.duration.toFixed(3)),
        transferSize: entry.transferSize,
        encodedBodySize: entry.encodedBodySize,
        decodedBodySize: entry.decodedBodySize
      }))
    }
  }, {scenario, pageErrors, consoleErrors})

  await page.evaluate(async value => {
    const response = await fetch('/__w7/complete', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify(value)
    })
    if (!response.ok) throw new Error(await response.text())
  }, data)
  return {
    scenario: data.scenario,
    resourceCount: data.resourceEntries.length,
    consoleErrorCount: data.consoleErrors.length,
    pageErrorCount: data.pageErrors.length,
    chunk404Count: data.chunk404s.length
  }
}
