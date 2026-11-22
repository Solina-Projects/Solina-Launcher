//! Manual bench for search-rs (the tool's `bench` cannot compile the
//! original: its kotlinc step ignores the stub jar and fails on
//! `filterHiddenApps`). Same methodology as the RustAnythingDriver bench
//! mode: JSON args converted once, JIT/warm-up for min_ms/2, then passes
//! until min_ms elapsed; reports ns per call, bridge (`call_json`)
//! included. Also times the batch: one `FuzzyScoreIndex` over all
//! distinct choices, `run` per distinct query.

use std::collections::HashMap;
use std::time::Instant;

use search_rs::port::FuzzyScoreIndex;
use serde_json::Value;

const MIN_MS: u128 = 200;

fn main() {
    let path = format!("{}/cases.jsonl", env!("CARGO_MANIFEST_DIR"));
    let mut groups: HashMap<String, Vec<String>> = HashMap::new();
    let mut order: Vec<String> = Vec::new();
    for line in std::fs::read_to_string(&path).unwrap().lines() {
        let v: Value = serde_json::from_str(line).unwrap();
        let f = v["fn"].as_str().unwrap().to_string();
        let entry = groups.entry(f.clone()).or_insert_with(|| {
            order.push(f.clone());
            Vec::new()
        });
        entry.push(v["args"].to_string());
    }
    for f in &order {
        let args_list = &groups[f];
        let pass = || {
            for args in args_list {
                let _ = search_rs::call_json(f, args);
            }
        };
        // Warm up.
        let warm = Instant::now();
        while warm.elapsed().as_millis() < MIN_MS / 2 {
            pass();
        }
        let mut passes: u128 = 0;
        let start = Instant::now();
        loop {
            pass();
            passes += 1;
            if start.elapsed().as_millis() >= MIN_MS {
                let ns =
                    start.elapsed().as_nanos() as f64 / (passes as f64 * args_list.len() as f64);
                println!("rust {f}: cases={} ns={ns:.1}", args_list.len());
                break;
            }
        }
    }

    // Batch: all distinct fuzzyScore choices, one index, run per query.
    let mut choices: Vec<String> = Vec::new();
    let mut queries: Vec<String> = Vec::new();
    for line in std::fs::read_to_string(&path).unwrap().lines() {
        let v: Value = serde_json::from_str(line).unwrap();
        if v["fn"].as_str() != Some("fuzzyScore") {
            continue;
        }
        let args = v["args"].as_array().unwrap();
        let choice = args[1].as_str().unwrap().to_string();
        if !choices.contains(&choice) {
            choices.push(choice);
        }
        let query = args[0].as_str().unwrap().to_string();
        if !queries.contains(&query) {
            queries.push(query);
        }
    }
    let batch = FuzzyScoreIndex::new(choices.clone());
    let n_choices = choices.len();
    let pass = || {
        for q in &queries {
            let _ = batch.run(q.clone()).unwrap();
        }
    };
    let warm = Instant::now();
    while warm.elapsed().as_millis() < MIN_MS / 2 {
        pass();
    }
    let mut passes: u128 = 0;
    let start = Instant::now();
    loop {
        pass();
        passes += 1;
        if start.elapsed().as_millis() >= MIN_MS {
            let per_run = start.elapsed().as_nanos() as f64 / passes as f64;
            let per_item = per_run / (queries.len() as f64 * n_choices as f64);
            println!(
                "rust batch: choices={n_choices} queries={} ns/run={per_run:.1} ns/item={per_item:.1}",
                queries.len()
            );
            break;
        }
    }
}
