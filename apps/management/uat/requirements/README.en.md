# Isolated requirement-pool interaction fixture

[简体中文](README.md) | **English**

Uses the real RequirementPool component with fictional API data to check pagination, editing, idempotent retries, frozen queue submissions, impact display, dependency forms, and access-revocation cleanup. Add `?shell=1&tc_view=requirements&tc_requirement=req-1` to load the real task-center shell, maintenance, feedback, and organization-task components and check execution-device drilldown and return navigation. Only unused personal-task/scope settings remain placeholders. The project catalog response is delayed to check initial-loading races. `readonly=1` uses read-only menu permissions; writes reuse the real checkPermi. Backend authorization and automation tests run separately from this fixture, which uses no real accounts, repositories, or models.

From the repository root, start management Vite through the external-storage wrapper with `apps/management/uat/requirements/vite.config.mjs`, listening on `127.0.0.1:19201`. Build caches go to `.local/agent/packages/requirements-vite/`. Stop the process after acceptance and keep this fixture outside publication.

Management dependencies must already be installed. Use a separate temporary browser profile under the project's `.local/agent/tmp/`, and remove it after closing the browser. Keep the user's Chrome session separate.
