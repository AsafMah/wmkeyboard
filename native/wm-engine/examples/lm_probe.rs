//! A look at a `.wmlm`: the likeliest next words after a context, the
//! log-probabilities of a few candidates, a greedy continuation, and the
//! time one context takes. Checks the reader against `tools/wmlm/check.py`.
//!
//! ```text
//! cargo run --release --example lm_probe -- model.wmlm "i want to" buy by bye
//! ```

use std::sync::Arc;
use std::time::Instant;

use wm_engine::lm::{Lm, Prefix, Session};

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let lm = Arc::new(Lm::open(std::fs::read(&args[1]).expect("model")).expect("a .wmlm"));
    println!("{} d{} layers {} heads {} ff {} ctx {} vocab {} langs {:?}", lm.name, lm.d, lm.layers, lm.heads, lm.ff, lm.ctx, lm.vocab_size(), lm.langs);
    let context: Vec<&str> = args.get(2).map(|s| s.split_whitespace().collect()).unwrap_or_default();
    let mut session = Session::new(lm.clone());
    // `LANGSLOT=1` reads the context as the model's second language.
    let prefix = Prefix { lang: std::env::var("LANGSLOT").ok().and_then(|v| v.parse().ok()).unwrap_or(0), ..Prefix::default() };
    let started = Instant::now();
    session.set_context(prefix, &context);
    let top = session.top(10, f32::NEG_INFINITY);
    let cold = started.elapsed();
    println!("context {:?} -> {} tokens in {:?}", context, session.tokens().len(), cold);
    for (w, lp) in &top {
        println!("  {w:>14} {lp:.3} ({:.4})", lp.exp());
    }
    for cand in args.iter().skip(3) {
        println!("  P({cand}) = {:?}", session.logprob(cand).map(|lp| (lp, lp.exp())));
    }
    // One more word: the incremental cost.
    let mut longer: Vec<&str> = context.clone();
    longer.push(top.first().map(|(w, _)| w.as_str()).unwrap_or("the"));
    let started = Instant::now();
    session.set_context(prefix, &longer);
    let _ = session.distribution();
    println!("one more word: {:?}", started.elapsed());
    session.set_context(prefix, &context);
    let (words, lp) = session.generate(4, 0.15);
    println!("greedy: {:?} (ln p {lp:.2})", words);
}
