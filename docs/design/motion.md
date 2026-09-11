# Motion

Motion keeps flat surfaces calm. Surfaces never pulse, glow, or blink. The carousel settles without overshoot, and press feedback stays under the perception threshold.

## Duration and easing scale

The grammar pairs each gesture with one duration and one curve. Press feedback stays under the perception threshold; screen and indicator motion shares one travel beat; the carousel settle runs one slower beat.

| Token | Value | Usage |
| :--- | :--- | :---- |
| Press scale | `0.99` | Card and square pressed state |
| Press duration | `90.ms` | Press and release feedback, ease-out |
| Travel duration | `180.ms` | Screen transitions and navbar indicator slide, ease-move |
| Carousel settle | `240.ms` | Notes carousel snap with no overshoot, ease-out |
| Stagger offset | `40.ms` | Staggered card entry, up to three cards |
| Count and progress | `240.ms` | Timers and progress fills with tabular numerals |

## Choreography

Pressed cards scale to `0.99` over `90.ms` with an ease-out curve and release on the same beat. Screen transitions run `180.ms` ease-out while the navbar indicator slides the same `180.ms` ease-move beat. The notes carousel settles `240.ms` ease-out. Staggered card entry offsets `40.ms` per card up to three cards; the carousel enters with the final card instead of staggering further.

## Reduced motion

Reduced-motion preferences disable scale, slide, stagger, and settle with instant state changes. Skeleton loading states render statically with no animation.

## See also

- `design-system.md` overviews the design principles and file map.
- `components.md` records the pressed card contracts.
- `notes.md` records the carousel settle behavior.
- `navigation.md` records the navbar indicator behavior.
- `states.md` records the loading and pairing appearances.
