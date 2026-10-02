# The presentation language

Every screen in Nivara is built from the same small set of shared pieces, so the application reads
as one product instead of a collection of features. This document is the contract those pieces
follow; it describes behavior that holds on every screen, not a style guide for one of them.

## Design tokens

Spacing, sizing, shape and motion are named values, not numbers scattered across screens:

- `NivaraSpacing` — `hairline` (2dp), `tight` (4dp), `small` (8dp), `row` (12dp), `screen` (16dp),
  `section` (24dp). Screen edges use `screen`; gaps inside a row use `row` or `tight`; the space
  between sections uses `section`.
- `NivaraSize` — `touchTarget` (48dp, the platform minimum), `rowIcon` (40dp), `gridIcon` (56dp).
  Interactive controls are never smaller than `touchTarget` unless they sit inside a larger
  tappable region.
- `NivaraShapes` — a small family of rounded corners (extra-small to extra-large). Containers that
  hold related content (cards, dialogs, the code entry field) use the medium/large steps; rows
  rely on spacing rather than a background of their own.
- `NivaraMotion` — `instant` (0ms), `quick` (150ms), `standard` (250ms), `emphasized` (350ms).

Screens do not invent new steps. When something does not fit the scale, that is a question about
the scale, answered in one place.

## Colour

Colours come from the Material 3 theme, never inline. The semantic roles are:

- `primary` — accent and the application's main actions.
- `tertiary` — positive outcomes and success.
- `error` — failure, damage, wrong input.
- `onSurfaceVariant` — quiet, secondary text.

Light and dark are both first-class: the application follows the system setting and never offers
its own toggle, and both schemes keep the same hierarchy of surfaces and the same meaning for each
role. No state is communicated by colour alone — wording always carries the meaning.

## Motion

Motion is native Compose, short, and serves comprehension: navigation continuity between screens,
the move from loading to content, the drawer opening, and nothing more. Three rules hold:

1. Every duration passes through `scaledDurationMillis`, which applies the device's own animator
   duration setting. A user who has asked the platform for no animation gets an instant resolution
   — not a shorter animation — and every transition in the application honours that.
2. Motion never touches sensitive material: credential entry, recovery-code entry, the vault's
   content, and anything on a `FLAG_SECURE` window are never animated.
3. State is authoritative over animation. A state change renders even while an animation is in
   flight; an animation never gates an action — launching an application, for example, acts
   immediately whatever the drawer's fade is doing.

## Shared pieces

- `NivaraLoadingState` — the one loading presentation; no indefinite spinners anywhere else.
- `NivaraErrorState` / `NivaraEmptyState` — wording plus action(s), with stable layout so a state
  change does not move the floor.
- `NivaraSectionHeader` — a section's title and quiet summary; announces itself as a heading so a
  screen reader can jump between the parts of a screen.
- `NivaraMessageText` — a transient, dismissible message; `Modifier.dismissibleMessage` gives every
  message the same tap-to-dismiss behaviour.
- `ApplicationIcon` — draws an application's icon with a fallback, at a shared size.

## Accessibility

- Every icon that means something carries a content description; decorative icons are silent.
- Choice rows carry the radio-button role and state, so a screen reader hears "selected".
- Interactive rows label their tap ("Open", not just the item's name).
- Descriptions never carry identity: no package names, vault identifiers, URIs, keys or recovery
  material — the same rule as everywhere else in the application.
- Layouts wrap rather than clip when text scales up; long names ellipsize in rows where wrapping
  would break scanning.

## What the presentation never does

It never reads vault content (only the viewer does), never touches files or codecs, never caches
security state, never persists what the user typed, and never reaches for a permission, a network
call or a background service to make a screen easier to build.
