# Agent Note: Settings readiness during reload

Status: implemented

English | [中文](2026-10-10-settings-reload-readiness.zh.md)

## Problem

Settings can open before the Host event connection and first-run capability checks settle. Closing Settings when an onboarding registrant mounts dismisses the panel even when that registrant ultimately renders nothing. Permission settings can also report a missing connection as an error or leave choices from the previous connection enabled.

## Decision

`SettingsOnboardingOwnerProps.present()` closes the settings panel only when the onboarding modal actually mounts. Both shipped onboarding registrants notify the coordinator through `OnboardingModal`; steps that are loading or already satisfied do not present.

`PermissionHostConnectingError` distinguishes missing connection readiness from a failed catalog response. The permission settings controller waits for the catalog subscription to deliver a current value, and clears and disables choices when the catalog is withdrawn. The shared catalog publishes a current-generation error for failed refreshes rather than leaving the settings row loading indefinitely. The row offers localized retry copy, coalesces concurrent retries, and preserves newer settings metadata while a retry is pending.

Browser snapshots wait for the advertised default permission button before comparing a settled settings page. They do not change expected output to accept the loading state or add fixed sleeps. The browser fixture mirrors the current FunnyWork welcome copy.

## Alternatives considered

**Close on registration.** Registration and presentation are different facts. This earlier behavior closes an unrelated settings panel during an invisible capability check.

**Increase test delays.** Longer sleeps conceal the readiness race without repairing the product. Observable permission readiness gives the snapshot a concrete completion signal.

**Retain enabled choices during reconnection.** Previous-generation choices cannot authorize a current write, so the row disables them until the Host supplies a replacement catalog.

## Consequences

Onboarding extensions notify the coordinator when their visible modal mounts. Connection loss temporarily disables permission edits; a replacement catalog restores them. Other preference rows and explicit settings close actions keep their existing behavior.

The focused regressions fail against the former implementations and pass with the fixes. Browser scenarios cover reload persistence, independent navigation scrolling, language fallback and the browser-disabled settings surface.
