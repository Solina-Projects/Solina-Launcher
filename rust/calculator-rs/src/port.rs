//! The Rust port of `calc/` — `FrontDoor.calculateEquation`.
//!
//! Mirrors `java.math.BigDecimal` semantics exactly: DECIMAL128 (34 digits,
//! HALF_EVEN) while evaluating, DECIMAL64 (16 digits) at the front door.
//! `Dec` is `(unscaled, scale)`: value = unscaled * 10^(-scale). `==` is
//! Java `equals` (scale-sensitive); comparisons are `compareTo` (numeric).
//! All errors collapse to `Ok("")` at the public entry point, matching the
//! Kotlin front door, which catches every exception and returns "".

#![forbid(unsafe_code)]

use num_bigint::BigInt;
use num_integer::Integer;
use num_traits::{Euclid, One, Signed, ToPrimitive, Zero};
use std::cmp::Ordering;
use std::collections::HashMap;

const PREC128: i32 = 34;
const PREC64: i32 = 16;
/// Guard against absurd digit counts from hostile inputs (nothing recorded
/// comes close; Java would allocate the same magnitudes).
const MAX_DIGITS: i64 = 1_000_000;

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
enum RoundMode {
    HalfEven,
    HalfUp,
    Floor,
    Ceiling,
}

#[derive(Clone, Debug, PartialEq, Eq)]
struct Dec {
    u: BigInt,
    scale: i32,
}

impl Dec {
    fn zero() -> Self {
        Dec {
            u: BigInt::zero(),
            scale: 0,
        }
    }
    fn one() -> Self {
        Dec {
            u: BigInt::one(),
            scale: 0,
        }
    }
    fn from_i32(v: i32) -> Self {
        Dec {
            u: BigInt::from(v),
            scale: 0,
        }
    }
    /// 0.5: unscaled 5, scale 1 (what `BigDecimal(0.5)` is).
    fn half() -> Self {
        Dec {
            u: BigInt::from(5),
            scale: 1,
        }
    }
    fn abs(&self) -> Self {
        Dec {
            u: self.u.abs(),
            scale: self.scale,
        }
    }
    fn negate(&self) -> Self {
        Dec {
            u: -self.u.clone(),
            scale: self.scale,
        }
    }
    /// Numeric zero (any scale).
    /// `if`, `&&` and `||` test, so `0.0` is truthy.
    fn equals_zero(&self) -> bool {
        self.u.is_zero() && self.scale == 0
    }
    fn is_truthy(&self) -> bool {
        !self.equals_zero()
    }
    fn signum(&self) -> i32 {
        if self.u.is_zero() {
            0
        } else if self.u.is_negative() {
            -1
        } else {
            1
        }
    }
}

fn pow10(n: i64) -> Result<BigInt, ()> {
    if !(0..=MAX_DIGITS).contains(&n) {
        return Err(());
    }
    Ok(BigInt::from(10).pow(n as u32))
}

/// Round the significant-digit string to `prec` digits per `mode`.
/// Returns the kept digits (prec+1 long on a carry, e.g. 999 -> 1000).
fn round_sig_digits(digits: &[u8], prec: usize, mode: RoundMode) -> Vec<u8> {
    if digits.len() <= prec {
        return digits.to_vec();
    }
    let (kept, dropped) = digits.split_at(prec);
    let up = match dropped[0] {
        0..=4 => false,
        6..=9 => true,
        _ => {
            // dropped[0] == 5
            if dropped[1..].iter().any(|&d| d != 0) {
                true
            } else {
                match mode {
                    RoundMode::HalfUp => true,
                    RoundMode::HalfEven => kept.last().is_some_and(|d| d % 2 == 1),
                    _ => false,
                }
            }
        }
    };
    if !up {
        return kept.to_vec();
    }
    let mut out = kept.to_vec();
    let mut i = out.len();
    while i > 0 {
        i -= 1;
        if out[i] == 9 {
            out[i] = 0;
        } else {
            out[i] += 1;
            return out;
        }
    }
    let mut r = vec![1u8];
    r.extend(std::iter::repeat_n(0, prec));
    r
}

fn digits_to_bigint(digits: &[u8]) -> BigInt {
    let mut v = BigInt::zero();
    for &d in digits {
        v = v * 10 + d;
    }
    v
}

/// Round to `prec` significant digits; unchanged when it already fits.
fn round_to_prec(d: &Dec, prec: i32, mode: RoundMode) -> Result<Dec, ()> {
    if prec <= 0 || d.u.is_zero() {
        return Ok(d.clone());
    }
    let digits_str = d.u.abs().to_string();
    let digits: Vec<u8> = digits_str.bytes().map(|b| b - b'0').collect();
    if digits.len() as i32 <= prec {
        return Ok(d.clone());
    }
    let drop = digits.len() - prec as usize;
    let rounded = round_sig_digits(&digits, prec as usize, mode);
    let mut u = digits_to_bigint(&rounded);
    if d.u.is_negative() {
        u = -u;
    }
    let scale = d.scale.checked_sub(drop as i32).ok_or(())?;
    Ok(Dec { u, scale })
}

/// Parse a Java `BigDecimal(String)` literal, optionally rounding to `prec`
/// significant digits (what `BigDecimal(String, MathContext)` does).
fn parse_decimal(s: &str, prec: Option<i32>) -> Result<Dec, ()> {
    let b = s.as_bytes();
    let mut i = 0;
    let mut neg = false;
    if i < b.len() && (b[i] == b'+' || b[i] == b'-') {
        neg = b[i] == b'-';
        i += 1;
    }
    let mut int_digits = String::new();
    while i < b.len() && b[i].is_ascii_digit() {
        int_digits.push(b[i] as char);
        i += 1;
    }
    let mut frac_digits = String::new();
    if i < b.len() && b[i] == b'.' {
        i += 1;
        while i < b.len() && b[i].is_ascii_digit() {
            frac_digits.push(b[i] as char);
            i += 1;
        }
    }
    if int_digits.is_empty() && frac_digits.is_empty() {
        return Err(());
    }
    let mut exp: i64 = 0;
    if i < b.len() && (b[i] == b'e' || b[i] == b'E') {
        i += 1;
        let mut eneg = false;
        if i < b.len() && (b[i] == b'+' || b[i] == b'-') {
            eneg = b[i] == b'-';
            i += 1;
        }
        if i >= b.len() || !b[i].is_ascii_digit() {
            return Err(());
        }
        let mut e: i64 = 0;
        while i < b.len() && b[i].is_ascii_digit() {
            e = e
                .checked_mul(10)
                .and_then(|v| v.checked_add((b[i] - b'0') as i64))
                .ok_or(())?;
            if e > 2_000_000_000 {
                return Err(());
            }
            i += 1;
        }
        exp = if eneg { -e } else { e };
    }
    if i != b.len() {
        return Err(());
    }
    let all = format!("{int_digits}{frac_digits}");
    let scale64 = frac_digits.len() as i64 - exp;
    let scale: i32 = i32::try_from(scale64).map_err(|_| ())?;
    let mut u = BigInt::parse_bytes(all.as_bytes(), 10).ok_or(())?;
    if neg {
        u = -u;
    }
    let d = Dec { u, scale };
    match prec {
        Some(p) => round_to_prec(&d, p, RoundMode::HalfEven),
        None => Ok(d),
    }
}

/// Java `equals`: unscaled value AND scale must match.
fn eq_dec(a: &Dec, b: &Dec) -> bool {
    a.u == b.u && a.scale == b.scale
}

fn cmp_mag(a: &Dec, b: &Dec) -> Ordering {
    let da = a.u.abs().to_string();
    let db = b.u.abs().to_string();
    let ea = da.len() as i64 - 1 - a.scale as i64;
    let eb = db.len() as i64 - 1 - b.scale as i64;
    if ea != eb {
        return ea.cmp(&eb);
    }
    let n = da.len().max(db.len());
    let pa = format!("{}{}", da, "0".repeat(n - da.len()));
    let pb = format!("{}{}", db, "0".repeat(n - db.len()));
    pa.cmp(&pb)
}

/// Java `compareTo`: numeric, scale-insensitive.
fn cmp_dec(a: &Dec, b: &Dec) -> Ordering {
    if a.u.is_zero() && b.u.is_zero() {
        return Ordering::Equal;
    }
    if a.u.is_zero() {
        return if b.u.is_negative() {
            Ordering::Greater
        } else {
            Ordering::Less
        };
    }
    if b.u.is_zero() {
        return if a.u.is_negative() {
            Ordering::Less
        } else {
            Ordering::Greater
        };
    }
    let aneg = a.u.is_negative();
    let bneg = b.u.is_negative();
    if aneg != bneg {
        return if aneg {
            Ordering::Less
        } else {
            Ordering::Greater
        };
    }
    let ord = cmp_mag(a, b);
    if aneg { ord.reverse() } else { ord }
}

fn add_exact(a: &Dec, b: &Dec) -> Result<Dec, ()> {
    let scale = a.scale.max(b.scale);
    let au = a.u.clone() * pow10(scale as i64 - a.scale as i64)?;
    let bu = b.u.clone() * pow10(scale as i64 - b.scale as i64)?;
    Ok(Dec { u: au + bu, scale })
}

fn sub_exact(a: &Dec, b: &Dec) -> Result<Dec, ()> {
    add_exact(a, &b.negate())
}

fn mul_exact(a: &Dec, b: &Dec) -> Result<Dec, ()> {
    Ok(Dec {
        u: a.u.clone() * b.u.clone(),
        scale: a.scale.checked_add(b.scale).ok_or(())?,
    })
}

fn mul_mc(a: &Dec, b: &Dec, prec: i32) -> Result<Dec, ()> {
    round_to_prec(&mul_exact(a, b)?, prec, RoundMode::HalfEven)
}

/// Java `divide(divisor, MathContext)`: quotient rounded to `prec`
/// significant digits; exact when it fits, preferred scale otherwise.
fn div_mc(a: &Dec, b: &Dec, prec: i32, mode: RoundMode) -> Result<Dec, ()> {
    if prec <= 0 || b.u.is_zero() {
        return Err(());
    }
    if a.u.is_zero() {
        return Ok(Dec {
            u: BigInt::zero(),
            scale: a.scale.checked_sub(b.scale).ok_or(())?,
        });
    }
    let neg = a.u.is_negative() != b.u.is_negative();
    let pref = a.scale.checked_sub(b.scale).ok_or(())?;
    let (au, bu) = (a.u.abs(), b.u.abs());
    let (int_part, mut rem) = au.div_rem(&bu);
    let int_str = int_part.to_string();
    let int_digits = int_str.len();
    let int_digs: Vec<u8> = int_str.bytes().map(|c| c - b'0').collect();
    let p = prec as usize;
    let mut sig: Vec<u8> = Vec::new();
    let mut leading_zeros = 0usize;
    let mut tail_nonzero = false;
    let mut fed = 0usize;
    for &d in &int_digs {
        if sig.len() == p + 1 {
            break;
        }
        fed += 1;
        if sig.is_empty() && d == 0 {
            leading_zeros += 1;
        } else {
            sig.push(d);
        }
    }
    if sig.len() == p + 1 {
        tail_nonzero = int_digs[fed..].iter().any(|&d| d != 0) || !rem.is_zero();
    } else {
        let mut frac_fed = 0usize;
        while sig.len() < p + 1 && !rem.is_zero() {
            if frac_fed > MAX_DIGITS as usize {
                return Err(());
            }
            frac_fed += 1;
            rem *= 10;
            let (d, r) = rem.div_rem(&bu);
            rem = r;
            let d8 = d.to_u8().ok_or(())?;
            if sig.is_empty() && d8 == 0 {
                leading_zeros += 1;
            } else {
                sig.push(d8);
            }
        }
        if sig.len() == p + 1 {
            tail_nonzero = !rem.is_zero();
        }
    }
    if sig.is_empty() {
        return Err(()); // unreachable: a != 0 guarantees a nonzero digit
    }
    // Place exponent of the leading significant digit.
    let e = int_digits as i64 - 1 - leading_zeros as i64 - pref as i64;
    if sig.len() <= p {
        // Exact.
        let scale64 = sig.len() as i64 - 1 - e;
        let scale = i32::try_from(scale64).map_err(|_| ())?;
        let mut u = digits_to_bigint(&sig);
        if neg {
            u = -u;
        }
        return Ok(Dec { u, scale });
    }
    let round_digit = sig[p];
    let tail = tail_nonzero || sig[p + 1..].iter().any(|&d| d != 0);
    let up = match round_digit {
        0..=4 => false,
        6..=9 => true,
        _ => {
            if tail {
                true
            } else {
                match mode {
                    RoundMode::HalfUp => true,
                    RoundMode::HalfEven => sig[p - 1] % 2 == 1,
                    _ => false,
                }
            }
        }
    };
    let mut kept: Vec<u8> = sig[..p].to_vec();
    let mut e2 = e;
    if up {
        let mut i = kept.len();
        let mut carry = true;
        while carry && i > 0 {
            i -= 1;
            if kept[i] == 9 {
                kept[i] = 0;
            } else {
                kept[i] += 1;
                carry = false;
            }
        }
        if carry {
            kept = vec![1u8];
            kept.extend(std::iter::repeat_n(0, p));
            e2 += 1;
        }
    }
    let scale64 = p as i64 - 1 - e2;
    let scale = i32::try_from(scale64).map_err(|_| ())?;
    let mut u = digits_to_bigint(&kept);
    if neg {
        u = -u;
    }
    Ok(Dec { u, scale })
}

/// Truncated integer part of a/b (toward zero), scale 0.
fn integral_quotient(a: &Dec, b: &Dec) -> Result<Dec, ()> {
    if b.u.is_zero() {
        return Err(());
    }
    let sd = b.scale as i64 - a.scale as i64;
    let (num, den) = if sd >= 0 {
        (a.u.clone() * pow10(sd)?, b.u.clone())
    } else {
        (a.u.clone(), b.u.clone() * pow10(-sd)?)
    };
    Ok(Dec {
        u: num / den,
        scale: 0,
    })
}

fn truncate_sig(d: &Dec, prec: i32) -> Result<Dec, ()> {
    let digits = d.u.abs().to_string();
    if digits.len() as i32 <= prec {
        return Ok(d.clone());
    }
    let drop = digits.len() - prec as usize;
    let div = pow10(drop as i64)?;
    let mut u = d.u.clone() / &div * &div;
    if d.u.is_negative() {
        u = -u.abs();
    }
    Ok(Dec { u, scale: d.scale })
}

/// Java `remainder(divisor, MathContext)`.
fn rem_mc(a: &Dec, b: &Dec, prec: i32) -> Result<Dec, ()> {
    let mut q = integral_quotient(a, b)?;
    if q.u.abs().to_string().len() as i32 > prec {
        q = truncate_sig(&q, prec)?;
    }
    sub_exact(a, &mul_exact(&q, b)?)
}

/// Java `remainder(divisor)` (no context): exact.
fn rem_exact(a: &Dec, b: &Dec) -> Result<Dec, ()> {
    let q = integral_quotient(a, b)?;
    sub_exact(a, &mul_exact(&q, b)?)
}

fn set_scale(d: &Dec, new_scale: i32, mode: RoundMode) -> Result<Dec, ()> {
    if new_scale == d.scale {
        return Ok(d.clone());
    }
    if new_scale > d.scale {
        let diff = new_scale as i64 - d.scale as i64;
        return Ok(Dec {
            u: d.u.clone() * pow10(diff)?,
            scale: new_scale,
        });
    }
    let drop = d.scale as i64 - new_scale as i64;
    let div = pow10(drop)?;
    let (mut q, r) = d.u.div_rem(&div);
    if !r.is_zero() {
        let up = match mode {
            RoundMode::HalfEven | RoundMode::HalfUp => {
                let twice: BigInt = r.abs() * 2;
                match twice.cmp(&div) {
                    Ordering::Greater => true,
                    Ordering::Less => false,
                    Ordering::Equal => match mode {
                        RoundMode::HalfUp => true,
                        _ => (q.abs() % 2) == BigInt::one(),
                    },
                }
            }
            RoundMode::Floor => d.u.is_negative(),
            RoundMode::Ceiling => !d.u.is_negative(),
        };
        if up {
            q += if d.u.is_negative() {
                BigInt::from(-1)
            } else {
                BigInt::one()
            };
        }
    }
    Ok(Dec {
        u: q,
        scale: new_scale,
    })
}

/// Kotlin `BigDecimal.toInt()`: truncate toward zero, low 32 bits.
fn to_int_trunc(d: &Dec) -> i32 {
    let q: BigInt = if d.scale >= 0 {
        let ndig = d.u.abs().to_string().len() as i64;
        if ndig <= d.scale as i64 {
            BigInt::zero()
        } else {
            match pow10(d.scale as i64) {
                Ok(p) => d.u.clone() / p,
                Err(()) => return 0,
            }
        }
    } else {
        let k = -d.scale as i64;
        if k >= 32 {
            BigInt::zero()
        } else {
            match pow10(k) {
                Ok(p) => d.u.clone() * p,
                Err(()) => return 0,
            }
        }
    };
    let m = BigInt::from(1) << 32;
    q.rem_euclid(&m).to_u32().map_or(0, |v| v as i32)
}

/// Kotlin `BigDecimal.intValueExact()`.
fn int_value_exact(d: &Dec) -> Result<i32, ()> {
    let q: BigInt = if d.scale >= 0 {
        let div = pow10(d.scale as i64)?;
        if !(d.u.clone() % &div).is_zero() {
            return Err(());
        }
        d.u.clone() / div
    } else {
        d.u.clone() * pow10(-d.scale as i64)?
    };
    q.to_i32().ok_or(())
}

/// Nearest f64 (correctly rounded via string parse; overflow -> infinity).
fn to_double(d: &Dec) -> Result<f64, ()> {
    if d.u.is_zero() {
        return Ok(0.0);
    }
    let neg = d.u.is_negative();
    let digits = d.u.abs().to_string();
    let e = digits.len() as i64 - 1 - d.scale as i64;
    let mant = if digits.len() == 1 {
        digits.clone()
    } else {
        format!("{}.{}", &digits[..1], &digits[1..])
    };
    let s = format!("{}{}e{}", if neg { "-" } else { "" }, mant, e);
    s.parse::<f64>().map_err(|_| ())
}

/// Java `new BigDecimal(double)`: exact decimal value of the binary double.
fn dec_from_f64(d: f64) -> Result<Dec, ()> {
    if !d.is_finite() {
        return Err(());
    }
    if d == 0.0 {
        return Ok(Dec::zero());
    }
    let bits = d.to_bits();
    let neg = bits >> 63 != 0;
    let exp_bits = ((bits >> 52) & 0x7ff) as i64;
    let frac = bits & ((1u64 << 52) - 1);
    let (m, e): (u64, i64) = if exp_bits == 0 {
        (frac, -1074)
    } else {
        ((1u64 << 52) | frac, exp_bits - 1075)
    };
    let mut u = BigInt::from(m);
    let scale: i32 = if e >= 0 {
        u <<= e as usize;
        0
    } else {
        let k = (-e) as u32;
        u *= BigInt::from(5).pow(k);
        (-e) as i32
    };
    if neg {
        u = -u;
    }
    Ok(Dec { u, scale })
}

fn pow_int(base: &Dec, exp: i32, prec: i32) -> Result<Dec, ()> {
    if exp == 0 {
        return Ok(Dec::one());
    }
    if exp < 0 {
        let pos = pow_int(base, exp.checked_neg().ok_or(())?, prec)?;
        return div_mc(&Dec::one(), &pos, prec, RoundMode::HalfEven);
    }
    if base.u.is_zero() {
        return Ok(Dec {
            u: BigInt::zero(),
            scale: base.scale.checked_mul(exp).ok_or(())?,
        });
    }
    let ndig = base.u.abs().to_string().len() as i64;
    if ndig * exp as i64 > MAX_DIGITS {
        return Err(());
    }
    let u = base.u.pow(exp as u32);
    let scale = base.scale.checked_mul(exp).ok_or(())?;
    round_to_prec(&Dec { u, scale }, prec, RoundMode::HalfEven)
}

/// `Evaluator`'s custom `BigDecimal.pow(n: BigDecimal)`.
fn pow_dec(base: &Dec, exp: &Dec) -> Result<Dec, ()> {
    let sign = exp.signum();
    let right = mul_exact(exp, &Dec::from_i32(sign))?;
    let rem = rem_exact(&right, &Dec::one())?;
    let int_part = sub_exact(&right, &rem)?;
    let n = int_value_exact(&int_part)?;
    let int_pow = pow_int(base, n, PREC128)?;
    let double_pow = dec_from_f64(to_double(base)?.powf(to_double(&rem)?))?;
    let mut result = mul_mc(&int_pow, &double_pow, PREC128)?;
    if sign == -1 {
        result = div_mc(&Dec::one(), &result, PREC128, RoundMode::HalfUp)?;
    }
    Ok(result)
}

fn strip_trailing_zeros(d: &Dec) -> Dec {
    if d.u.is_zero() {
        return Dec::zero();
    }
    let ten = BigInt::from(10);
    let mut u = d.u.clone();
    let mut scale = d.scale;
    while (&u % &ten).is_zero() {
        u /= &ten;
        match scale.checked_sub(1) {
            Some(s) => scale = s,
            None => break,
        }
    }
    Dec { u, scale }
}

fn to_plain_string(d: &Dec) -> Result<String, ()> {
    let neg = d.u.is_negative();
    let digits = d.u.abs().to_string();
    let mut s = String::new();
    if neg {
        s.push('-');
    }
    if d.scale == 0 {
        s.push_str(&digits);
    } else if d.scale > 0 {
        let sc = d.scale as usize;
        if sc > MAX_DIGITS as usize {
            return Err(());
        }
        if digits.len() > sc {
            s.push_str(&digits[..digits.len() - sc]);
            s.push('.');
            s.push_str(&digits[digits.len() - sc..]);
        } else {
            s.push_str("0.");
            s.push_str(&"0".repeat(sc - digits.len()));
            s.push_str(&digits);
        }
    } else {
        let zeros = -d.scale as i64;
        if zeros > MAX_DIGITS {
            return Err(());
        }
        s.push_str(&digits);
        s.push_str(&"0".repeat(zeros as usize));
    }
    Ok(s)
}

/// Java `BigDecimal.toString()`.
fn to_java_string(d: &Dec) -> Result<String, ()> {
    let digits = d.u.abs().to_string();
    let adj = digits.len() as i64 - 1 - d.scale as i64;
    if d.scale == 0 {
        let mut s = String::new();
        if d.u.is_negative() {
            s.push('-');
        }
        s.push_str(&digits);
        return Ok(s);
    }
    if d.scale > 0 && adj >= -6 {
        return to_plain_string(d);
    }
    let mut s = String::new();
    if d.u.is_negative() {
        s.push('-');
    }
    s.push_str(&digits[..1]);
    if digits.len() > 1 {
        s.push('.');
        s.push_str(&digits[1..]);
    }
    s.push('E');
    if adj >= 0 {
        s.push('+');
    }
    s.push_str(&adj.to_string());
    Ok(s)
}

// ---------------------------------------------------------------------------
// Scanner (calc/Scanner.kt): works on UTF-16 code units like Kotlin's Char.
// Identifiers/digits are ASCII-only by the member `isAlpha`/`isDigit` rules.
// ---------------------------------------------------------------------------

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
enum Tok {
    Plus,
    Minus,
    Star,
    Slash,
    Modulo,
    Exponent,
    Sqrt,
    Assign,
    EqEq,
    NotEq,
    Greater,
    GreaterEq,
    Less,
    LessEq,
    BarBar,
    AmpAmp,
    Comma,
    LParen,
    RParen,
    Number,
    Ident,
    Eof,
}

#[derive(Clone, Debug)]
struct Token {
    ty: Tok,
    lexeme: String,
    literal: Option<Dec>,
}

fn is_digit_unit(c: u16) -> bool {
    c == b'.' as u16 || (b'0' as u16..=b'9' as u16).contains(&c)
}

fn is_alpha_unit(c: u16) -> bool {
    (b'a' as u16..=b'z' as u16).contains(&c)
        || (b'A' as u16..=b'Z' as u16).contains(&c)
        || c == b'_' as u16
}

/// Scanner's `isDigit(char, previousChar, nextChar)`.
fn is_digit_ext(c: u16, prev: u16, next: u16) -> bool {
    if is_digit_unit(c) {
        return true;
    }
    match c {
        0x65 | 0x45 => {
            // 'e' | 'E'
            is_digit_unit(prev)
                && (is_digit_unit(next) || next == b'+' as u16 || next == b'-' as u16)
        }
        0x2b | 0x2d => {
            // '+' | '-'
            (prev == b'e' as u16 || prev == b'E' as u16) && is_digit_unit(next)
        }
        _ => false,
    }
}

struct Scanner {
    units: Vec<u16>,
    start: usize,
    current: usize,
    tokens: Vec<Token>,
}

impl Scanner {
    fn new(source: &str) -> Self {
        Scanner {
            units: source.encode_utf16().collect(),
            start: 0,
            current: 0,
            tokens: Vec::new(),
        }
    }

    fn scan_tokens(mut self) -> Result<Vec<Token>, ()> {
        while !self.is_at_end() {
            self.scan_token()?;
        }
        self.tokens.push(Token {
            ty: Tok::Eof,
            lexeme: String::new(),
            literal: None,
        });
        Ok(self.tokens)
    }

    fn is_at_end(&self) -> bool {
        self.current >= self.units.len()
    }

    fn advance(&mut self) -> u16 {
        let c = self.units[self.current];
        self.current += 1;
        c
    }

    fn peek(&self) -> u16 {
        if self.is_at_end() {
            0
        } else {
            self.units[self.current]
        }
    }

    fn peek_prev(&self) -> u16 {
        if self.current > 0 {
            self.units[self.current - 1]
        } else {
            0
        }
    }

    fn peek_next(&self) -> u16 {
        if self.current + 1 >= self.units.len() {
            0
        } else {
            self.units[self.current + 1]
        }
    }

    fn match_char(&mut self, expected: u16) -> bool {
        if self.is_at_end() || self.units[self.current] != expected {
            return false;
        }
        self.current += 1;
        true
    }

    fn add_token(&mut self, ty: Tok, literal: Option<Dec>) {
        let lexeme = String::from_utf16(&self.units[self.start..self.current]).unwrap_or_default();
        self.tokens.push(Token {
            ty,
            lexeme,
            literal,
        });
    }

    fn scan_token(&mut self) -> Result<(), ()> {
        self.start = self.current;
        let c = self.advance();
        match c {
            0x20 | 0x0d | 0x09 => {}
            0x2b => self.add_token(Tok::Plus, None),
            0x2d => self.add_token(Tok::Minus, None),
            0x2a => self.add_token(Tok::Star, None),
            0x2f => self.add_token(Tok::Slash, None),
            0x25 => self.add_token(Tok::Modulo, None),
            0x5e => self.add_token(Tok::Exponent, None),
            0x221a => self.add_token(Tok::Sqrt, None),
            0x3d => {
                if self.match_char(b'=' as u16) {
                    self.add_token(Tok::EqEq, None);
                } else {
                    self.add_token(Tok::Assign, None);
                }
            }
            0x21 => {
                if self.match_char(b'=' as u16) {
                    self.add_token(Tok::NotEq, None);
                } else {
                    return Err(());
                }
            }
            0x3e => {
                if self.match_char(b'=' as u16) {
                    self.add_token(Tok::GreaterEq, None);
                } else {
                    self.add_token(Tok::Greater, None);
                }
            }
            0x3c => {
                if self.match_char(b'=' as u16) {
                    self.add_token(Tok::LessEq, None);
                } else {
                    self.add_token(Tok::Less, None);
                }
            }
            0x7c => {
                if self.match_char(b'|' as u16) {
                    self.add_token(Tok::BarBar, None);
                } else {
                    return Err(());
                }
            }
            0x26 => {
                if self.match_char(b'&' as u16) {
                    self.add_token(Tok::AmpAmp, None);
                } else {
                    return Err(());
                }
            }
            0x2c => self.add_token(Tok::Comma, None),
            0x28 => self.add_token(Tok::LParen, None),
            0x29 => self.add_token(Tok::RParen, None),
            _ => {
                if is_digit_unit(c) {
                    self.number()?;
                } else if is_alpha_unit(c) {
                    self.identifier();
                } else {
                    return Err(());
                }
            }
        }
        Ok(())
    }

    fn number(&mut self) -> Result<(), ()> {
        while is_digit_unit(self.peek()) {
            self.advance();
        }
        if is_digit_ext(self.peek(), self.peek_prev(), self.peek_next()) {
            self.advance();
            while is_digit_ext(self.peek(), self.peek_prev(), self.peek_next()) {
                self.advance();
            }
        }
        // Lexeme is ASCII (digits, '.', 'e', 'E', '+', '-') by construction.
        let text: String = self.units[self.start..self.current]
            .iter()
            .map(|&u| u as u8 as char)
            .collect();
        let value = parse_decimal(&text, Some(PREC128))?;
        self.add_token(Tok::Number, Some(value));
        Ok(())
    }

    fn identifier(&mut self) {
        while is_alpha_unit(self.peek()) || is_digit_unit(self.peek()) {
            self.advance();
        }
        self.add_token(Tok::Ident, None);
    }
}

// ---------------------------------------------------------------------------
// Parser (calc/Parser.kt)
// ---------------------------------------------------------------------------

#[derive(Clone, Debug)]
enum Expr {
    Assign {
        name: String,
        value: Box<Expr>,
    },
    Logical {
        left: Box<Expr>,
        op: Tok,
        right: Box<Expr>,
    },
    Binary {
        left: Box<Expr>,
        op: Tok,
        right: Box<Expr>,
    },
    Unary {
        op: Tok,
        right: Box<Expr>,
    },
    Call {
        name: String,
        args: Vec<Expr>,
    },
    Literal(Dec),
    Variable(String),
    Grouping(Box<Expr>),
}

struct Parser {
    tokens: Vec<Token>,
    current: usize,
}

impl Parser {
    fn new(tokens: Vec<Token>) -> Self {
        Parser { tokens, current: 0 }
    }

    fn parse(&mut self) -> Result<Expr, ()> {
        let expr = self.expression()?;
        if !self.is_at_end() {
            return Err(());
        }
        Ok(expr)
    }

    fn expression(&mut self) -> Result<Expr, ()> {
        self.assignment()
    }

    fn assignment(&mut self) -> Result<Expr, ()> {
        let expr = self.or()?;
        if self.match_types(&[Tok::Assign]) {
            let value = self.assignment()?;
            if let Expr::Variable(name) = expr {
                return Ok(Expr::Assign {
                    name,
                    value: Box::new(value),
                });
            }
            return Err(());
        }
        Ok(expr)
    }

    fn or(&mut self) -> Result<Expr, ()> {
        let mut expr = self.and()?;
        while self.match_types(&[Tok::BarBar]) {
            let op = self.previous()?.ty;
            let right = self.and()?;
            expr = Expr::Logical {
                left: Box::new(expr),
                op,
                right: Box::new(right),
            };
        }
        Ok(expr)
    }

    fn and(&mut self) -> Result<Expr, ()> {
        let mut expr = self.equality()?;
        while self.match_types(&[Tok::AmpAmp]) {
            let op = self.previous()?.ty;
            let right = self.equality()?;
            expr = Expr::Logical {
                left: Box::new(expr),
                op,
                right: Box::new(right),
            };
        }
        Ok(expr)
    }

    fn equality(&mut self) -> Result<Expr, ()> {
        let mut left = self.comparison()?;
        while self.match_types(&[Tok::EqEq, Tok::NotEq]) {
            let op = self.previous()?.ty;
            let right = self.comparison()?;
            left = Expr::Binary {
                left: Box::new(left),
                op,
                right: Box::new(right),
            };
        }
        Ok(left)
    }

    fn comparison(&mut self) -> Result<Expr, ()> {
        let mut left = self.addition()?;
        while self.match_types(&[Tok::Greater, Tok::GreaterEq, Tok::Less, Tok::LessEq]) {
            let op = self.previous()?.ty;
            let right = self.addition()?;
            left = Expr::Binary {
                left: Box::new(left),
                op,
                right: Box::new(right),
            };
        }
        Ok(left)
    }

    fn addition(&mut self) -> Result<Expr, ()> {
        let mut left = self.multiplication()?;
        while self.match_types(&[Tok::Plus, Tok::Minus]) {
            let op = self.previous()?.ty;
            let right = self.multiplication()?;
            left = Expr::Binary {
                left: Box::new(left),
                op,
                right: Box::new(right),
            };
        }
        Ok(left)
    }

    fn multiplication(&mut self) -> Result<Expr, ()> {
        let mut left = self.unary()?;
        while self.match_types(&[Tok::Star, Tok::Slash, Tok::Modulo]) {
            let op = self.previous()?.ty;
            let right = self.unary()?;
            left = Expr::Binary {
                left: Box::new(left),
                op,
                right: Box::new(right),
            };
        }
        Ok(left)
    }

    fn unary(&mut self) -> Result<Expr, ()> {
        if self.match_types(&[Tok::Minus]) {
            let op = self.previous()?.ty;
            let right = self.unary()?;
            return Ok(Expr::Unary {
                op,
                right: Box::new(right),
            });
        }
        self.sqrt()
    }

    fn sqrt(&mut self) -> Result<Expr, ()> {
        if self.match_types(&[Tok::Sqrt]) {
            let op = self.previous()?.ty;
            let right = self.unary()?;
            return Ok(Expr::Unary {
                op,
                right: Box::new(right),
            });
        }
        self.exponent()
    }

    fn exponent(&mut self) -> Result<Expr, ()> {
        let mut left = self.call()?;
        if self.match_types(&[Tok::Exponent]) {
            let op = self.previous()?.ty;
            let right = self.unary()?;
            left = Expr::Binary {
                left: Box::new(left),
                op,
                right: Box::new(right),
            };
        }
        Ok(left)
    }

    fn call(&mut self) -> Result<Expr, ()> {
        if self.match_two(Tok::Ident, Tok::LParen) {
            let (name_tok, _) = self.previous_two()?;
            let name = name_tok.lexeme.clone();
            let mut args = Vec::new();
            if !self.check(Tok::RParen) {
                loop {
                    args.push(self.expression()?);
                    if !self.match_types(&[Tok::Comma]) {
                        break;
                    }
                }
            }
            self.consume(Tok::RParen)?;
            return Ok(Expr::Call { name, args });
        }
        self.primary()
    }

    fn primary(&mut self) -> Result<Expr, ()> {
        if self.match_types(&[Tok::Number]) {
            let lit = self.previous()?.literal.clone().ok_or(())?;
            return Ok(Expr::Literal(lit));
        }
        if self.match_types(&[Tok::Ident]) {
            let name = self.previous()?.lexeme.clone();
            return Ok(Expr::Variable(name));
        }
        if self.match_types(&[Tok::LParen]) {
            let expr = self.expression()?;
            self.consume(Tok::RParen)?;
            return Ok(Expr::Grouping(Box::new(expr)));
        }
        Err(())
    }

    fn match_types(&mut self, types: &[Tok]) -> bool {
        for &ty in types {
            if self.check(ty) {
                self.advance();
                return true;
            }
        }
        false
    }

    fn match_two(&mut self, first: Tok, second: Tok) -> bool {
        let start = self.current;
        if self.match_types(&[first]) && self.match_types(&[second]) {
            return true;
        }
        self.current = start;
        false
    }

    fn check(&self, ty: Tok) -> bool {
        if self.is_at_end() {
            false
        } else {
            self.peek().ty == ty
        }
    }

    fn consume(&mut self, ty: Tok) -> Result<(), ()> {
        if self.check(ty) {
            self.advance();
            Ok(())
        } else {
            Err(())
        }
    }

    fn advance(&mut self) {
        if !self.is_at_end() {
            self.current += 1;
        }
    }

    fn is_at_end(&self) -> bool {
        self.peek().ty == Tok::Eof
    }

    fn peek(&self) -> &Token {
        &self.tokens[self.current]
    }

    fn previous(&self) -> Result<&Token, ()> {
        if self.current == 0 {
            Err(())
        } else {
            Ok(&self.tokens[self.current - 1])
        }
    }

    fn previous_two(&self) -> Result<(&Token, &Token), ()> {
        if self.current < 2 {
            Err(())
        } else {
            Ok((
                &self.tokens[self.current - 2],
                &self.tokens[self.current - 1],
            ))
        }
    }
}

// ---------------------------------------------------------------------------
// Evaluator + Expressions (calc/Evaluator.kt, calc/Expressions.kt)
// ---------------------------------------------------------------------------

struct Evaluator {
    vars: HashMap<String, Dec>,
}

impl Evaluator {
    fn eval(&mut self, expr: &Expr) -> Result<Dec, ()> {
        match expr {
            Expr::Assign { name, value } => {
                let v = self.eval(value)?;
                // Stored under the raw lexeme (no lowercasing); lookups
                // lowercase, exactly like the Kotlin original.
                self.vars.insert(name.clone(), v.clone());
                Ok(v)
            }
            Expr::Logical { left, op, right } => match op {
                Tok::BarBar => {
                    let l = self.eval(left)?;
                    if l.is_truthy() {
                        Ok(Dec::one())
                    } else {
                        Ok(bool_dec(self.eval(right)?.is_truthy()))
                    }
                }
                Tok::AmpAmp => {
                    let l = self.eval(left)?;
                    if !l.is_truthy() {
                        Ok(Dec::zero())
                    } else {
                        Ok(bool_dec(self.eval(right)?.is_truthy()))
                    }
                }
                _ => Err(()),
            },
            Expr::Binary { left, op, right } => {
                let l = self.eval(left)?;
                let r = self.eval(right)?;
                match op {
                    Tok::Plus => add_exact(&l, &r),
                    Tok::Minus => sub_exact(&l, &r),
                    Tok::Star => mul_exact(&l, &r),
                    Tok::Slash => div_mc(&l, &r, PREC128, RoundMode::HalfEven),
                    Tok::Modulo => rem_mc(&l, &r, PREC128),
                    Tok::Exponent => pow_dec(&l, &r),
                    Tok::EqEq => Ok(bool_dec(eq_dec(&l, &r))),
                    Tok::NotEq => Ok(bool_dec(!eq_dec(&l, &r))),
                    Tok::Greater => Ok(bool_dec(cmp_dec(&l, &r) == Ordering::Greater)),
                    Tok::GreaterEq => Ok(bool_dec(cmp_dec(&l, &r) != Ordering::Less)),
                    Tok::Less => Ok(bool_dec(cmp_dec(&l, &r) == Ordering::Less)),
                    Tok::LessEq => Ok(bool_dec(cmp_dec(&l, &r) != Ordering::Greater)),
                    _ => Err(()),
                }
            }
            Expr::Unary { op, right } => {
                let r = self.eval(right)?;
                match op {
                    Tok::Minus => Ok(r.negate()),
                    Tok::Sqrt => pow_dec(&r, &Dec::half()),
                    _ => Err(()),
                }
            }
            Expr::Call { name, args } => {
                let fname = name.to_ascii_lowercase();
                let mut vals = Vec::with_capacity(args.len());
                for a in args {
                    vals.push(self.eval(a)?);
                }
                call_builtin(&fname, &vals)
            }
            Expr::Literal(v) => Ok(v.clone()),
            Expr::Variable(name) => self.vars.get(&name.to_ascii_lowercase()).cloned().ok_or(()),
            Expr::Grouping(inner) => self.eval(inner),
        }
    }
}

fn bool_dec(b: bool) -> Dec {
    if b { Dec::one() } else { Dec::zero() }
}

fn call_builtin(name: &str, args: &[Dec]) -> Result<Dec, ()> {
    match name {
        "abs" => {
            if args.len() != 1 {
                return Err(());
            }
            Ok(args[0].abs())
        }
        "sum" => {
            if args.is_empty() {
                return Err(());
            }
            let mut acc = args[0].clone();
            for a in &args[1..] {
                acc = add_exact(&acc, a)?;
            }
            Ok(acc)
        }
        "floor" => {
            if args.len() != 1 {
                return Err(());
            }
            set_scale(&args[0], 0, RoundMode::Floor)
        }
        "ceil" => {
            if args.len() != 1 {
                return Err(());
            }
            set_scale(&args[0], 0, RoundMode::Ceiling)
        }
        "round" => {
            if args.len() != 1 && args.len() != 2 {
                return Err(());
            }
            let scale = if args.len() == 2 {
                to_int_trunc(&args[1])
            } else {
                0
            };
            set_scale(&args[0], scale, RoundMode::HalfEven)
        }
        "min" => {
            if args.is_empty() {
                return Err(());
            }
            let mut best = args[0].clone();
            for a in &args[1..] {
                if cmp_dec(a, &best) == Ordering::Less {
                    best = a.clone();
                }
            }
            Ok(best)
        }
        "max" => {
            if args.is_empty() {
                return Err(());
            }
            let mut best = args[0].clone();
            for a in &args[1..] {
                if cmp_dec(a, &best) == Ordering::Greater {
                    best = a.clone();
                }
            }
            Ok(best)
        }
        "if" => {
            if args.len() < 3 {
                return Err(());
            }
            if args[0].is_truthy() {
                Ok(args[1].clone())
            } else {
                Ok(args[2].clone())
            }
        }
        _ => Err(()),
    }
}

struct Expressions {
    ev: Evaluator,
}

impl Expressions {
    fn new() -> Result<Self, ()> {
        // BigDecimal(Math.PI).setScale(33, HALF_EVEN) then parsed with
        // DECIMAL128; same for Math.E.
        let pi = parse_decimal("3.141592653589793115997963468544185", Some(PREC128))?;
        let e = parse_decimal("2.718281828459045090795598298427649", Some(PREC128))?;
        let mut ev = Evaluator {
            vars: HashMap::new(),
        };
        ev.vars.insert("pi".to_string(), pi);
        ev.vars.insert("e".to_string(), e);
        Ok(Expressions { ev })
    }

    fn eval(&mut self, expression: &str) -> Result<Dec, ()> {
        let tokens = Scanner::new(expression).scan_tokens()?;
        let expr = Parser::new(tokens).parse()?;
        self.ev.eval(&expr)
    }
}

// ---------------------------------------------------------------------------
// Front door (calc/FrontDoor.kt)
// ---------------------------------------------------------------------------

fn calculate_inner(query: &str) -> Result<String, ()> {
    let mut exprs = Expressions::new()?;
    let value = exprs.eval(query)?;
    let rounded = round_to_prec(&value, PREC64, RoundMode::HalfEven)?;
    let stripped = strip_trailing_zeros(&rounded);
    let threshold = parse_decimal("9999999999999999", None)?;
    if cmp_dec(&stripped.abs(), &threshold) == Ordering::Greater {
        to_java_string(&stripped)
    } else {
        to_plain_string(&stripped)
    }
}

/// Port of `calculateEquation` (`calc/FrontDoor.kt:14`).
/// The Kotlin front door returns "" on any error, so this never returns Err.
pub fn calculate_equation(query: String) -> Result<String, String> {
    Ok(calculate_inner(&query).unwrap_or_default())
}
