# Agent Note: FunnyWork distribution branding

Status: implemented

English | [中文](2026-10-11-funnywork-production-build.zh.md)

## Problem

Desktop packaging selected the upstream official client profile, which overrides caller titles and embeds DeepSeek Harness branding. The resulting FunnyWork application displayed the preview badge and upstream welcome copy.

## Decision

Desktop packaging selects the named `funnywork` distribution profile. It pins `FunnyWork` and its own profile identifier, includes the source commit and package version, and excludes caller-only values and dirty metadata. Release packing verifies the same selected profile against the built artifact record. The upstream official profile retains its existing contract.

## Validation

Owning tests cover profile isolation, rejection of upstream artifacts during FunnyWork release packing, and packaging-stage environment propagation. The built application smoke checks the FunnyWork identity and absence of the preview badge.

## Alternatives considered

**Set only the caller title.** The upstream official profile overrides that value, so this does not fix packaged artifacts.

**Use a local build.** This loses the named distribution contract and its strict artifact verification.

## Consequences

Signed and unsigned desktop packages share the FunnyWork client identity. Brand selection happens before bundling and release verification rather than after application installation.
