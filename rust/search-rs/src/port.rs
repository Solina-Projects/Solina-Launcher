//! The Rust port of `search/`.
//!
//! `normalize_search_text` ports `SearchTextNormalizer.normalize`
//! (NFKD, Latin-only combining-mark folding, Arabic mappings, ROOT
//! lowercase, whitespace collapse, trim). `fuzzy_score` ports the
//! per-app WeightedRatio step of `SearchUtils.fuzzySearch` (fuzzywuzzy
//! 1.4.0: `WeightedRatio` over `DefaultStringFunction`-processed
//! strings). `FuzzyScoreIndex` pre-processes the choice list once so the
//! per-keystroke loop only scores.
//!
//! Faithfulness notes (all verified against the fuzzywuzzy 1.4.0 sources
//! in `vendor/src/` and against probes of the JDK this VM runs):
//! - The fuzzy core works on UTF-16 code units, exactly like Java
//!   `String.length()` / `toCharArray()`; astral characters are two units
//!   there, one `char` here, so every ratio function takes `&[u16]`.
//! - `DiffUtils.getRatio` prices a substitution at 2 (insert + delete:
//!   its DP has no diagonal substitution term), while `getEditOps`
//!   (used for matching blocks) prices it at 1. The port keeps both.
//! - `DefaultStringFunction`'s `\W` (UNICODE_CHARACTER_CLASS) is a
//!   per-code-point test: word = Alphabetic | Mark | Nd | Pc |
//!   Join_Control (U+200C/U+200D). The mark / Nd / Pc / Latin-script
//!   tables in `unicode_tables` were dumped from this JDK so they match
//!   exactly; `char::is_alphabetic` supplies Alphabetic.
//! - Token sorting uses UTF-16 unit order (`String.compareTo`), not
//!   Rust `str` order: they differ for astral vs high-BMP characters.
//! - `Math.round` is round-half-up (`floor(x + 0.5)`), NaN rounds to 0.
//! - `PartialRatio`'s max over block ratios uses `Double.compareTo`
//!   order, where NaN beats every number (a `("", "")` block ratio is
//!   NaN); plain `f64::max` would drop it, so `total_cmp` is used.

#![forbid(unsafe_code)]

use std::cmp::Ordering;
use std::collections::HashSet;

use unicode_normalization::UnicodeNormalization;

#[path = "unicode_tables.rs"]
mod unicode_tables;

use unicode_tables::in_ranges;

/// A Java `\s` character: `[ \t\n\x0B\f\r ]` (no UNICODE flag, ASCII only).
fn is_java_space(c: char) -> bool {
    matches!(c, ' ' | '\t' | '\n' | '\x0B' | '\x0C' | '\r')
}

/// Java `String.trim()`: strip leading/trailing chars `<= ' '` (U+0020).
fn java_trim(s: &str) -> &str {
    s.trim_matches(|c: char| (c as u32) <= 0x20)
}

/// Java `Regex("\\s+").replace(s, " ")`: each maximal run of Java
/// whitespace becomes one space.
fn collapse_java_spaces(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut in_run = false;
    for c in s.chars() {
        if is_java_space(c) {
            if !in_run {
                out.push(' ');
                in_run = true;
            }
        } else {
            out.push(c);
            in_run = false;
        }
    }
    out
}

/// Java `Math.round(double)`: round-half-up; NaN rounds to 0. All call
/// sites here are non-negative, so `floor(x + 0.5)` is exact.
fn java_round(x: f64) -> i32 {
    if x.is_nan() {
        0
    } else {
        (x + 0.5).floor() as i32
    }
}

/// Order two strings the way Java `String.compareTo` does: lexicographic
/// over UTF-16 code units. (Rust `str` order is by code point and differs
/// for astral characters vs high-BMP characters.)
fn cmp_utf16(a: &str, b: &str) -> Ordering {
    a.encode_utf16().cmp(b.encode_utf16())
}

fn utf16(s: &str) -> Vec<u16> {
    s.encode_utf16().collect()
}

/// Port of `normalizeSearchText` (`search/SearchUtils.kt:78`, i.e.
/// `SearchTextNormalizer.normalize`).
pub fn normalize_search_text(value: String) -> Result<String, String> {
    // NFKD first; the loop then works on the decomposed code points.
    let input: String = value.nfkd().collect();
    let mut canonical = String::with_capacity(input.len());
    // `previousBaseScript` starts at COMMON; only "is it LATIN" matters.
    let mut prev_is_latin = false;
    for ch in input.chars() {
        let cp = ch as u32;
        let is_mark = in_ranges(unicode_tables::MARK_RANGES, cp);
        if !is_mark || !prev_is_latin {
            match cp {
                0x064A | 0x0649 => canonical.push('\u{06CC}'),
                0x0643 => canonical.push('\u{06A9}'),
                0x0640 | 0x200C | 0x200D | 0xFEFF | 0x200E | 0x200F => {}
                0x0660..=0x0669 => canonical.push((b'0' + (cp - 0x0660) as u8) as char),
                0x06F0..=0x06F9 => canonical.push((b'0' + (cp - 0x06F0) as u8) as char),
                _ => canonical.push(ch),
            }
        }
        if !is_mark {
            // Dropped characters still update the script (e.g. a ZWNJ
            // resets it to COMMON), exactly as the Kotlin source does.
            prev_is_latin = in_ranges(unicode_tables::LATIN_SCRIPT_RANGES, cp);
        }
    }
    let lowered = canonical.to_lowercase();
    let collapsed = collapse_java_spaces(&lowered);
    Ok(java_trim(&collapsed).to_string())
}

/// `me.xdrop.fuzzywuzzy.algorithms.DefaultStringFunction`'s `\W` with
/// `UNICODE_CHARACTER_CLASS`: word = Alphabetic | Mark (Mn/Mc/Me) | Nd |
/// Pc | Join_Control. Probed on this JDK: an astral character is one
/// `\W` match (one space), Nl/Lm are word characters, No is not.
fn is_word_char(c: char) -> bool {
    let cp = c as u32;
    c.is_alphabetic()
        || in_ranges(unicode_tables::MARK_RANGES, cp)
        || in_ranges(unicode_tables::DECIMAL_DIGIT_RANGES, cp)
        || in_ranges(unicode_tables::CONNECTOR_PUNCTUATION_RANGES, cp)
        || cp == 0x200C
        || cp == 0x200D
}

/// Port of `DefaultStringFunction.apply`: each non-word character becomes
/// one space, then lowercase (default locale here is POSIX/C, so it is the
/// same as ROOT), then Java trim. Idempotent on its own output.
fn default_string_function(input: &str) -> String {
    let mut out = String::with_capacity(input.len());
    for c in input.chars() {
        if is_word_char(c) {
            out.push(c);
        } else {
            out.push(' ');
        }
    }
    let lowered = out.to_lowercase();
    java_trim(&lowered).to_string()
}

/// `DiffUtils.levEditDistance(s1, s2, 1)`: insert/delete only, so a
/// substitution costs 2 (delete + insert). Standard two-row DP with that
/// cost is the same distance; verified against the Java probe
/// (`lev("a","b") = 2`, `lev("kitten","sitting") = 5`).
fn lev_distance_no_sub(s1: &[u16], s2: &[u16]) -> usize {
    if s1.is_empty() {
        return s2.len();
    }
    if s2.is_empty() {
        return s1.len();
    }
    let mut prev: Vec<usize> = (0..=s2.len()).collect();
    let mut cur: Vec<usize> = vec![0; s2.len() + 1];
    for (i, &a) in s1.iter().enumerate() {
        cur[0] = i + 1;
        for (j, &b) in s2.iter().enumerate() {
            let cost = if a == b { 0 } else { 2 };
            cur[j + 1] = (prev[j] + cost).min(prev[j + 1] + 1).min(cur[j] + 1);
        }
        std::mem::swap(&mut prev, &mut cur);
    }
    prev[s2.len()]
}

/// `DiffUtils.getRatio`: `(len1 + len2 - editDistance) / (len1 + len2)`.
/// Both empty gives `0.0 / 0.0` = NaN, exactly as in Java.
fn get_ratio(s1: &[u16], s2: &[u16]) -> f64 {
    let lensum = s1.len() + s2.len();
    (lensum - lev_distance_no_sub(s1, s2)) as f64 / lensum as f64
}

/// `SimpleRatio.apply`: `round(100 * getRatio)`.
fn simple_ratio(s1: &[u16], s2: &[u16]) -> i32 {
    java_round(100.0 * get_ratio(s1, s2))
}

/// One edit operation from `DiffUtils.getEditOps`. `KEEP` never occurs:
/// `editOpsFromCostMatrix` only emits INSERT / DELETE / REPLACE.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
enum EditType {
    Insert,
    Delete,
    Replace,
}

#[derive(Clone, Copy, Debug)]
struct EditOp {
    op_type: EditType,
    spos: usize,
    dpos: usize,
}

/// Port of `DiffUtils.getEditOps`: strips the common prefix and suffix,
/// fills the standard Levenshtein matrix (substitution cost 1) over the
/// middle part, then backtracks. The backtrack's branch order and `dir`
/// state decide which optimal alignment is produced, so they are kept
/// exactly; `getMatchingBlocks` depends on the result.
fn get_edit_ops(c1: &[u16], c2: &[u16]) -> Vec<EditOp> {
    let mut p1 = 0usize;
    let mut p2 = 0usize;
    let mut len1 = c1.len();
    let mut len2 = c2.len();
    let mut len1o = 0usize;
    while len1 > 0 && len2 > 0 && c1[p1] == c2[p2] {
        len1 -= 1;
        len2 -= 1;
        p1 += 1;
        p2 += 1;
        len1o += 1;
    }
    let len2o = len1o;
    while len1 > 0 && len2 > 0 && c1[p1 + len1 - 1] == c2[p2 + len2 - 1] {
        len1 -= 1;
        len2 -= 1;
    }
    len1 += 1;
    len2 += 1;

    // Matrix: len1 rows x len2 cols, row-major, matrix[i * len2 + j].
    let mut matrix = vec![0i32; len2 * len1];
    for (j, slot) in matrix.iter_mut().take(len2).enumerate() {
        *slot = j as i32;
    }
    for i in 1..len1 {
        matrix[len2 * i] = i as i32;
    }
    for i in 1..len1 {
        let mut ptr_prev = (i - 1) * len2;
        let mut ptr_c = i * len2;
        let ptr_end = ptr_c + len2 - 1;
        let char1 = c1[p1 + i - 1];
        let mut ptr_char2 = p2;
        let mut x = i as i32;
        ptr_c += 1;
        while ptr_c <= ptr_end {
            let mut c3 = matrix[ptr_prev] + i32::from(char1 != c2[ptr_char2]);
            ptr_prev += 1;
            ptr_char2 += 1;
            x += 1;
            if x > c3 {
                x = c3;
            }
            c3 = matrix[ptr_prev] + 1;
            if x > c3 {
                x = c3;
            }
            matrix[ptr_c] = x;
            ptr_c += 1;
        }
    }

    // Backtrack from the bottom-right cell.
    let mut rev: Vec<EditOp> = Vec::with_capacity(matrix[len1 * len2 - 1] as usize);
    let mut dir: i32 = 0;
    let mut i = len1 - 1;
    let mut j = len2 - 1;
    let mut ptr = len1 * len2 - 1;
    while i > 0 || j > 0 {
        if i != 0
            && j != 0
            && matrix[ptr] == matrix[ptr - len2 - 1]
            && c1[p1 + i - 1] == c2[p2 + j - 1]
        {
            i -= 1;
            j -= 1;
            ptr -= len2 + 1;
            dir = 0;
            continue;
        }
        if dir < 0 && j != 0 && matrix[ptr] == matrix[ptr - 1] + 1 {
            rev.push(EditOp {
                op_type: EditType::Insert,
                spos: i + len1o,
                dpos: j - 1 + len2o,
            });
            j -= 1;
            ptr -= 1;
            continue;
        }
        if dir > 0 && i != 0 && matrix[ptr] == matrix[ptr - len2] + 1 {
            rev.push(EditOp {
                op_type: EditType::Delete,
                spos: i - 1 + len1o,
                dpos: j + len2o,
            });
            i -= 1;
            ptr -= len2;
            continue;
        }
        if i != 0 && j != 0 && matrix[ptr] == matrix[ptr - len2 - 1] + 1 {
            rev.push(EditOp {
                op_type: EditType::Replace,
                spos: i - 1 + len1o,
                dpos: j - 1 + len2o,
            });
            i -= 1;
            j -= 1;
            ptr -= len2 + 1;
            dir = 0;
            continue;
        }
        if dir == 0 && j != 0 && matrix[ptr] == matrix[ptr - 1] + 1 {
            rev.push(EditOp {
                op_type: EditType::Insert,
                spos: i + len1o,
                dpos: j - 1 + len2o,
            });
            j -= 1;
            ptr -= 1;
            dir = -1;
            continue;
        }
        if dir == 0 && i != 0 && matrix[ptr] == matrix[ptr - len2] + 1 {
            rev.push(EditOp {
                op_type: EditType::Delete,
                spos: i - 1 + len1o,
                dpos: j + len2o,
            });
            i -= 1;
            ptr -= len2;
            dir = 1;
            continue;
        }
        unreachable!("inconsistent Levenshtein cost matrix");
    }
    rev.reverse();
    rev
}

#[derive(Clone, Copy, Debug)]
struct MatchingBlock {
    spos: usize,
    dpos: usize,
}

/// Port of `DiffUtils.getMatchingBlocks(int, int, EditOp[])`: the
/// matching blocks are the gaps between edit operations, plus a final
/// zero-length block at `(len1, len2)`.
fn get_matching_blocks(len1: usize, len2: usize, ops: &[EditOp]) -> Vec<MatchingBlock> {
    let n = ops.len();
    let mut blocks: Vec<MatchingBlock> = Vec::new();
    let mut o = 0usize;
    let mut spos = 0usize;
    let mut dpos = 0usize;
    let mut i = n;
    while i != 0 {
        if spos < ops[o].spos || dpos < ops[o].dpos {
            blocks.push(MatchingBlock { spos, dpos });
            spos = ops[o].spos;
            dpos = ops[o].dpos;
        }
        let ty = ops[o].op_type;
        loop {
            match ty {
                EditType::Replace => {
                    spos += 1;
                    dpos += 1;
                }
                EditType::Delete => {
                    spos += 1;
                }
                EditType::Insert => {
                    dpos += 1;
                }
            }
            i -= 1;
            o += 1;
            if i == 0 || ops[o].op_type != ty || spos != ops[o].spos || dpos != ops[o].dpos {
                break;
            }
        }
    }
    if spos < len1 || dpos < len2 {
        debug_assert_eq!(len1 - spos, len2 - dpos);
        blocks.push(MatchingBlock { spos, dpos });
    }
    blocks.push(MatchingBlock {
        spos: len1,
        dpos: len2,
    });
    blocks
}

/// Max over doubles in Java `Collections.max` / `Double.compareTo`
/// order: NaN beats every number (including another NaN it ties with).
fn max_doubles(scores: &[f64]) -> f64 {
    let mut best = scores[0];
    for &t in &scores[1..] {
        if t.total_cmp(&best) == Ordering::Greater {
            best = t;
        }
    }
    best
}

/// Port of `PartialRatio.apply`: best substring ratio over the matching
/// blocks of the shorter string inside the longer one; returns 100 early
/// when a block ratio exceeds 0.995.
fn partial_ratio(s1: &[u16], s2: &[u16]) -> i32 {
    let (shorter, longer) = if s1.len() <= s2.len() {
        (s1, s2)
    } else {
        (s2, s1)
    };
    let blocks = get_matching_blocks(shorter.len(), longer.len(), &get_edit_ops(shorter, longer));
    let mut scores: Vec<f64> = Vec::with_capacity(blocks.len());
    for mb in &blocks {
        let dist = mb.dpos as i32 - mb.spos as i32;
        let long_start = if dist > 0 { dist as usize } else { 0 };
        let long_end = (long_start + shorter.len()).min(longer.len());
        let ratio = get_ratio(shorter, &longer[long_start..long_end]);
        if ratio > 0.995 {
            return 100;
        }
        scores.push(ratio);
    }
    java_round(100.0 * max_doubles(&scores))
}

/// Java `String.split("\\s+")` on a trimmed string: split on maximal
/// runs of Java whitespace, no empty tokens. (The raw Java split would
/// keep a leading empty token, but every caller here passes a
/// `DefaultStringFunction` result, which is trimmed.)
fn tokenize(s: &str) -> Vec<&str> {
    let mut out: Vec<&str> = Vec::new();
    let mut start: Option<usize> = None;
    for (i, c) in s.char_indices() {
        if is_java_space(c) {
            if let Some(st) = start.take() {
                out.push(&s[st..i]);
            }
        } else if start.is_none() {
            start = Some(i);
        }
    }
    if let Some(st) = start {
        out.push(&s[st..]);
    }
    out
}

/// `Utils.sortAndJoin`: sort tokens by Java `String` order (UTF-16
/// units) and join with single spaces. `Utils.join` prepends the
/// separator before every element and trims, which for space-free tokens
/// is exactly `join(" ")` (and `""` for an empty list).
fn sort_and_join_tokens(tokens: &[String]) -> String {
    let mut sorted: Vec<&String> = tokens.iter().collect();
    sorted.sort_by(|a, b| cmp_utf16(a, b));
    sorted
        .iter()
        .map(|s| s.as_str())
        .collect::<Vec<_>>()
        .join(" ")
}

/// One side of a WeightedRatio comparison, pre-computed once: the
/// `DefaultStringFunction` output and everything the token ratios need
/// from it, so the per-query loop never re-does this work.
struct Prepared {
    units: Vec<u16>,
    sorted_joined_units: Vec<u16>,
    token_set: HashSet<String>,
}

fn prepare(s: &str) -> Prepared {
    let processed = default_string_function(s);
    let units = utf16(&processed);
    let tokens = tokenize(&processed);
    let sorted_tokens: Vec<String> = {
        let mut t: Vec<String> = tokens.iter().map(|t| t.to_string()).collect();
        t.sort_by(|a, b| cmp_utf16(a, b));
        t
    };
    let sorted_joined = sorted_tokens.join(" ");
    let sorted_joined_units = utf16(&sorted_joined);
    let token_set: HashSet<String> = tokens.iter().map(|t| t.to_string()).collect();
    Prepared {
        units,
        sorted_joined_units,
        token_set,
    }
}

/// `TokenSort` with a given ratio: sort each side's tokens, join, ratio.
fn token_sort_apply(q: &Prepared, c: &Prepared, ratio: fn(&[u16], &[u16]) -> i32) -> i32 {
    ratio(&q.sorted_joined_units, &c.sorted_joined_units)
}

/// `TokenSet` with a given ratio: intersection and differences of the
/// token sets, three ratios, the max.
fn token_set_apply(q: &Prepared, c: &Prepared, ratio: fn(&[u16], &[u16]) -> i32) -> i32 {
    let intersection: Vec<String> = q.token_set.intersection(&c.token_set).cloned().collect();
    let diff1to2: Vec<String> = q.token_set.difference(&c.token_set).cloned().collect();
    let diff2to1: Vec<String> = c.token_set.difference(&q.token_set).cloned().collect();
    let sorted_inter = sort_and_join_tokens(&intersection);
    let sorted_1to2 = java_trim(&format!(
        "{sorted_inter} {}",
        sort_and_join_tokens(&diff1to2)
    ))
    .to_string();
    let sorted_2to1 = java_trim(&format!(
        "{sorted_inter} {}",
        sort_and_join_tokens(&diff2to1)
    ))
    .to_string();
    let r1 = ratio(&utf16(&sorted_inter), &utf16(&sorted_1to2));
    let r2 = ratio(&utf16(&sorted_inter), &utf16(&sorted_2to1));
    let r3 = ratio(&utf16(&sorted_1to2), &utf16(&sorted_2to1));
    r1.max(r2).max(r3)
}

fn max_plain(values: &[f64]) -> f64 {
    let mut best = values[0];
    for &t in &values[1..] {
        if t > best {
            best = t;
        }
    }
    best
}

/// Port of `WeightedRatio.apply` over already-prepared sides. Lengths
/// and ratios are on the `DefaultStringFunction` outputs (UTF-16 units).
fn weighted_ratio_prepared(q: &Prepared, c: &Prepared) -> i32 {
    let len1 = q.units.len();
    let len2 = c.units.len();
    if len1 == 0 || len2 == 0 {
        return 0;
    }
    let base = simple_ratio(&q.units, &c.units);
    let len_ratio = len1.max(len2) as f64 / len1.min(len2) as f64;
    let try_partials = len_ratio >= 1.5;
    let partial_scale = if len_ratio > 8.0 { 0.6 } else { 0.90 };
    if try_partials {
        let partial = partial_ratio(&q.units, &c.units) as f64 * partial_scale;
        let partial_sor = token_sort_apply(q, c, partial_ratio) as f64 * 0.95 * partial_scale;
        let partial_set = token_set_apply(q, c, partial_ratio) as f64 * 0.95 * partial_scale;
        java_round(max_plain(&[base as f64, partial, partial_sor, partial_set]))
    } else {
        let token_sort = token_sort_apply(q, c, simple_ratio) as f64 * 0.95;
        let token_set = token_set_apply(q, c, simple_ratio) as f64 * 0.95;
        java_round(max_plain(&[base as f64, token_sort, token_set]))
    }
}

/// Port of `fuzzyScore` (`search/FuzzyScore.kt:11`): the per-app
/// WeightedRatio step of `SearchUtils.fuzzySearch`, over
/// already-normalized strings.
pub fn fuzzy_score(query: String, choice: String) -> Result<i64, String> {
    // NOTE: returns i64 (not i32) because `rust-anything refresh --batch`
    // rejects `i32` as a batch result while accepting integer JSON
    // numbers; the value is always an exact integer 0..=100.
    let q = prepare(&query);
    let c = prepare(&choice);
    Ok(weighted_ratio_prepared(&q, &c) as i64)
}

/// The per-app scoring loop, batched: the choice list (the
/// already-normalized `sectionName + title` strings) is prepared once in
/// `new`, and `run` scores every choice against one query, on all cores.
/// Built once per app list, not per keystroke. (The name matters:
/// `rust-anything refresh --batch` uses `pub struct FuzzyScoreIndex`
/// with `new`/`run` instead of generating a default index that would
/// re-prepare every choice on every query.)
pub struct FuzzyScoreIndex {
    choices: Vec<Prepared>,
}

impl FuzzyScoreIndex {
    pub fn new(choices: Vec<String>) -> Self {
        Self {
            choices: choices.iter().map(|c| prepare(c)).collect(),
        }
    }

    /// Score every choice against `query`, in choice order.
    pub fn run(&self, query: String) -> Result<Vec<i64>, String> {
        use rayon::prelude::*;
        let q = prepare(&query);
        Ok(self
            .choices
            .par_iter()
            .map(|c| weighted_ratio_prepared(&q, c) as i64)
            .collect())
    }
}
