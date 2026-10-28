# Settings redesign

Why the settings app feels like too much, what to build so a Gboard or SwiftKey
user feels at home, and how to do it without dropping a single setting.
Numbers are from the source at `e31f6f977` (2026-10-26); the five screenshots
came from the build on the phone (0.6.3, versionCode 382).

## What the code says

The app draws **2,062 rows** behind **123 routes**: 671 switches, 220 sliders
and steppers, 214 choice rows, the rest navigation, actions and lists. Only
**35 groups fold** (16 of them in the theme editor, 8 on the Tools list), so
nearly everything is open all the time.

The pages people actually land on are the long ones:

| Screen | Rows | Groups | Folds |
|---|---:|---:|---:|
| Typing › Gestures | 69 | 4 | 1 |
| Clipboard | 51 | 4 | 0 |
| Typing › Automatic corrections | 42 | 1 | 0 |
| Typing › Suggestions | 36 | 1 | 0 |
| Layout › Size & position | 31 | 2 | 0 |
| Key press (hub) | 28 | 3 | 0 |
| Floating button | 28 | 5 | 0 |
| Layout & size (hub) | 25 | 6 | 0 |
| Emoji | 25 | 5 | 0 |
| Accessibility | 23 | 5 | 0 |
| Theme editor | 137 | 17 | 16 |
| One tool's page (70 of them) | 3 to 25 | | |

Gboard has about 65 settings in total. SwiftKey about 80. A WM Keyboard user
who opens Typing › Gestures meets 69 on one page, most of them timings and
thresholds with a `?` beside each.

The chrome costs as much as the rows. On the phone (800 dp tall), the expanded
bar is 152 dp, the path strip under it 44 dp, and the top gap 12 dp: 208 dp of
heading before the first row, a quarter of the screen. Rows run 72 to 110 dp,
so four or five fit. The path strip also draws on every
second-level page ("Home › Typing"), where it repeats the title sitting right
above it; it was designed to start one level down (`MinCrumbDepth = 1`), and
that is worth revisiting now that the title is so large.

One layout bug was visible on the first screens opened. On **Appearance ›
Keyboard themes** the current value ("Default") sits in the row's trailing
slot, so `ListItem` gives it a column for the row's whole height and squeezes
the subtitle into seven lines of three words each. `NavRow` now puts a value
that fits on the title line, where the subtitle keeps the row's width.

Every switch row carries up to three extra targets (`?`, link, reset) stacked
two high beside the switch. With Setting links on, every row has a chain icon.
That is a lot of furniture for someone who wants to turn off vibration.

What already exists and is under-used:

- The onboarding quiz asks **"What kind of keyboard do you want?"** with
  "Keep it simple / A good middle / Show me everything" (`PersonaDepth`).
  Today the answer only picks the starter tool set, the theme gallery style
  and how many discovery cards to show. It never touches the settings app.
- `SettingsGroup(foldKey = …)` already folds a group closed, remembers the
  state per screen in `AppUiSettings.advancedOpen`, and opens itself when a
  search result lands inside. The machinery is there; it is used 35 times.
- Search indexes every row, favourites pin any row, the keyboard test button
  shows the real keyboard under any screen, and `MiniKeyboardPreview` draws a
  live mini keyboard from the settings (onboarding and the Layout hub use it).
- `SETTINGS_AUDIT.md` already sorted 828 settings into keep / demote / merge /
  automate / remove, screen by screen. Its 135 demotes are the first list of
  rows to hide.

## The design

One dial, three levels, and every row knows which level it belongs to. The
dial is **Settings depth**: **Simple**, **Standard**, **Everything**. A row
above the current depth is not drawn; it keeps its value, and search still
finds it and opens its group with the row showing. Whoever wants to see where
the rest went switches on About › App › "Show where settings are hidden", and
every group that left something out ends in a "N more settings" row.

### 1. Settings depth

- `AppUiSettings.settingsDepth: SettingsDepth { AUTO, SIMPLE, STANDARD,
  EVERYTHING }`, DataStore key `settings_depth`, default AUTO. AUTO resolves
  from the onboarding answer the same way the theme gallery style does: "Keep
  it simple" → Simple, "Show me everything" → Everything, anything else
  (including installs that never saw the quiz) → Standard.
- Where to change it: About › App › **How many settings to show**, beside the
  other rows about the app itself. It was the first row on Home and a row on
  Accessibility in the first cut; the owner moved it (2026-10-26): a control
  about the settings app is not a setting anyone looks for first.
- Under it, **Show where settings are hidden** (off): on, a "N more settings"
  row ends every group the depth left something out of, and Home's left-out
  rows come back as a closed More group. Off, a hidden row is simply not there.
- The onboarding option subtitles say "and fewer settings" / "every setting",
  so the quiz is honest about what it decides.

### 2. Row levels

`SettingsGroupScope.item(level = SettingLevel.X)` with three values:

| Level | Shown at | Rule for tagging |
|---|---|---|
| `BASIC` (default) | every depth | Gboard, SwiftKey or Samsung have it; or it is a feature's master switch; or it is the list the page exists for (your languages, your snippets) |
| `STANDARD` | Standard, Everything | a choice a non-technical person could still want: a size, a style, a which-of-three |
| `ADVANCED` | Everything only | timings, thresholds, multipliers, per-variant overrides, anything that needs the engine explained, everything the audit demoted |

Untagged rows are `BASIC`, which is why the sweep is safe to do a screen at a
time: an untagged screen looks exactly as it does today at every depth.

`SettingsGroup(level = …)` tags a whole group; below its depth the group draws
as a closed fold with its row count, and opens like any fold.

Inside a group, the rows above the depth are left out. With "Show where
settings are hidden" on they move to the end of the card stack behind one row,
**"N more settings"** with a chevron: tapping it expands them in place (the
rows grow in under the row, same reveal as a conditional row), the group's
corners follow, and the open state is remembered per group in `advancedOpen`.
While a search highlight is pending the hidden rows are drawn in their places
whatever the switch says, so a result can land on one.

Home's 17 rows get a level too. At Simple the list is Languages, Typing, Key
press, Appearance, Layout & size, Emoji, Voice typing, Clipboard, Tools,
Accessibility, Privacy, About, plus one closed "More" fold holding Rows &
bars, Text expansion, AI, Backup and Advanced.

Target sizes once the sweep is done: Simple about 120 rows across the whole
app, Standard about 600, Everything all 2,062.

### 3. Extensions

The second half of "too many settings" is features a user never asked for
showing up everywhere: a Tools entry, a toolbar button, a search result, a
panel, background work. The tool list already has a switch per tool, but a
feature is bigger than a tool.

An **Extensions** screen lists the far-fetched features with one switch each
and a line on what each adds. Far-fetched means what most people would never
expect a keyboard to have: a bubble level, a music recogniser, a link to the
desktop, a notebook, a speed test. Not the necessities. Voice typing, the
clipboard, snippets, translate and AI are what a keyboard is for today and
keep their own switches where they live. (The first cut had them as bundles;
the owner called that nonsense on 2026-10-26 and the list was redone.)

Off means gone: not on Tools, not in the toolbar or toolbox, not in search,
not in the row counts, no notifications, no background refresh. On restores
all of it. The bundles, in the order the screen lists them: Stickers, GIFs
and emoji mixes · Scanners · Handwriting · Notes · Vocabulary · Calculator
and converters · Web search · Camera and gallery · Calendar and clock ·
Generators · Typing test · Read back · Learn from text · Find and replace ·
Keyboard modes · Media controls · App launcher · Weather and location ·
Instruments · Music recognition · Speed test · KDE Connect · Send files ·
Smart home.

Mechanically it is one `Extension` enum carrying the tools and routes it owns,
and a check at the choke points: `ToolGroups` (and every picker reading it),
`SettingsSearch`, the keyboard's toolbox and toolbar, the Advanced rows, and
the deep-link allowlist. Depth sets the defaults: Simple starts with none,
Standard with the bundles that work the moment they are on, Everything
with all.

### 4. Splits and merges

Pages that stay long after tagging are split where the audit and the counts
agree:

- Typing › Gestures (69) → **Glide typing**, **Spacebar**, **Flicks and
  swipes**. The glide tuning fold stays with Glide.
- Clipboard (51) → **History**, **Paste chip and codes**, **Rules and privacy**
  (phone formats and copy rules already have pages).
- Automatic corrections (42) → **Autocorrect**, **Capitals, punctuation and
  spacing**.
- Suggestions (36) → keep one page; tag the learning and source rows Standard.

Merges the audit called for, each turning several rows into one control:
double space (period / tab / off) into one choice; the six press-and-hold
letter rows into one multi-choice; trail width, length and opacity into one
trail control; toolbar spread and scroll into one layout choice; popup min and
max duration into one slider with the max as a constant.

### 5. Chrome and layout

- `NavRow`: a value that fits goes on the title line, so the subtitle keeps
  its width (the Keyboard themes bug; done in phase 0).
- Path strip from the third level only. At the second level the title is
  already saying where you are.
- Sub-pages take a shorter expanded bar (112 dp instead of 152). Home keeps
  its badge.
- At Simple depth the `?` and link buttons leave the row; a long press on the
  row opens the hold menu, which already exists, with "What is this?" at the
  top. Reset stays, since it only appears on changed rows.
- Hub subtitles are cut to one line. "Autocorrect, capitals, punctuation and
  spacing" is a sentence, not a subtitle.
- Slider rows draw the slider on the title line's right when the name is
  short, which takes a 110 dp row down to 72.

### 6. Previews (goal stands; the first attempt failed it, see phase 4)

A row that changes how the keyboard looks should show the change, not describe
it. `MiniKeyboardPreview` is already live-driven; pin it under the bar on
Layout & size, Size & position, Appearance (keys group), Key press › Key
popup, Rows & bars and Emoji (emoji row), the way the theme editor pins its
preview. Add to it:

- an animated stroke over the mini keyboard on Gestures (glide trail, spacebar
  swipe, backspace swipe), one loop per setting while its row is in view;
- a glyph per option on choice rows that have a shape (`ChoiceDetail` already
  carries one; the shape pickers use it, the rest do not);
- a swatch strip on the theme row and a glyph sample on the font row.

### 7. Motion

The "more settings" rows, when shown, expand with the group's existing
reveal, a depth change cross-fades the rows that come and go, and Reduce
motion keeps the still versions, as every other motion in the app already
does.

## Order of work

**Phase 0 (done 2026-10-26, uncompiled):** depth setting and resolver, row
levels and "more settings" folds, the picker (since moved to About › App,
with hidden rows hidden by default), Home's More fold, the NavRow fix, docs, and 391 tags across Typing and its six sub-pages,
Key press and its three, Appearance, Layout & size and its two, Emoji and
the panel page, the Languages root, Clipboard, Voice and Privacy. Untagged
rows are basic, so every other screen is unchanged at every depth.

**Phase 1, one card per file:** tag the rest. Each card also applies the
audit's merges on its screen, and re-reads phase 0's tags on the way past:
they were set from the row names and the audit, not from use.

- Done 2026-10-26, uncompiled: the tool pages, 273 tags across
  `ToolDetailScreens.kt` and the per-tool files it calls into, plus the
  provider pages and the Whisper and model lists they open. Of about 410
  rows, 79 stay basic, 192 are Standard and 139 Advanced; a page now shows
  its switch and one or two everyday rows at Simple. The Toolbar and Keyword
  folds every page carries are Standard, with icon colours and Match capitals
  Advanced. `ProvidersGroup`, `serverItems` and `ServerFieldsGroup` take a
  `level`, and the fold of how a provider list is used is Advanced
  everywhere. Three controls drawn outside any group cannot fold: the GIF and
  sticker content filter, QR error correction and the typing test's default
  test.
- Done 2026-10-26, uncompiled: Rows & bars, the symbol row page, Modes and
  the mode editor; Accessibility (the Settings app group left as it is);
  Backup, automatic backup and sync; Data saver; the AI hub, the AI tool page
  and the action editor; personal words, blocked words, copy rules and
  learned corrections; Snippets and the snippet editor; Floating button; the
  layout list, the layout editor's options and the key sheet (not the grid);
  the theme gallery, app colours and the theme editor, its part sheets and
  the rows inside its folds; Add-ons; Plugins and plugin details; About;
  Notifications; Selection actions; Photo rotation; the per-language page
  with its CJK, pinyin, phonetic-strip and Khipro groups; Kaomoji; Emoji
  mixes; Permissions. About 650 row declarations: 245 basic, 236 standard,
  173 advanced, counting a row inside a tagged group at the group's level
  and a row drawn in a loop once.
- Left untagged on purpose: the AI chat screens (a chat, not settings
  rows), the selection action list and its sub-lists, the add-on detail and
  preview pages (information, not choices), the layout grid, and every keyed
  list row.
- Tail done 2026-10-26: semantic search's two optional rows are Standard,
  the Espanso hub address group is Advanced, backup locations are a list
  (basic by rule). The three controls that sat outside any group now sit in
  one: the GIF and sticker content filter (Standard), QR error correction
  (Advanced, with the size limit) and the typing test's default test
  (Standard). Audit merges on the tool pages: Power saving has a What to
  drop bundle row (Light / Balanced / Aggressive / Custom) over its twelve
  switches, and the key popup and typing statistics drops are gone; the
  camera's own haptics switch is gone, the panel follows the keyboard's.

**Phase 2 (done 2026-10-26, redone the same day after review, uncompiled):**
Extensions. `Extension` in `core/settings` with the tools and routes each of
the twenty-four bundles owns, the enabled set in `AppUiSettings.extensions`
(null for an install from before, which keeps everything on; onboarding lands
the depth's defaults), the Keyboard modes master switch moved with its
bundle, `enabledTools` read with a switched-off bundle's tools taken out, the
choke points, the `extensions` screen and one page per bundle, docs and
`ExtensionsTest`. The first list bundled the necessities (AI, voice, the
clipboard, snippets, translate, plugins, add-ons, selection actions, the
floating button, the physical keyboard, statistics, to-do); the owner threw
that out, and the list is now the far-fetched features only (§3).

**Phase 3 (done 2026-10-26, uncompiled):** the splits and the merges.
Seven routes added, none renamed: `typing/gestures` is a hub of
`typing/gestures/glide`, `typing/gestures/spacebar` and
`typing/gestures/flicks`, with the glide master switch on the hub's row;
`clipboard` keeps the history switch (as the door to `clipboard/history`),
the doors to `clipboard/chips` and `clipboard/rules`, and the Panel fold;
`typing/corrections` keeps autocorrect and `typing/writing` (Capitals,
punctuation and spacing) takes the rest, reached from the Corrections page
and the Typing hub. Suggestions stays one page. Of the merges, double
space, the six hold shortcuts and the toolbar's spread/scroll were already
one control each; this phase adds the trail style (Off / Thin / Normal /
Bold over the three sliders, which sit at Everything) and drops the key
popup's maximum duration row, whose stored value is read as it was. A
stale row link or favourite that names a row's old screen now opens the
screen the row is on. Every page has an icon, a colour and a section in
the index; the docs pages for Typing, Key press and the Clipboard tool,
the settings overview and `settings-links.json` follow the moves.

**Phase 4 (not done; first attempt reverted 2026-10-26):** the previews the
owner asked for are still wanted. What Claude built, a keyboard miniature
pinned under the bar on five screens, a looping gesture demo and row glyphs,
did not serve the goal and was reverted (revert of merge cce4d9cd1): a
pinned panel spends the space phase 5 reclaimed, and a loop is decoration,
not something that helps a reader understand a setting. The next attempt
starts from what a non-technical reader needs to see beside a row, is
sketched and agreed before it is built, and costs no space while unused.

**Phase 5 (done 2026-10-26, uncompiled):** chrome. The path strip draws from
the third level (`MinCrumbDepth` 2), so a second-level page is its back arrow
and its heading. Every page below Home takes a 112 dp expanded bar; Home keeps
152 dp under its badge. The title bands were re-fitted by measuring every
screen title in Manrope Bold against the 276 dp a 360 dp phone leaves beside
the heading tile (≤14 chars 33sp, ≤18 28sp, ≤22 24sp, ≤25 21sp, longer 19sp),
which puts "Phone number formats", "Image sizes and templates" and
"One-handed, split and floating" on one line where they ellipsised before;
the last still ellipsises in the collapsed strip, which has 248 dp. At Simple
`RowExtras` composes no "?" and no link button; the row's hold menu opens
with "What is this?" (the same dialog) and, with Setting links on Button,
copies the link as Hold does. The reset stays. Thirty-five hub subtitles were
cut to one line at 360 dp, measured in Inter at the row's 200 dp. A slider
whose name fits beside a 140 dp slider, its pill and its extras draws the
slider on the title line (72 dp rather than 110); the pill, the typed value
and the haptic ticks are unchanged. Density was checked, not changed: Material's
72/88 dp rows, 3 dp between cards, 16 dp under a group; each card still pads
4 dp above and below its row, from the reveal work.

`GroupCard`'s 4 dp vertical padding went on 2026-10-26: a row brings its own
padding, and the extra 4 dp made every row 80 dp where the list is designed
around 72.

## What this does not do

It does not remove, rename or move a route, so links, favourites, the search
index and the docs keep working. It does not change any default of the
keyboard itself. The 21 wrong defaults the audit listed are still the owner's
call and are not part of this.
