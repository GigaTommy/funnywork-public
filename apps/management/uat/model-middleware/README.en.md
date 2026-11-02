# Model management and Harness page UAT tools

[简体中文](README.md) | **English**

Development acceptance tools importing production pages and client components with controlled mock APIs. They are excluded from production management routes and use no production services, public model calls, real accounts, or secrets.

The complete plan and manual installer/engine scenarios are in [MODEL_UAT_PLAN.md](../../../../services/business/MODEL_UAT_PLAN.md) and [MODEL_UAT_MANUAL.md](../../../../services/business/MODEL_UAT_MANUAL.md). Use the root README for environment preparation.

## Run automatic regression

From the repository root:

```sh
python3 scripts/checks/run-model-uat.py --output .local/uat/regression
```

On Windows, use `python` with the same parameters. Prepare both workspaces' dependencies, JDK, and Maven beforehand. The script defaults to separate H2 and a local mock upstream and removes inherited `FUNNYWORK_MODEL_TEST_DB_URL`. Run PostgreSQL multi-replica checks separately. Use `--skip-build` for diagnosis; its NOT_RUN entries remain incomplete acceptance items.

Outputs include `.local/uat/regression/regression-results.json`, selected Java test XML, and step logs. Failures return nonzero and preserve local logs; review and sanitize raw logs before sharing terminal output.

## Open isolated pages with real components

```sh
python3 scripts/checks/run-model-uat.py --mode serve
```

- Management: `http://127.0.0.1:19192/`
- Client model selection: `http://127.0.0.1:19192/client.html`

Uses source ModelMiddleware, ModelMiddlewareUsage, ModelSelect, and UI primitives. Mocks replace APIs, catalogs, and return values. The console selects basic/1000-ticket/unknown/empty/changed-access datasets, injects 401/403/409/503/slow responses, and changes language and appearance. API audits contain fictional identifiers and test settings. Electron login, chat, tools, and GPU execution are checked through their actual application paths.

## Replayable browser scripts

`browser-uat.mjs` accepts a Codex CUA Tab and operates real controls through that Tab's supported browser APIs. It requires an active CUA session; ordinary Node execution does not provide one. No additional browser automation dependency is required.

In Codex CUA, create the test Tab and inspect its state, then run these examples after replacing the repository path:

```javascript
var managementTab = await cua.createBrowserTab('iab', 'http://127.0.0.1:19192/', { visible: false })
```

```javascript
var runner = await import('file:///absolute/repository/apps/management/uat/model-middleware/browser-uat.mjs')
nodeRepl.write(await runner.runBrowserUat(managementTab, 'management', '/absolute/repository/.local/uat/browser', {
  ids: ['M01','M02','M03','M04','M05','M06','M07','M08','M09','M10','M11','M12']
}))
```

Run M13-401, M13-403, M13-503, M14, M15, M16, M17, and M18 in the second batch; U01–U07 in the third; and U08–U13, U14-401, U14-403, and V01 in the fourth. Pass a string array of individual IDs rather than a range literal such as “U01–U13”. The management lane requires explicit IDs and at most 12 cases per batch to fit the CUA execution window. The client lane can omit IDs to run 12 cases.

Open `/client.html` for the client lane, then run:

```javascript
nodeRepl.write(await runner.runBrowserUat(clientTab, 'client', '/absolute/repository/.local/uat/browser'))
```

Create `clientTab` first with `cua.createBrowserTab`. Add a different file-URL query when reimporting changed scripts to avoid ESM caching. Reruns can select IDs. Reports preserve attempts, latest results, plannedCases, notRun, and screenshots; earlier failures remain, and timestamped filenames preserve older evidence.

The script waits for visible business results, the current test round, and actual button state, and closes previous drawers between cases. Diagnose failures using real controls rather than forced clicks, DOM edits, or internal-state injection. M17/M18 wait 5.2 seconds to verify five-second automatic refresh.

Browser outputs are `results-management.json`, `results-client.json`, and case PNG/JPEG images. Complete L2 acceptance requires plannedCases of 36 and 12, empty notRun, latest PASS for every case, and existing screenshots. Retain browser/script failures as FAIL and record fixture audits separately from backend evidence.

## Manual retesting

Follow the complete plan step by step and inspect UI, mock requests, and screenshots. Distinguish request failure from an unconfirmed result. Reconcile actual GPU stopping separately from a running-call cancellation. The client's recovery button is in the test console; clicking it normally closes the model menu, so reopen the menu before continuing.

Stop test services and close tool-created temporary Tabs afterward. Keep evidence in `.local/`; exclude screenshots, generated pages, logs, dependencies, and databases from commits.

## Summarize and verify evidence

```sh
python3 scripts/checks/summarize-model-uat.py --regression .local/uat/regression-final --browser .local/uat/browser --manual .local/uat/manual-pending.json --output .local/uat
```

Point `--regression` at the actual regression directory; if using the first example's `.local/uat/regression`, select that directory here too. Copy `manual-results.example.json` to `.local/uat/manual-pending.json` and fill actual statuses/evidence after real-environment checks, retaining the source template unchanged.

The summarizer checks R01–R08, 48 page-case IDs, latest PASS, unexecuted cases, and screenshot encodings. It writes `UAT_REPORT.md` and `acceptance-summary.json`. Name screenshots according to actual PNG/JPEG encoding and record SHA-256. Preserve historical failures. Failed automatic/page checks or missing evidence return nonzero. Production acceptance also requires manual sign-off.

Enterprise Agent presets: select **企业 Agent 预设** to load production `EnterpriseAgentPresets`. `agent-browser-uat.mjs` has 7 interaction cases covering saving only the current version, editing, template-expression refusal, replacement publication, personal-preset policy, version conflicts, and permission-loss cleanup.

```javascript
var agentUat = await import('file:///Volumes/SSD/Code/funnyworker/apps/management/uat/model-middleware/agent-browser-uat.mjs')
await agentUat.runAgentBrowserUat(managementTab, '/Volumes/SSD/Code/funnyworker/.local/uat/enterprise-agents')
```

This writes separate `results-enterprise-agents.json` and case screenshots. The two existing model/usage lanes in `summarize-model-uat.py` exclude this supplemental lane. The manual template adds EP01–EP10; sign off each platform installer and actual-backend check individually.

## Model allowance pages

Select **模型限额** to load production `ModelBudgets`. `budget-browser-uat.mjs` covers default denial for new members, exact currency conversion and saved-value display, pausing at zero global budget, deployment pricing, preserving drafts on conflict, permission-loss cleanup, bilingual/light/dark states, global-permission scope isolation, and read-only pricing for ordinary organization administrators. All data is simulated, leaving real allowances and providers untouched.

```javascript
var budgetUat = await import('file:///Volumes/SSD/Code/funnyworker/apps/management/uat/model-middleware/budget-browser-uat.mjs')
nodeRepl.write(await budgetUat.runBudgetBrowserUat(managementTab, '/Volumes/SSD/Code/funnyworker/.local/uat/model-budgets-run1'))
```

Use a new output directory for each retest and preserve failures. Outputs are `results-model-budgets.json` and ten JPEG screenshots. This supplemental lane is separate from the existing 48-case summary. See [model limits](../../../../services/business/MODEL_BUDGETS.md) for backend budgets and PostgreSQL concurrency checks.

Allowance browser acceptance also covers promotions: one-time CNY 1 grants, balance caps, and global grant caps, plus manual-credit amounts and request deduplication identifiers. All actions change only isolated mock data.

Credit recovery covers component recreation after a lost response: reconcile a completed credit or retry with the original identifier and locked amount if unsubmitted. `account.html` uses real Mac/Windows account-menu components to check allowance, claiming grants, languages, appearance, and offline display in the isolated mock environment.
