//! The built-in glide decoder (`glide::Decoder`, GlideBeam) on real swipes:
//! FUTO's MIT swipe.futo.org strokes, read straight from the dataset's jsonl.
//! See native/bench/futo-swipe/README.md for the download.
//!
//! cargo run --release --example glide_beam_eval -- <records.jsonl> <qwerty.json> <word list>
//!
//! Reads the strokes whose word the list holds (`SKIP` and `LIMIT` pick a
//! slice of them), then one tuning per line from stdin and prints a line of
//! results for each, so a fitting script can keep one process and its parsed
//! strokes. A tuning line is `name=value` pairs over `Tuning`'s fields
//! (`sigma=0.45 shape_weight=2`); an empty line is the shipped tuning. With
//! stdin closed at once (`< /dev/null`) it measures the shipped tuning only.
//!
//! Prints top-1 and top-4 over all strokes, then top-1 over the short words
//! with a doubled letter (#886) and over the words of six letters or more.
//!
//! For a layout that is not QWERTY, and for the numbers a language profile
//! is judged on (#966): `COLS` is how many keys wide the canvas is (10
//! unless set; German QWERTZ is 11), `RESULTS` how many candidates a decode
//! returns (4; the keyboard's strip shows more), `ALL=1` scores every
//! stroke, a word the list lacks as a miss, so the denominator is the
//! swipes and not the list (success per swipe), and `DUMP=<path>` writes
//! one line per stroke (`word`, top-1, hit, in list, then every candidate
//! as `word:score` joined by `|`, best first) for a regression set or a
//! rerank sweep over the scored pool.
//! Words are read in any alphabet, not Latin letters alone.
//!
//! `CHAIN=1` measures chained strokes instead (#968): consecutive records on
//! the same canvas are joined the way a glide across the spacebar reaches
//! the decoder, the first word's stroke continued straight down to the
//! bar's edge and the second's begun there, and each half is decoded as the
//! keyboard decodes it, with the walk read as an open end, against the
//! plain decode of the same half.

use std::collections::HashSet;
use std::io::{BufRead, Write};

use wm_engine::fuzzy::Tier;
use wm_engine::glide::keymap::KeyMap;
use wm_engine::glide::{open_run, Decoder, Point, Source, Tuning};
use wm_engine::user_lexicon::UserTrie;

struct Record {
    word: String,
    width: f32,
    height: f32,
    points: Vec<Point>,
}

fn number(v: &serde_json::Value, key: &str) -> f32 {
    v[key].as_f64().unwrap_or(0.0) as f32
}

/// A key's width on a canvas `COLS` keys across: a tenth of it on the
/// dataset's QWERTY (`GlideRealSwipeEval` reads it the same way), an
/// eleventh on its German layout.
fn key_width(canvas_width: f32) -> f32 {
    let cols: f32 = std::env::var("COLS").ok().and_then(|v| v.parse().ok()).unwrap_or(10.0);
    canvas_width / cols
}

/// The dataset's own key centres, in key widths.
fn keymap(layout: &[(i32, f32, f32)], width: f32, height: f32) -> KeyMap {
    let kw = key_width(width);
    let mut order: Vec<usize> = (0..layout.len()).collect();
    order.sort_by_key(|&i| layout[i].0);
    let xs = layout.iter().map(|k| k.1 * width / kw).collect();
    let ys = layout.iter().map(|k| k.2 * height / kw).collect();
    let sorted = order.iter().map(|&i| layout[i].0).collect();
    let key_of = order.iter().map(|&i| i as i32).collect();
    KeyMap::new(xs, ys, sorted, key_of)
}

fn set(t: &mut Tuning, name: &str, value: f64) {
    match name {
        "sigma" => t.sigma = value as f32,
        "max_point_cost" => t.max_point_cost = value as f32,
        "gap_weight" => t.gap_weight = value as f32,
        "gap_window" => t.gap_window = value as f32,
        "shape_weight" => t.shape_weight = value,
        "freq_weight" => t.freq_weight = value,
        "repeat_cost" => t.repeat_cost = value as f32,
        "unlooped_repeat" => t.unlooped_repeat = value as f32,
        "dwell_full" => t.dwell_full = value as f32,
        "unclaimed_dwell" => t.unclaimed_dwell = value as f32,
        "loop_extent" => t.loop_extent = value as f32,
        "loop_min_arc" => t.loop_min_arc = value as f32,
        "loop_radius" => t.loop_radius = value as f32,
        "wiggle_extent" => t.wiggle_extent = value as f32,
        "wiggle_weight" => t.wiggle_weight = value as f32,
        "loop_exclusion" => t.loop_exclusion = value as f32,
        "unclaimed_loop" => t.unclaimed_loop = value as f32,
        "shape_channel" => t.shape_channel = value,
        "start_radius" => t.start_radius = value as f32,
        "end_radius" => t.end_radius = value as f32,
        "near_radius" => t.near_radius = value as f32,
        "joiner_cost" => t.joiner_cost = value as f32,
        "join_cost" => t.join_cost = value,
        "join_min_part" => t.join_min_part = value as i32,
        "join_part_penalty" => t.join_part_penalty = value,
        _ => panic!("no tuning field {name}"),
    }
}

fn doubled(w: &str) -> bool {
    w.as_bytes().windows(2).any(|p| p[0] == p[1])
}

#[derive(Default, Clone, Copy)]
struct Tally {
    n: usize,
    top1: usize,
    top4: usize,
    short_n: usize,
    short_top1: usize,
    long_n: usize,
    long_top1: usize,
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let layout_json: serde_json::Value =
        serde_json::from_str(&std::fs::read_to_string(&args[2]).expect("layout")).expect("layout json");
    let layout: Vec<(i32, f32, f32)> = layout_json["keys"]
        .as_array()
        .expect("keys")
        .iter()
        .filter_map(|k| {
            let letter = k["letter"].as_str()?.chars().next()? as i32;
            Some((letter, number(k, "cx"), number(k, "cy")))
        })
        .collect();
    let text = std::fs::read_to_string(&args[3]).expect("word list");
    let mut trie = UserTrie::new();
    let mut known: HashSet<String> = HashSet::new();
    for (word, frequency) in wm_engine::wordlist::parse(&text) {
        known.insert(word.to_lowercase());
        trie.insert(&word, frequency);
    }
    let skip = std::env::var("SKIP").ok().and_then(|v| v.parse().ok()).unwrap_or(0usize);
    let limit = std::env::var("LIMIT").ok().and_then(|v| v.parse().ok()).unwrap_or(usize::MAX);
    let threads = std::env::var("THREADS").ok().and_then(|v| v.parse().ok()).unwrap_or(8usize);
    let all = std::env::var("ALL").is_ok_and(|v| v == "1");
    let results = std::env::var("RESULTS").ok().and_then(|v| v.parse().ok()).unwrap_or(4usize).max(1);
    let dump = std::env::var("DUMP").ok().filter(|p| !p.is_empty());
    let file = std::io::BufReader::new(std::fs::File::open(&args[1]).expect("records"));
    let records: Vec<Record> = file
        .lines()
        .map_while(Result::ok)
        .filter_map(|line| {
            let v: serde_json::Value = serde_json::from_str(&line).ok()?;
            let word = v["word"].as_str()?.to_lowercase();
            if word.chars().count() < 2 || !word.chars().all(char::is_alphabetic) || (!all && !known.contains(&word)) {
                return None;
            }
            let (width, height) = (number(&v, "canvas_width"), number(&v, "canvas_height"));
            let points = v["data"]
                .as_array()?
                .iter()
                .map(|p| Point { x: number(p, "x") * width, y: number(p, "y") * height, t: p["t"].as_i64().unwrap_or(0) })
                .collect();
            Some(Record { word, width, height, points })
        })
        .skip(skip)
        .take(limit)
        .collect();
    eprintln!("{} strokes", records.len());

    if std::env::var("CHAIN").is_ok_and(|v| v == "1") {
        chained(&records, &layout, &trie);
        return;
    }
    let run = |tuning: &Tuning| -> Tally {
        let dump_on = dump.is_some();
        let chunk = records.len().div_ceil(threads).max(1);
        let tallies: Vec<(Tally, Vec<String>)> = std::thread::scope(|scope| {
            let handles: Vec<_> = records
                .chunks(chunk)
                .map(|part| {
                    let (trie, layout, known) = (&trie, &layout, &known);
                    scope.spawn(move || {
                        let sources = [Source { walker: trie, log_weight: 0.0, tier: Tier::Dictionary, min_frequency: 0 }];
                        let mut decoder = Decoder::new(tuning.clone());
                        let mut t = Tally::default();
                        let mut lines: Vec<String> = Vec::new();
                        for r in part {
                            let keys = keymap(layout, r.width, r.height);
                            let out = decoder.decode(&r.points, &keys, key_width(r.width), &sources, results, None, 0);
                            let words: Vec<String> = out.iter().map(|c| String::from_utf16_lossy(&c.word).to_lowercase()).collect();
                            let hit = words.first() == Some(&r.word);
                            if dump_on {
                                let top = words.first().map(String::as_str).unwrap_or("-");
                                let pool = out
                                    .iter()
                                    .map(|c| format!("{}:{:.6}", String::from_utf16_lossy(&c.word).to_lowercase(), c.score))
                                    .collect::<Vec<_>>()
                                    .join("|");
                                lines.push(format!(
                                    "{}\t{top}\t{}\t{}\t{pool}",
                                    r.word,
                                    hit as u8,
                                    known.contains(&r.word) as u8
                                ));
                            }
                            t.n += 1;
                            t.top1 += hit as usize;
                            t.top4 += words.iter().any(|w| *w == r.word) as usize;
                            if r.word.len() <= 4 && doubled(&r.word) {
                                t.short_n += 1;
                                t.short_top1 += hit as usize;
                            }
                            if r.word.len() >= 6 {
                                t.long_n += 1;
                                t.long_top1 += hit as usize;
                            }
                        }
                        (t, lines)
                    })
                })
                .collect();
            handles.into_iter().map(|h| h.join().unwrap()).collect()
        });
        if let Some(path) = &dump {
            let text: String = tallies.iter().flat_map(|(_, lines)| lines.iter()).map(|l| format!("{l}\n")).collect();
            std::fs::write(path, text).expect("dump");
        }
        let mut t = Tally::default();
        for (x, _) in tallies {
            t.n += x.n;
            t.top1 += x.top1;
            t.top4 += x.top4;
            t.short_n += x.short_n;
            t.short_top1 += x.short_top1;
            t.long_n += x.long_n;
            t.long_top1 += x.long_top1;
        }
        t
    };
    let report = |t: Tally| {
        let f = |a: usize, b: usize| a as f64 / b.max(1) as f64;
        println!(
            "top1 {:.4} top{results} {:.4} n {} | short doubled {:.4} n {} | 6+ letters {:.4} n {}",
            f(t.top1, t.n),
            f(t.top4, t.n),
            t.n,
            f(t.short_top1, t.short_n),
            t.short_n,
            f(t.long_top1, t.long_n),
            t.long_n
        );
        std::io::stdout().flush().ok();
    };

    let mut any = false;
    for line in std::io::stdin().lock().lines().map_while(Result::ok) {
        any = true;
        let mut tuning = Tuning::default();
        for pair in line.split_whitespace() {
            let (name, value) = pair.split_once('=').expect("name=value");
            set(&mut tuning, name, value.parse().expect("number"));
        }
        report(run(&tuning));
    }
    if !any {
        report(run(&Tuning::default()));
    }
}

/// A straight walk from `from` to `to`, a fifth of a key per sample.
fn walk(from: Point, to: Point, key_width: f32) -> Vec<Point> {
    let d = ((to.x - from.x).powi(2) + (to.y - from.y).powi(2)).sqrt();
    let steps = ((d / (key_width * 0.2)).ceil() as usize).max(1);
    (1..=steps)
        .map(|i| {
            let f = i as f32 / steps as f32;
            Point { x: from.x + (to.x - from.x) * f, y: from.y + (to.y - from.y) * f, t: from.t + 8 * i as i64 }
        })
        .collect()
}

/// The chained-stroke measurement (#968). Each consecutive pair of records
/// on one canvas becomes the two halves of a stroke that crossed the
/// spacebar: the first continued straight down to the bar's top edge, the
/// second begun there. Both halves are decoded plain (what the keyboard did
/// before) and with the walk as an open end (what it does now), and the
/// whole-pair hit rate too.
fn chained(records: &[Record], layout: &[(i32, f32, f32)], trie: &UserTrie) {
    let sources = [Source { walker: trie, log_weight: 0.0, tier: Tier::Dictionary, min_frequency: 0 }];
    let mut decoder = Decoder::new(Tuning::default());
    let (mut n, mut first_plain, mut first_open, mut second_plain, mut second_open, mut both_plain, mut both_open) =
        (0usize, 0usize, 0usize, 0usize, 0usize, 0usize, 0usize);
    let mut open_tails = 0usize;
    let mut open_heads = 0usize;
    let cy_min = layout.iter().map(|k| k.2).fold(f32::INFINITY, f32::min);
    let cy_max = layout.iter().map(|k| k.2).fold(f32::NEG_INFINITY, f32::max);
    for pair in records.windows(2) {
        let (a, b) = (&pair[0], &pair[1]);
        if a.width != b.width || a.height != b.height {
            continue;
        }
        let kw = a.width * 0.1;
        let pitch = (cy_max - cy_min) * a.height / 2.0;
        let bar_top = cy_max * a.height + pitch / 2.0;
        let a_last = *a.points.last().unwrap();
        let b_first = b.points[0];
        if a_last.y >= bar_top - kw * 0.1 || b_first.y >= bar_top - kw * 0.1 {
            continue;
        }
        let keys = keymap(layout, a.width, a.height);
        let mut seg1 = a.points.clone();
        seg1.extend(walk(a_last, Point { x: a_last.x, y: bar_top, t: a_last.t }, kw));
        let mut seg2 = walk(Point { x: b_first.x, y: bar_top, t: b_first.t - 400 }, b_first, kw);
        seg2.pop();
        seg2.extend(b.points.iter().cloned());
        let top = |decoder: &mut Decoder, seg: &[Point]| -> Option<String> {
            decoder.decode(seg, &keys, kw, &sources, 4, None, 0).first().map(|c| String::from_utf16_lossy(&c.word).to_lowercase())
        };
        decoder.open_head = 0;
        decoder.open_tail = 0;
        let p1 = top(&mut decoder, &seg1) == Some(a.word.clone());
        let p2 = top(&mut decoder, &seg2) == Some(b.word.clone());
        let tail = open_run(&seg1, kw, true);
        let head = open_run(&seg2, kw, false);
        open_tails += (tail > 0) as usize;
        open_heads += (head > 0) as usize;
        decoder.open_tail = tail;
        let o1 = top(&mut decoder, &seg1) == Some(a.word.clone());
        decoder.open_tail = 0;
        decoder.open_head = head;
        let o2 = top(&mut decoder, &seg2) == Some(b.word.clone());
        decoder.open_head = 0;
        n += 1;
        first_plain += p1 as usize;
        first_open += o1 as usize;
        second_plain += p2 as usize;
        second_open += o2 as usize;
        both_plain += (p1 && p2) as usize;
        both_open += (o1 && o2) as usize;
    }
    let f = |a: usize| a as f64 / n.max(1) as f64;
    println!(
        "chained pairs {n}: first word plain {:.4} open {:.4} | second word plain {:.4} open {:.4} | both plain {:.4} open {:.4} | open tail found {:.3} head {:.3}",
        f(first_plain),
        f(first_open),
        f(second_plain),
        f(second_open),
        f(both_plain),
        f(both_open),
        f(open_tails),
        f(open_heads)
    );
}
