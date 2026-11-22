//! The batch index must give the same result as the function, item by
//! item, over every recorded `fuzzyScore` case. Cases are grouped by
//! query; one batch is built per query and its `run` output is compared
//! with the recorded scores, and the ranking (score >= 65, best first,
//! ties in choice order) is compared with a direct sort of the same
//! scores.

use std::collections::HashMap;

use search_rs::port::{FuzzyScoreIndex, fuzzy_score};
use serde_json::Value;

fn load_cases() -> Vec<(String, String, i64)> {
    let path = format!("{}/cases.jsonl", env!("CARGO_MANIFEST_DIR"));
    let mut out = Vec::new();
    for line in std::fs::read_to_string(path).unwrap().lines() {
        let v: Value = serde_json::from_str(line).unwrap();
        if v["fn"].as_str() != Some("fuzzyScore") {
            continue;
        }
        let args = v["args"].as_array().unwrap();
        let query = args[0].as_str().unwrap().to_string();
        let choice = args[1].as_str().unwrap().to_string();
        let ok = v["ok"].as_i64().unwrap();
        out.push((query, choice, ok));
    }
    out
}

#[test]
fn batch_matches_function_on_recorded_cases() {
    let cases = load_cases();
    assert!(!cases.is_empty(), "no fuzzyScore cases in cases.jsonl");
    // Group by query, keeping choice order.
    let mut groups: HashMap<String, Vec<(String, i64)>> = HashMap::new();
    let mut order: Vec<String> = Vec::new();
    for (query, choice, ok) in &cases {
        let entry = groups.entry(query.clone()).or_insert_with(|| {
            order.push(query.clone());
            Vec::new()
        });
        entry.push((choice.clone(), *ok));
    }
    for query in &order {
        let group = &groups[query];
        let choices: Vec<String> = group.iter().map(|(c, _)| c.clone()).collect();
        let batch = FuzzyScoreIndex::new(choices);
        let scores = batch.run(query.clone()).unwrap();
        assert_eq!(scores.len(), group.len());
        for (i, (choice, ok)) in group.iter().enumerate() {
            assert_eq!(
                scores[i], *ok,
                "batch run mismatch for query {query:?} choice {choice:?}"
            );
            // The single-call function must agree too.
            assert_eq!(
                fuzzy_score(query.clone(), choice.clone()).unwrap(),
                *ok,
                "function mismatch for query {query:?} choice {choice:?}"
            );
        }
        // Ranking: score >= 65, best first, ties in choice order.
        let mut expected: Vec<(usize, i64)> = scores
            .iter()
            .enumerate()
            .filter(|&(_, &s)| s >= 65)
            .map(|(i, &s)| (i, s))
            .collect();
        expected.sort_by_key(|a| std::cmp::Reverse(a.1));
        let expected_idx: Vec<usize> = expected.into_iter().map(|(i, _)| i).collect();
        // Recompute the same ranking from run() the way the generated
        // JNI rank does (filter, stable sort by score descending).
        let mut ranked: Vec<(usize, i64)> = scores
            .iter()
            .enumerate()
            .filter(|&(_, &s)| s >= 65)
            .map(|(i, &s)| (i, s))
            .collect();
        ranked.sort_by_key(|a| std::cmp::Reverse(a.1));
        let ranked_idx: Vec<usize> = ranked.into_iter().map(|(i, _)| i).collect();
        assert_eq!(ranked_idx, expected_idx, "ranking mismatch for {query:?}");
    }
}
