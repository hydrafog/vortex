# Governance

The Vortex design system is maintained through convention, automation, and clear ownership. This document defines the rules for contributing, enforcing, and evolving the design system.

## Token lint

Builds fail on violations of the token system:

- Hardcoded colors outside the `Theme.kt` ladder or `AccentColor` enum.
- `dp` or `sp` values outside the defined tokens in `spacing-layout.md` and `typography.md`.
- Raw hex values in Compose code that should use tokens.

The `lefthook.yml` pre-commit hook enforces these rules at commit time.

## Screenshot tests

Roborazzi or Paparazzi tests cover dark, light, and OLED themes for every accent variant:

- Component previews for all primitives in `components.md`.
- Settings rows across all categories.
- Calendar card with events and empty state.
- Notes carousel with notes and empty state.
- Notification groups across all four types.
- Widget in both themes.

Tests run on push and block merges on failure.

## Accessibility tests

Automated checks enforce accessibility invariants:

- Touch target minimum of `48dp` for all interactive elements.
- Contrast checks for every foreground/background pair per theme and accent.
- Content description presence for all duotone icons, battery indicators, and toggles.
- Dynamic type rendering at 200% scale without clipping.

## Icon allowlist tests

The `SOLAR_ICON_ALLOWLIST` in `SolarIcons.kt` is the single source of truth for valid icon names. Tests verify:

- Every mapped action resolves to an allowlisted icon.
- `IconLoadFailure` never crashes a release build; it falls back to a default glyph.
- No unmapped icons render at runtime.

## Doc link checks

Cross-references in `## See also` sections are verified on commit. Broken links fail the build.

## Deprecation policy

Tokens and components marked for retirement are annotated in code with `@Deprecated` and in docs with a retired label. After one release cycle, retired items are removed.

When a component is retired:
1. Add `@Deprecated` annotation with removal version.
2. Update the component doc to state it is retired.
3. Remove from the active component table in `components.md`.
4. Keep the `## See also` section but note the retirement.

## Ownership and contribution

The design system is owned by the Android app team. Changes to tokens, primitives, or the visual language require review by at least one maintainer.

Contribution rules:
1. Update the relevant token doc and the code source simultaneously.
2. Add screenshot tests for any visual change.
3. Update `docs/index.md` if adding or removing a design doc.
4. Ensure the `## See also` sections link correctly.

## Changelog

Design system changes are documented in the `docs/design/` files themselves. Each file describes the current state.

## See also

- `design-system.md` defines the design principles and enforcement.
- `color.md` defines tokens and the contrast rule.
- `accessibility.md` defines accessibility requirements.
- `components.md` defines primitive contracts.
