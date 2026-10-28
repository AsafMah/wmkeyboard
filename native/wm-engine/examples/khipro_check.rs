//! Checks a Khipro spec against a conformance table the way the app checks
//! a downloaded one before installing it (`khipro::check`), for publishing a
//! spec set to the data repository.
//!
//! cargo run --example khipro_check -- <spec.mim> <cases.tsv> [skip.tsv]
//!
//! `skip.tsv` has the table's shape and lists the cases upstream has left
//! stale. Prints how many cases ran, or the refusal, and exits 1 on one.

use std::{env, fs, process};

fn main() {
    let args: Vec<String> = env::args().skip(1).collect();
    if args.len() < 2 {
        eprintln!("usage: khipro_check <spec.mim> <cases.tsv> [skip.tsv]");
        process::exit(2);
    }
    let read = |path: &String| fs::read_to_string(path).unwrap_or_else(|e| panic!("{path}: {e}"));
    let skip = args.get(2).map(read).unwrap_or_default();
    match wm_engine::khipro::check(&read(&args[0]), &read(&args[1]), &skip) {
        Ok(cases) => println!("{}: {cases} cases pass", args[0]),
        Err(why) => {
            eprintln!("{}: {why}", args[0]);
            process::exit(1);
        }
    }
}
