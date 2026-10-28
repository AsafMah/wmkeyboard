//! Khipro (ক্ষিপ্র): Bengali typed compositionally from lowercase Latin keys
//! (https://khipro.khiproteam.com).
//!
//! Not a phonetic scheme like Avro. Every keystroke has one meaning: `f` and
//! `/` are modifiers (ত→ট, ক→ক্ষ, and the slicer that breaks a conjunct or
//! makes ৎ and ঁ), `;` separates two parts a conjunct would otherwise fuse,
//! and `?` or `\` blind the method so the next run is literal Latin. So a word
//! has exactly one reading and there is nothing to rank: what [`convert`]
//! returns is what the user typed.
//!
//! Two editions exist. Classic joins consonants into conjuncts as they are
//! typed and uses `/` to break one apart. Dark leaves consonants apart and
//! joins them on demand: one, two or three `/` after a run of consonants fuse
//! its last two, three or four (`ntrz///` is ন্ত্র্য).
//!
//! The method is defined as an m17n input method (`bn-khipro.mim`), a small
//! state machine over maps of key sequences. Rather than transcribe its ~200
//! conjunct rules, where they would drift from every upstream fix, this runs
//! the official spec itself: the `.mim` files under `data/khipro/` are
//! compiled in verbatim and [`Spec`] interprets them. The interpreter
//! follows KhiproTeam's own `khipro-library`, which re-runs the whole roman
//! tape through the spec on every key, and is conformance-tested against the
//! same `khipro-testcases` tables. One place follows m17n-lib instead: a key
//! the current state has no rule for is read again from `init` rather than
//! written as itself, which Dark's `fusion_possible` state depends on.
//!
//! Only the subset of m17n the Khipro specs use: `map` blocks of
//! `(keys actions… "output")` entries, `state` blocks whose rules name a map
//! and list actions to run when one of its keys matches, and `macro` blocks
//! of named action lists, expanded where they are called. Actions are `set`,
//! `insert`, `delete` and `move` to a position (`@-N`, `@+N`, `@<`, `@>` or
//! a marker), `mark`, `shift`, `cond` over `=`/`<`/`>`/`<=`/`>=`/`&`/`|`,
//! `commit` (a no-op here: the composing buffer is the whole word) and `undo`.
//!
//! Markers move with the text the way m17n-lib moves them: an insertion
//! shifts every marker after the insertion point (one exactly at it stays
//! put, which is what lets Dark mark the boundary before the next consonant),
//! and a deletion pulls the markers inside it to its start.
//!
//! Positions count Unicode scalar values. The Kotlin original counts UTF-16
//! units; the two agree on everything in the Bengali block and differ only
//! when a non-BMP character (an emoji) sits inside a word, where this one is
//! the saner of the two.
//!
//! Khipro is updated on a rolling basis, so the app can [`install`] a newer
//! spec set from its data repository over the bundled one, once [`check`]
//! has run each spec against its own conformance table (#909).
//!
//! Upstream: rank-coder/khipro-m17n, KhiproTeam/khipro-mim-touchscreen,
//! KhiproTeam/khipro-dark-m17n and KhiproTeam/khipro-dark-mim-touchscreen
//! (all MIT, © 2024 rank_coder); `scripts/update-khipro.sh` refreshes them.
//! Interpreter semantics from KhiproTeam/khipro-library (MIT, © 2026
//! KhiproTeam), and m17n-lib's own for the marker commands only Dark uses.

use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, OnceLock, RwLock};

use crate::sexp::Sexp;
use crate::sort;

/// Which spec a keystroke is read against: the edition, and whether the key
/// came from a touchscreen or a hardware keyboard. Within an edition the two
/// differ only in what a phone keyboard already has keys for: the desktop
/// spec turns digits into Bengali digits, `.` into the danda and `$` into ৳,
/// the touchscreen one leaves all of that to the keys and spells ॥ as `।f`.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Variant {
    Touchscreen,
    Desktop,
    DarkTouchscreen,
    DarkDesktop,
}

impl Variant {
    pub const ALL: [Variant; 4] = [
        Variant::Touchscreen,
        Variant::Desktop,
        Variant::DarkTouchscreen,
        Variant::DarkDesktop,
    ];

    /// The spec of edition `dark` for a key typed on a hardware keyboard or not.
    pub fn of(dark: bool, desktop: bool) -> Variant {
        match (dark, desktop) {
            (false, false) => Variant::Touchscreen,
            (false, true) => Variant::Desktop,
            (true, false) => Variant::DarkTouchscreen,
            (true, true) => Variant::DarkDesktop,
        }
    }

    pub fn dark(self) -> bool {
        matches!(self, Variant::DarkTouchscreen | Variant::DarkDesktop)
    }

    pub fn desktop(self) -> bool {
        matches!(self, Variant::Desktop | Variant::DarkDesktop)
    }

    /// The `.mim` text, compiled in from the Android app's resources so both
    /// implementations run the same upstream revision. Deflated by build.rs,
    /// inflated here once per variant, when its spec is parsed.
    fn source(self) -> String {
        macro_rules! spec_file {
            ($name:literal) => {
                include_bytes!(concat!(env!("OUT_DIR"), "/", $name, ".deflate"))
            };
        }
        let packed: &[u8] = match self {
            Variant::Touchscreen => spec_file!("bn-khipro-touchscreen.mim"),
            Variant::Desktop => spec_file!("bn-khipro.mim"),
            Variant::DarkTouchscreen => spec_file!("bn-khipro-dark-touchscreen.mim"),
            Variant::DarkDesktop => spec_file!("bn-khipro-dark.mim"),
        };
        let text = miniz_oxide::inflate::decompress_to_vec(packed).expect("bundled Khipro spec inflates");
        String::from_utf8(text).expect("bundled Khipro spec is UTF-8")
    }

    fn bundled(self) -> &'static Arc<Spec> {
        static SPECS: [OnceLock<Arc<Spec>>; 4] = [
            OnceLock::new(),
            OnceLock::new(),
            OnceLock::new(),
            OnceLock::new(),
        ];
        SPECS[self as usize].get_or_init(|| {
            Arc::new(Spec::parse(&self.source()).expect("bundled Khipro spec parses"))
        })
    }

    /// The spec keys are read against: one [`install`]ed at run time, or the
    /// bundled one.
    fn spec(self) -> Arc<Spec> {
        let installed = INSTALLED[self as usize]
            .read()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .clone();
        installed.unwrap_or_else(|| self.bundled().clone())
    }
}

/// Specs installed at run time over the bundled ones, by [`Variant`] ordinal.
/// Khipro is updated on a rolling basis, and the app fetches newer specs from
/// its data repository rather than waiting for a release to carry them.
static INSTALLED: [RwLock<Option<Arc<Spec>>>; 4] =
    [RwLock::new(None), RwLock::new(None), RwLock::new(None), RwLock::new(None)];

/// Moves on every [`install`] and [`reset`], for what is derived from a spec
/// (the glide romanizer's tables) to know it is stale.
static GENERATION: AtomicU64 = AtomicU64::new(0);

/// When the bundled specs were taken from upstream, as an RFC 3339 UTC time
/// (`data/khipro/REVISION`, written by `scripts/update-khipro.sh`). A
/// published set is newer than the bundled one when its revision sorts after
/// this.
pub fn bundled_revision() -> &'static str {
    include_str!("../data/khipro/REVISION").trim()
}

/// The Bengali `roman` spells under `variant`. Empty in, empty out.
pub fn convert(roman: &str, variant: Variant) -> String {
    if roman.is_empty() {
        String::new()
    } else {
        variant.spec().convert(roman)
    }
}

/// Parses `variant`'s spec now rather than on the first keystroke.
pub fn warm(variant: Variant) {
    variant.spec();
}

/// Reads `variant`'s keys against `text`, an m17n spec, from now on in
/// place of the bundled one. Refused, leaving the spec in use as it was,
/// when `text` does not parse or reads none of the method's basic words;
/// run [`check`] against the spec's own conformance table first.
pub fn install(variant: Variant, text: &str) -> Result<(), String> {
    let spec = Spec::parse(text)?;
    spec.sane()?;
    *INSTALLED[variant as usize]
        .write()
        .unwrap_or_else(|poisoned| poisoned.into_inner()) = Some(Arc::new(spec));
    GENERATION.fetch_add(1, Ordering::Relaxed);
    Ok(())
}

/// Goes back to the bundled spec for `variant`.
pub fn reset(variant: Variant) {
    let was = INSTALLED[variant as usize]
        .write()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
        .take();
    if was.is_some() {
        GENERATION.fetch_add(1, Ordering::Relaxed);
    }
}

/// Whether `variant` reads against an [`install`]ed spec.
pub fn is_installed(variant: Variant) -> bool {
    INSTALLED[variant as usize]
        .read()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
        .is_some()
}

/// See [`GENERATION`].
pub fn generation() -> u64 {
    GENERATION.load(Ordering::Relaxed)
}

/// Runs `text`, an m17n spec, against `cases`, a Khipro conformance table
/// (a header line, then `input<TAB>expected` per line), without installing
/// it. `skip` is a table of the same shape listing cases upstream has left
/// stale, passed over when both columns match. Returns how many cases ran,
/// or why the spec is refused: it does not parse, the table holds too few
/// cases to vouch for it, or some differ (the first few are named).
pub fn check(text: &str, cases: &str, skip: &str) -> Result<usize, String> {
    let spec = Spec::parse(text)?;
    spec.sane()?;
    spec.conforms(&parse_cases(cases), &parse_cases(skip))
}

/// Fewer cases than this cannot vouch for a spec: every upstream table has
/// about a hundred.
const MIN_CASES: usize = 50;

/// Failures named in a [`check`] refusal; the count covers the rest.
const NAMED_FAILURES: usize = 8;

/// The `input<TAB>expected` rows of a conformance table, past its header.
fn parse_cases(text: &str) -> Vec<(String, String)> {
    text.lines()
        .skip(1)
        .filter(|line| !line.trim().is_empty())
        .map(|line| {
            let mut parts = line.split('\t');
            (
                parts.next().unwrap_or("").trim().to_string(),
                parts.next().unwrap_or("").trim().to_string(),
            )
        })
        .collect()
}

const INITIAL: &str = "init";
const MAX_MACRO_DEPTH: usize = 8;

/// A parsed m17n input method, and the interpreter that runs a roman tape
/// through it.
pub struct Spec {
    maps: HashMap<String, Vec<Entry>>,
    states: HashMap<String, State>,
}

struct Entry {
    key: Vec<char>,
    actions: Vec<Action>,
}

struct Rule {
    map: String,
    actions: Vec<Action>,
}

struct State {
    entry: Vec<Action>,
    rules: Vec<Rule>,
}

/// Where a `move` or `delete` goes.
enum Position {
    /// This many characters from the cursor, negative to the left.
    Relative(i64),
    Start,
    End,
    Marker(String),
}

enum Action {
    SetVar(String, String),
    Insert(Vec<char>),
    /// Deletes from the cursor to the position, on whichever side of it that is.
    Delete(Position),
    Move(Position),
    Mark(String),
    Shift(String),
    Commit,
    Undo,
    Cond(Vec<(Condition, Vec<Action>)>),
}

#[derive(Clone, Copy)]
enum Op {
    Eq,
    Lt,
    Gt,
    Le,
    Ge,
}

enum Condition {
    Always,
    /// A variable against a value, a number or another variable, by the operator.
    Compare {
        op: Op,
        variable: String,
        value: String,
    },
    And(Box<Condition>, Box<Condition>),
    Or(Box<Condition>, Box<Condition>),
}

/// The mutable run of one conversion.
struct Run<'a> {
    spec: &'a Spec,
    out: Vec<char>,
    cursor: usize,
    vars: HashMap<String, i64>,
    marks: HashMap<String, usize>,
    state: String,
}

impl<'a> Run<'a> {
    fn new(spec: &'a Spec) -> Run<'a> {
        Run {
            spec,
            out: Vec::new(),
            cursor: 0,
            vars: HashMap::new(),
            marks: HashMap::new(),
            state: INITIAL.to_string(),
        }
    }

    fn enter(&mut self, name: &str) {
        self.state = name.to_string();
        if let Some(state) = self.spec.states.get(name) {
            self.execute(&state.entry);
        }
    }

    fn value(&self, operand: &str) -> i64 {
        operand
            .parse::<i64>()
            .ok()
            .or_else(|| self.vars.get(operand).copied())
            .unwrap_or(0)
    }

    fn resolve(&self, position: &Position) -> usize {
        let len = self.out.len() as i64;
        let at = match position {
            Position::Relative(by) => self.cursor as i64 + by,
            Position::Start => 0,
            Position::End => len,
            Position::Marker(name) => self.marks.get(name).copied().unwrap_or(0) as i64,
        };
        at.clamp(0, len) as usize
    }

    fn insert(&mut self, text: &[char]) {
        if text.is_empty() {
            return;
        }
        self.cursor = self.cursor.min(self.out.len());
        self.out
            .splice(self.cursor..self.cursor, text.iter().copied());
        for mark in self.marks.values_mut() {
            if *mark > self.cursor {
                *mark += text.len();
            }
        }
        self.cursor += text.len();
    }

    fn delete(&mut self, to: usize) {
        let len = self.out.len();
        let from = self.cursor.min(to).min(len);
        let end = self.cursor.max(to).min(len);
        if from == end {
            return;
        }
        self.out.drain(from..end);
        for mark in self.marks.values_mut() {
            if *mark > end {
                *mark -= end - from;
            } else if *mark > from {
                *mark = from;
            }
        }
        self.cursor = from;
    }

    fn execute(&mut self, actions: &[Action]) {
        for action in actions {
            match action {
                Action::SetVar(name, value) => {
                    let value = self.value(value);
                    self.vars.insert(name.clone(), value);
                }
                Action::Insert(text) => self.insert(text),
                Action::Delete(to) => {
                    let to = self.resolve(to);
                    self.delete(to);
                }
                Action::Move(to) => self.cursor = self.resolve(to),
                Action::Mark(name) => {
                    let at = self.cursor.min(self.out.len());
                    self.marks.insert(name.clone(), at);
                }
                Action::Shift(state) => self.enter(state),
                Action::Commit => {}
                Action::Undo => {
                    if self.cursor > 0 && !self.out.is_empty() {
                        self.delete(self.cursor - 1);
                    }
                }
                Action::Cond(branches) => {
                    if let Some((_, actions)) =
                        branches.iter().find(|(condition, _)| self.holds(condition))
                    {
                        self.execute(actions);
                    }
                }
            }
        }
    }

    fn holds(&self, condition: &Condition) -> bool {
        match condition {
            Condition::Always => true,
            Condition::Compare {
                op,
                variable,
                value,
            } => {
                let left = self.vars.get(variable).copied().unwrap_or(0);
                let right = self.value(value);
                match op {
                    Op::Eq => left == right,
                    Op::Lt => left < right,
                    Op::Gt => left > right,
                    Op::Le => left <= right,
                    Op::Ge => left >= right,
                }
            }
            Condition::And(left, right) => self.holds(left) && self.holds(right),
            Condition::Or(left, right) => self.holds(left) || self.holds(right),
        }
    }
}

impl Spec {
    /// Words every edition and form of Khipro has always typed the same way.
    /// A file that parses but reads none of them is not a Khipro spec at all
    /// (an HTML error page parses as atoms).
    fn sane(&self) -> Result<(), String> {
        const WORDS: [(&str, &str); 3] = [("ami", "আমি"), ("tumi", "তুমি"), ("baxla", "বাংলা")];
        if !self.states.contains_key(INITIAL) {
            return Err("no init state".to_string());
        }
        for (input, expected) in WORDS {
            let actual = self.convert(input);
            if actual != expected {
                return Err(format!("{input}: expected {expected}, got {actual}"));
            }
        }
        Ok(())
    }

    /// Runs `cases` (see [`check`]) through this spec.
    fn conforms(&self, cases: &[(String, String)], skip: &[(String, String)]) -> Result<usize, String> {
        if cases.len() < MIN_CASES {
            return Err(format!("{} conformance cases, fewer than {MIN_CASES}", cases.len()));
        }
        let failures: Vec<String> = cases
            .iter()
            .filter(|case| !skip.contains(case))
            .filter_map(|(input, expected)| {
                let actual = self.convert(input);
                (&actual != expected).then(|| format!("{input}: expected {expected}, got {actual}"))
            })
            .collect();
        if failures.is_empty() {
            Ok(cases.len())
        } else {
            Err(format!(
                "{} of {} cases differ:\n{}",
                failures.len(),
                cases.len(),
                failures.iter().take(NAMED_FAILURES).cloned().collect::<Vec<_>>().join("\n")
            ))
        }
    }

    /// Runs the roman tape `input` through the method.
    pub fn convert(&self, input: &str) -> String {
        let input: Vec<char> = input.chars().collect();
        let mut run = Run::new(self);
        run.enter(INITIAL);
        let mut i = 0;
        while i < input.len() {
            // A blinder (`?`, `\`) and what it leaves Latin are the spec's own
            // `blind_state`, as in the library since it dropped its hard-coded
            // collapse of a doubled blinder.
            let Some(state) = self.states.get(&run.state) else {
                break;
            };
            let mut best: Option<(&Entry, &Rule)> = None;
            for rule in &state.rules {
                let Some(entries) = self.maps.get(&rule.map) else {
                    continue;
                };
                for entry in entries {
                    // Each map is sorted longest key first, so the first hit in
                    // it is its longest; a longer hit in a later map wins.
                    if entry.key.len() <= best.map_or(0, |(entry, _)| entry.key.len()) {
                        break;
                    }
                    if input[i..].starts_with(&entry.key) {
                        best = Some((entry, rule));
                        break;
                    }
                }
            }
            match best {
                None if run.state != INITIAL => {
                    // Nothing in this state reads the character: the method
                    // starts afresh and reads it again from `init`, as m17n-lib
                    // does. Dark's `fusion_possible` relies on it: a consonant
                    // after a vowel there begins the next run.
                    run.enter(INITIAL);
                }
                None => {
                    // Not even `init` reads it: it is written as itself.
                    run.insert(&input[i..i + 1]);
                    i += 1;
                }
                Some((entry, rule)) => {
                    run.execute(&entry.actions);
                    run.execute(&rule.actions);
                    run.cursor = run.cursor.min(run.out.len());
                    i += entry.key.len();
                }
            }
        }
        run.out.into_iter().collect()
    }

    /// Parses an m17n file. Errors are malformed S-expressions only; unknown
    /// commands are skipped, as the Kotlin interpreter skips them.
    pub fn parse(text: &str) -> Result<Spec, String> {
        let nodes: Vec<Sexp> = Sexp::parse_all(text)?
            .into_iter()
            .filter(|node| matches!(node, Sexp::Node(_)))
            .collect();
        // Macros first: the specs define them after the states that call them.
        let mut macros: HashMap<String, Vec<Sexp>> = HashMap::new();
        for node in &nodes {
            if node.head() != Some("macro") {
                continue;
            }
            for body in node.items().iter().skip(1) {
                if let Some(name) = body.head() {
                    macros.insert(name.to_string(), body.items()[1..].to_vec());
                }
            }
        }
        let parser = Parser { macros };
        let mut maps = HashMap::new();
        let mut states = HashMap::new();
        for node in &nodes {
            match node.head() {
                Some("map") => {
                    for map in node.items().iter().skip(1) {
                        if let Some((name, entries)) = parser.map(map) {
                            maps.insert(name, entries);
                        }
                    }
                }
                Some("state") => {
                    for state in node.items().iter().skip(1) {
                        if let Some((name, def)) = parser.state(state) {
                            states.insert(name, def);
                        }
                    }
                }
                _ => {}
            }
        }
        Ok(Spec { maps, states })
    }
}

fn position(text: &str) -> Position {
    match text {
        "@<" => Position::Start,
        "@>" => Position::End,
        "@-" => Position::Relative(-1),
        "@+" => Position::Relative(1),
        _ if text.starts_with("@-") => Position::Relative(-text[2..].parse::<i64>().unwrap_or(1)),
        _ if text.starts_with("@+") => Position::Relative(text[2..].parse::<i64>().unwrap_or(1)),
        _ => Position::Marker(text.to_string()),
    }
}

struct Parser {
    macros: HashMap<String, Vec<Sexp>>,
}

impl Parser {
    fn map(&self, node: &Sexp) -> Option<(String, Vec<Entry>)> {
        let name = node.head()?.to_string();
        let mut entries: Vec<Entry> = node
            .items()
            .iter()
            .skip(1)
            .filter_map(|item| {
                // (BackSpace) and the like, a key the tape never holds, has a
                // list where the key would be and is skipped.
                let key: Vec<char> = item.items().first()?.atom()?.chars().collect();
                if key.is_empty() {
                    return None;
                }
                // In the order written: Dark's `zf` resets its markers before
                // its text goes in.
                let mut actions = Vec::new();
                for part in item.items().iter().skip(1) {
                    match part {
                        Sexp::Atom(text) => {
                            if !text.is_empty() {
                                actions.push(Action::Insert(text.chars().collect()));
                            }
                        }
                        Sexp::Node(_) => actions.extend(self.action(part, 0)),
                    }
                }
                Some(Entry { key, actions })
            })
            .collect();
        // Stable, so equal lengths keep the spec's own order.
        sort::by_key(&mut entries, |entry| std::cmp::Reverse(entry.key.len()));
        Some((name, entries))
    }

    fn state(&self, node: &Sexp) -> Option<(String, State)> {
        let name = node.head()?.to_string();
        let mut entry = Vec::new();
        let mut rules = Vec::new();
        for rule in node.items().iter().skip(1) {
            let Some(head) = rule.head() else { continue };
            let actions: Vec<Action> = rule
                .items()
                .iter()
                .skip(1)
                .flat_map(|item| self.action(item, 0))
                .collect();
            if head == "t" {
                entry.extend(actions);
            } else {
                rules.push(Rule {
                    map: head.to_string(),
                    actions,
                });
            }
        }
        Some((name, State { entry, rules }))
    }

    /// `node` as actions: one, none for what this subset skips, or a macro's
    /// whole body.
    fn action(&self, node: &Sexp, depth: usize) -> Vec<Action> {
        let Some(head) = node.head() else {
            return Vec::new();
        };
        let items = node.items();
        let first = items.get(1).and_then(Sexp::atom);
        let single = match head {
            "set" => match (first, items.get(2).and_then(Sexp::atom)) {
                (Some(name), Some(value)) => {
                    Some(Action::SetVar(name.to_string(), value.to_string()))
                }
                _ => None,
            },
            // `?্` is m17n's character literal: the character itself.
            "insert" => first.map(|text| {
                Action::Insert(text.strip_prefix('?').unwrap_or(text).chars().collect())
            }),
            "delete" => first.map(|text| Action::Delete(position(text))),
            "move" => first.map(|text| Action::Move(position(text))),
            "mark" => first.map(|text| Action::Mark(text.to_string())),
            "shift" => first.map(|text| Action::Shift(text.to_string())),
            "commit" => Some(Action::Commit),
            "undo" => Some(Action::Undo),
            "cond" => {
                let branches: Vec<(Condition, Vec<Action>)> = items
                    .iter()
                    .skip(1)
                    .filter_map(|branch| {
                        let parts = match branch {
                            Sexp::Node(parts) => parts,
                            Sexp::Atom(_) => return None,
                        };
                        let condition = condition(parts.first()?)?;
                        let actions = parts
                            .iter()
                            .skip(1)
                            .flat_map(|item| self.action(item, depth))
                            .collect();
                        Some((condition, actions))
                    })
                    .collect();
                if branches.is_empty() {
                    None
                } else {
                    Some(Action::Cond(branches))
                }
            }
            _ => {
                // A macro calling itself would never end; the specs never do.
                return match self.macros.get(head) {
                    Some(body) if depth <= MAX_MACRO_DEPTH => body
                        .iter()
                        .flat_map(|item| self.action(item, depth + 1))
                        .collect(),
                    _ => Vec::new(),
                };
            }
        };
        single.into_iter().collect()
    }
}

fn condition(node: &Sexp) -> Option<Condition> {
    if let Some(text) = node.atom() {
        return if text == "1" {
            Some(Condition::Always)
        } else {
            None
        };
    }
    let items = node.items();
    let head = items.first()?.atom()?;
    let op = match head {
        "1" => return Some(Condition::Always),
        "=" => Op::Eq,
        "<" => Op::Lt,
        ">" => Op::Gt,
        "<=" => Op::Le,
        ">=" => Op::Ge,
        // The specs chain three or more operands under one `|`; fold them
        // left, as m17n does.
        "&" | "|" => {
            let operands: Vec<Condition> =
                items.iter().skip(1).map(condition).collect::<Option<_>>()?;
            if operands.len() < 2 {
                return None;
            }
            return operands.into_iter().reduce(|left, right| {
                if head == "&" {
                    Condition::And(Box::new(left), Box::new(right))
                } else {
                    Condition::Or(Box::new(left), Box::new(right))
                }
            });
        }
        _ => return None,
    };
    Some(Condition::Compare {
        op,
        variable: items.get(1)?.atom()?.to_string(),
        value: items.get(2).and_then(Sexp::atom).unwrap_or("0").to_string(),
    })
}

#[cfg(test)]
mod tests {
    //! Khipro against the Khipro team's own conformance cases (the same TSVs
    //! `KhiproTest.kt` replays). A failure here is a disagreement with
    //! upstream, not a matter of taste: fix the interpreter, never the table.

    use super::*;

    const FIXTURES: &str = "../data/khipro/tests/";

    #[test]
    fn deflated_specs_inflate_to_the_files() {
        for (variant, file) in [
            (Variant::Touchscreen, include_str!("../data/khipro/bn-khipro-touchscreen.mim")),
            (Variant::Desktop, include_str!("../data/khipro/bn-khipro.mim")),
            (Variant::DarkTouchscreen, include_str!("../data/khipro/bn-khipro-dark-touchscreen.mim")),
            (Variant::DarkDesktop, include_str!("../data/khipro/bn-khipro-dark.mim")),
        ] {
            assert_eq!(variant.source(), file, "{variant:?}");
        }
    }

    /// Cases upstream's table still has from before a spec change that
    /// overturned them, as (input, the stale expectation). Matched on the
    /// expectation too, so a case runs again once upstream corrects it.
    ///
    /// `kso/`: Dark's 2026-10-18 spec moved a vowel after two or more
    /// consonants to `fusion_possible`, where `/` fuses them (`cnd/r/bindu/`
    /// in the same table relies on it), so it is now ক্স, not কসঁ; the table
    /// was last touched 2026-10-14.
    ///
    /// `........`: the touch table of 2026-10-20 expects a run of full stops
    /// to lose one, as a run of commas does, but the touchscreen spec shipped
    /// with it sets `overflow` only from `,,,`: no rule in it reads a dot
    /// run, so every dot stays a dot.
    const STALE_UPSTREAM: &[(&str, &str)] = &[("kso/", "কসঁ"), ("........", ".......")];

    fn assert_conforms(name: &str, text: &str, variant: Variant) {
        let skip: Vec<(String, String)> =
            STALE_UPSTREAM.iter().map(|(input, expected)| (input.to_string(), expected.to_string())).collect();
        if let Err(why) = variant.spec().conforms(&parse_cases(text), &skip) {
            panic!("{FIXTURES}{name}: {why}");
        }
    }

    #[test]
    fn touchscreen_spec_passes_the_touch_conformance_cases() {
        assert_conforms(
            "khipro-testcases-touch.tsv",
            include_str!("../data/khipro/tests/khipro-testcases-touch.tsv"),
            Variant::Touchscreen,
        );
    }

    #[test]
    fn desktop_spec_passes_the_desktop_conformance_cases() {
        assert_conforms(
            "khipro-testcases.tsv",
            include_str!("../data/khipro/tests/khipro-testcases.tsv"),
            Variant::Desktop,
        );
    }

    #[test]
    fn dark_touchscreen_spec_passes_the_dark_touch_conformance_cases() {
        assert_conforms(
            "khipro-dark-testcases-touch.tsv",
            include_str!("../data/khipro/tests/khipro-dark-testcases-touch.tsv"),
            Variant::DarkTouchscreen,
        );
    }

    #[test]
    fn dark_desktop_spec_passes_the_dark_desktop_conformance_cases() {
        assert_conforms(
            "khipro-dark-testcases.tsv",
            include_str!("../data/khipro/tests/khipro-dark-testcases.tsv"),
            Variant::DarkDesktop,
        );
    }

    #[test]
    fn dark_joins_consonants_only_on_the_slash_as_many_as_it_is_pressed() {
        let dark = Variant::DarkTouchscreen;
        assert_eq!("লাগবে", convert("lagbe", dark));
        assert_eq!("নতর\u{200D}্য", convert("ntrz/", dark));
        assert_eq!("নত্র্য", convert("ntrz//", dark));
        assert_eq!("ন্ত্র্য", convert("ntrz///", dark));
        assert_eq!("চন্দ্রবিন্দু", convert("cndr//bind/u", dark));
    }

    #[test]
    fn everyday_words() {
        let t = Variant::Touchscreen;
        assert_eq!("আমি", convert("ami", t));
        assert_eq!("তুমি", convert("tumi", t));
        assert_eq!("ক্ষিপ্র", convert("kkhipr", t));
        assert_eq!("বুধবার", convert("budh;bar", t));
        assert_eq!("আমিই", convert("ami;i", t));
        assert_eq!("ভালো", convert("valw", t));
        assert_eq!("বাংলা", convert("baxla", t));
    }

    #[test]
    fn the_slicer_after_a_vowel_is_a_chandrabindu() {
        assert_eq!("চাঁদ", convert("ca/d", Variant::Touchscreen));
    }

    #[test]
    fn the_desktop_spec_types_bengali_digits_and_the_danda_the_touch_spec_leaves_them() {
        assert_eq!("১২৩", convert("123", Variant::Desktop));
        assert_eq!("123", convert("123", Variant::Touchscreen));
        assert_eq!("কথা।", convert("kotha.", Variant::Desktop));
    }

    #[test]
    fn the_touch_spec_builds_the_double_danda_and_the_abbreviation_sign_on_the_danda() {
        let t = Variant::Touchscreen;
        assert_eq!("।", convert("।", t));
        assert_eq!("॥", convert("।f", t));
        assert_eq!("৺", convert("।ff", t));
        assert_eq!("আমি॥", convert("ami।f", t));
        // Any other key after it starts afresh.
        assert_eq!("আমি।ত", convert("ami।t", t));
    }

    #[test]
    fn nukta_letters_come_out_precomposed_as_the_word_lists_spell_them() {
        let word = convert("barfi", Variant::Touchscreen);
        assert!(word.contains('\u{9DC}'), "{word}");
        assert!(!word.contains('\u{9BC}'), "{word}");
    }

    #[test]
    fn empty_in_empty_out() {
        assert_eq!("", convert("", Variant::Touchscreen));
    }

    #[test]
    fn an_emoji_inside_a_word_is_one_character_to_the_cursor() {
        // Non-BMP input: written through as itself, and the method restarts.
        assert_eq!("আমি😀আমি", convert("ami😀ami", Variant::Touchscreen));
    }

    #[test]
    fn a_spec_is_checked_against_its_own_table_before_it_is_trusted() {
        let spec = include_str!("../data/khipro/bn-khipro-touchscreen.mim");
        let table = include_str!("../data/khipro/tests/khipro-testcases-touch.tsv");
        let skip = "input\texpected\n........\t.......\n";
        assert!(check(spec, table, skip).unwrap() > 50);
        // The stale case is a real difference, so without the skip it is refused.
        assert!(check(spec, table, "").unwrap_err().contains("........"));
        // The desktop table expects Bengali digits the touch spec leaves alone.
        let desktop = include_str!("../data/khipro/tests/khipro-testcases.tsv");
        assert!(check(spec, desktop, "").is_err());
        // A table too short to vouch for anything.
        assert!(check(spec, "input\texpected\nami\tআমি\n", "").is_err());
        // Not a spec: an error page parses, but types nothing.
        assert!(check("<html>404: Not Found</html>", table, "").is_err());
        assert!(check("(map (unclosed", table, "").is_err());
    }

    #[test]
    fn an_installed_spec_replaces_the_bundled_one_until_reset() {
        // Dark desktop only, which no other test installs over, and the bundled
        // text itself, so the conformance test reading the same variant
        // meanwhile types the same whichever copy it meets.
        let variant = Variant::DarkDesktop;
        let bundled = include_str!("../data/khipro/bn-khipro-dark.mim");
        assert!(!is_installed(variant));
        assert!(install(variant, "<html>404</html>").is_err());
        assert!(!is_installed(variant), "a refused spec leaves the bundled one in use");
        let before = generation();
        install(variant, bundled).unwrap();
        assert!(is_installed(variant));
        assert!(generation() > before);
        assert_eq!("আমি", convert("ami", variant));
        reset(variant);
        assert!(!is_installed(variant));
        assert_eq!("আমি", convert("ami", variant));
    }

    #[test]
    fn the_bundled_revision_is_a_utc_time() {
        let revision = bundled_revision();
        assert_eq!(20, revision.len(), "{revision}");
        assert!(revision.ends_with('Z') && revision.as_bytes()[10] == b'T', "{revision}");
    }

    #[test]
    fn variants_resolve_from_their_flags() {
        for variant in Variant::ALL {
            assert_eq!(variant, Variant::of(variant.dark(), variant.desktop()));
        }
    }
}
