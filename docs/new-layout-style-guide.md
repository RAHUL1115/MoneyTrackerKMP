# New layout style guide

How the new layout looks, and which value wins when the design canvas and the app disagree. The design canvas
("MoneyTracker Redesign") shows the screens; this guide fixes the shared rules. Tokens and shared components live in
`composeApp/src/commonMain/kotlin/app/moneytracker/ui/fresh/Kit.kt`.

Decided on 2026-11-01:
- **Type follows the app, not the canvas.** The canvas draws extra-bold (800) titles and amounts at larger sizes. The app
  keeps the calmer classic scale below (Medium and SemiBold). The canvas is out of date on this point.
- **System font.** The canvas uses Plus Jakarta Sans for mockups only; the app uses the phone's font.
- **Everything else follows the canvas:** spacing, corners, component shapes, row heights and colour roles.

## Principles

- Calm, not fancy: off-white page, white cards, one teal accent for actions and what's selected.
- Nothing moves: zones keep fixed heights; the keyboard draws over the keypad instead of resizing the screen.
- One hand: the controls you tap most sit in the lower half.

## Colour

| Token | Light | Use |
|---|---|---|
| `F.page` | #F3F4F1 | Screen background, filled secondary buttons |
| `F.card` | #FFFFFF | Cards, trays, sheets, header tiles |
| `F.ink` | #16201D | Text, toast background |
| `F.muted` | #5D6964 | Labels, hints, meta lines |
| `F.line` | #ECEEEA | Dividers inside cards |
| `F.border` | #E3E6E2 | Hairline borders (inputs, secondary buttons, tray top) |
| `F.fill` | #EEF0EC | Icon tiles, unselected chips inside sheets, picker rows |
| `F.track` | #E6E8E4 | Segmented control track, progress bar track |
| `F.switchOff` | #C9CFCC | Switch track when off |
| `F.accent` / `F.accentTint` | #0F5E4E / #E3F2EE | Primary action, selection, success |
| `F.income` / `F.expense` | blue / red | Money in / money out (swapped by the colour setting) |
| `F.warn` | #C26B4E | Over budget only |
| `F.danger` | #E5484D | Delete and other destructive actions |

Roles:
- **Entry amounts** are coloured by direction: out red, in blue.
- **Summary totals** ("Out" on calendar, months and search) are ink; "In" totals are blue.
- **Account balances** above zero are blue, below zero red. Card rows stay ink; a card in credit shows the credit in blue.
- **Success** (all caught up, backup done) uses the accent and its tint, not a separate green.
- **Selection** is always the accent tint with accent text, never black.

## Type (system font)

| Role | Size | Weight |
|---|---|---|
| Tab title (Home, Activity, Insights, Accounts) | 22 | SemiBold |
| Inner screen and sheet title | 17 | SemiBold |
| Hero amount (Spent today) | 34 | SemiBold |
| Large amount (net worth, receipt amount) | 26–40 | SemiBold, shrinks to fit |
| Card or section title | 15 | Medium |
| Row title, body, button | 14 | Medium (buttons SemiBold) |
| Row amount | 14 | Medium, tabular figures (`F.money`) |
| Meta line, hint | 12 | Regular |
| Field label | 11 | Medium, upper case, 0.6 letter spacing |

## Spacing and size

- Side margin (`F.gutter`): 8, so cards use nearly the full width (changed from the canvas's 16 on 2026-11-01). Gap between cards on a screen: 12.
- Card padding: 18 (list cards: 4 top and bottom, 18 sides). Hero card padding: 20.
- Inner screen header: back tile and title, 16 above and 8 below, 8 between them.
- Touch targets: at least 44.
- Row heights: settings and list rows at least 52; entry rows at least 64 with a 40 tile; picker rows 48–50.

## Corners

| Element | Radius |
|---|---|
| Card | 20 (`F.cardShape`) |
| Hero card | 24 (`F.heroShape`) |
| Sheet top | 24; confirmation sheet 28 |
| Button | 16 (`F.buttonShape`) |
| Header tile (back, settings, add) | 14 (`F.iconShape`) |
| Text field | 12 |
| Segmented control | 12 outside, 10 per segment |
| Small in-card button (Restore, Save) | 10 |
| Chip, pill, badge | fully round |

## Components

- **Header tile**: 44×44 white tile, radius 14, 20 icon. Text actions in a header ("Edit", "+ Category") use the same
  white 44-tall tile with text, not a bordered pill.
- **Primary button**: 52 tall, accent, radius 16. Disabled: `F.fill` with muted text.
- **Secondary button**: 52 tall, white with a hairline border. In a confirmation, Cancel is filled `F.page` with no border.
- **Destructive text action** (Delete account, Stop rule): red text on `F.dangerTint`, or red text alone, never a
  bordered white button.
- **Segmented control**: 42 tall, `F.track`, white selected segment.
- **Pill / chip**: 34 tall, 12 side padding, 6 apart. Selected: accent tint, accent text; unselected: white with a hairline.
- **Switch**: `FSwitch`, 46×28, accent when on, `F.switchOff` when off, white thumb.
- **Text field**: white, hairline border, radius 12, at least 44 tall, 12 side padding.
- **Toast**: ink background, radius 14, shadow, 13 text. Undo is a small chip; × closes it.
- **Progress bar**: `F.track` track, accent fill (warn when over budget).
- **Entry tray** (adding an entry): edge to edge, squared off, a hairline on top and under its header; 8 inside.
  Categories in two columns of wide rows; accounts as rows with balances under Recent and account-group tabs.

## Differences found on 2026-11-01

Every board on the canvas was compared with the code. Status: **Fixed** (app changed to match), **Type** (kept: the app's
type scale wins), **Kept** (deliberate app choice, canvas to be updated).

### Everywhere
| Difference | Canvas | App before | Status |
|---|---|---|---|
| Font | Plus Jakarta Sans | System font | Kept |
| Title, amount and button weights and sizes | 800, larger | SemiBold/Medium | Type |
| Settings and list rows | 44–52 tall | At least 70 (padding outside a 44 minimum) | Fixed: at least 52 |
| Inner screen header | 16/8 padding, 8 gap | 10/10, 12 gap | Fixed |
| Side margin | 16 | 16 | Changed to 8 on request; canvas to follow |
| Segmented control | 42 tall, #E6E8E4 track, radius 10 | 44, #EEF0EC, radius 11 | Fixed |
| Pills and chips | 34 tall, 6 apart | 36, 8 apart | Fixed |
| Switch | Custom 46×28, clear grey off track | Material switch, faint off state | Fixed |
| Text fields | White, hairline, radius 12, 44 tall | Grey fill, radius 14, 48+ | Fixed |
| Toast | Radius 14, shadow, Undo chip, × close | Radius 16, no shadow, text Undo, no × | Fixed |
| Confirmation sheet | Radius 28, filled Cancel | Radius 24, bordered Cancel | Fixed |
| Sheet side padding | 16 | 20 | Fixed |
| Progress bar track | #E6E8E4 | #EEF0EC | Fixed |

### Per-screen differences

| Screen | Difference | Canvas | App before | Status |
|---|---|---|---|---|
| Home | Spending card padding and gap | 24/22/20, gap 20 | 20/18, gap 16 | Fixed (radius stays 24) |
| Home | Budget bar track; over budget | `F.track`; warn | `F.fill`; danger | Fixed |
| Home | Selected tab in the bottom bar | Bolder label | Same weight as the others | Fixed (SemiBold) |
| Unlock | Gap between items; lock icon | 20; 28 | 16; 30 | Fixed |
| Bank SMS review | "N to review" line | Under the title in the header | A line in the body | Fixed (header subtitle) |
| Bank SMS review | Stacked-card edges behind the message | Shown | None | Fixed (when more than one waits) |
| Bank SMS review | Dismiss and Edit buttons | White, no border, radius 18 | Bordered, radius 16 | Fixed |
| Bank SMS review | Detail rows | 52 tall | 44 | Fixed |
| Bank SMS settings | "Original message" control | Compact, inline on the right | Full width below the row | Fixed |
| Bank SMS settings | Detected-messages tile | Accent tint | Grey mark | Fixed |
| Bank SMS settings | Days control | 40-tall segments, radius 12/9 | 48, radius 14/11 | Fixed |
| Bank SMS settings | "Per account ›"; filter Save | Accent link; 44 tall | Muted with chevron; about 36 | Fixed |
| Activity | Gap between days | 10 | 16 | Fixed (12, the guide's card gap) |
| Calendar | ‹ › arrows | At the card's edges | Next to the month | Fixed |
| Calendar | "Out" total | Ink | Red | Fixed |
| Calendar | Card padding | 12/12/14 | 16 | Fixed |
| Calendar | Rows of the picked day | 56 tall, 30 tile | 52, 32 | Kept (shared row) |
| Months | Week "out" amounts | Red, blue when net in | Ink | Fixed |
| Months | Lines between weeks | Dashed | Solid | Fixed |
| Months | Year "Out" total | Ink | Red | Fixed |
| Months | Gaps between and inside cards | 12; 16 | 16; 12/8 | Fixed |
| Notes | Card padding and gap; editor padding | 18 and 10; 18 | 16/14 and 16; 16/14 | Fixed |
| Search | Chips | 34 tall | 32 | Fixed |
| Search | "out" total | Ink | Red | Fixed |
| Search | Filter card spacing; header; footer | 16; 16/8; 20 | 12; 10/10; 12 | Fixed |
| Search | Category tile on results | None | Shown | Kept (helps scanning) |
| Insights | Trend bars | Full width, gap 8, radius 7 | 70% width, gap 6, radius 6 | Fixed |
| Insights | Past bars colour | #B9D9CE | Accent at 35% | Kept (close; follows the accent) |
| Insights | Daily average and projected | Two grey tiles | Columns under a line | Fixed |
| Insights | Category / Account / Tag switch | Small unbordered pills beside the title | Bordered pills below it | Fixed |
| Insights | Breakdown marks; change badge; budget ring | 28; radius 8; 40 | 34; round; 52 | Fixed |
| Budget | Ring; row marks; bars | 84; 28; 8 tall | 60; 34; 6 | Fixed |
| Budget | Editing a budget | Inline field, compact Save, red-text Remove | Full-width bordered buttons | Fixed |
| Budget | Adding a category | Three-column tiles | A list | Fixed |
| Budget, Account detail | "+ Category", "Edit" in the header | White 44-tall tile | Bordered round pill | Fixed |
| Accounts | Liabilities part of the net-worth bar | Grey, with a gap | Track colour | Fixed |
| Accounts | "Pay bill" | Tinted | Solid accent | Fixed |
| Accounts | Add-account tile; gap between cards | Tinted; 12 | White; 16 | Fixed |
| Accounts | Show hidden accounts | Dashed muted button | Accent link | Fixed |
| Account detail | Notes box | White, radius 16, ink text | Grey, radius 20, muted | Fixed |
| Account detail | Entries grouped by day | One card of rows | One card per day | Kept (easier to read) |
| Edit account | Switch rows | Divided, at least 58 | No dividers | Fixed |
| Edit account | Group chips | White with a hairline | Grey fill | Fixed |
| Edit account | "Bill paid from" and similar values | Accent | Muted | Fixed |
| Edit account | Delete | Red text | Bordered button | Fixed |
| Settings | On/off rows | "On"/"Off" text | Switch | Kept (switches are clearer) |
| Settings | Link rows | No chevron | Chevron | Kept (shows they open) |
| Settings | Quick tiles | Bare icon, radius 16, tighter | Icon in a tinted box, radius 20 | Fixed |
| Settings | Section headers; search field | Padding 14, small chevron; 44 tall, accent ring | 16, large chevron; 48, no ring | Fixed |
| Settings | No results; footer | Centred | Left | Fixed |
| Categories | Up/down buttons | Grey filled, 34 | No fill, 36 | Fixed |
| Categories | Fields in the sheet | White hairline field | Material outlined field | Fixed |
| Categories | Delete tile | Soft red | No fill | Fixed |
| Categories | Sub-categories note | Plain text | Card with "Turn on" | Kept (it's a setting) |
| Repeats | Summary card; group titles | Radius 24; inside the card | 20; above it | Fixed |
| Repeats | Rule rows | 38 mark, at least 64 tall | 36, tighter | Fixed |
| Repeats | Stop rule; summary sentence | Red text on soft red; grey box | Bordered half-width; plain | Fixed |
| Backup | Status card | Tinted (success colour) | White | Fixed (accent tint, not green) |
| Backup | Status buttons | 46 tall, Share filled | 52, Share bordered | Fixed |
| Backup | Start over | Inside a card, warm border | Bare bordered buttons | Fixed |
| Backup | Snapshots | Label in the card, no dividers, small Restore and Delete | Dividers, larger buttons | Fixed |
