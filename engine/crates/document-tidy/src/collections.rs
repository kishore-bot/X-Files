//! Smart collections: transparent, rule-based sorting of documents into
//! Receipts & bills, Tickets & travel, Bank statements, Certificates & IDs,
//! Pay & tax, Medical, and Resumes & CVs.
//!
//! Each category has a table of weighted words and phrases for the text and
//! for the file name, plus points per money amount and per date found. A
//! document joins the best-scoring category that reaches its threshold, and
//! the match carries its reasons ("mentions 'invoice', 'subtotal'; 3
//! amounts"), so the user can see why.
//!
//! Tables are grouped by language in [`LEXICONS`]; adding a language is adding
//! a table, the scoring is shared. Patterns for amounts and dates are
//! language-neutral apart from the month names, which live in the table too.

/// The collections a document can join.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum CollectionKind {
    Receipts,
    Travel,
    BankStatements,
    Certificates,
    PayAndTax,
    Medical,
    Resumes,
}

/// Rules for one category in one language. Terms are lowercase words or
/// phrases of words separated by single spaces; they match whole words.
pub struct CategoryRules {
    pub kind: CollectionKind,
    pub terms: &'static [(&'static str, u32)],
    pub name_terms: &'static [(&'static str, u32)],
    /// Points per amount found, up to `max_amounts` amounts.
    pub amount_points: u32,
    pub max_amounts: u32,
    /// Points per date found, up to `max_dates` dates.
    pub date_points: u32,
    pub max_dates: u32,
    /// The score a document must reach to join.
    pub threshold: u32,
}

pub struct Lexicon {
    /// BCP 47 language tag, for whoever maintains the tables.
    pub language: &'static str,
    pub months: &'static [&'static str],
    pub categories: &'static [CategoryRules],
}

/// Why a document joined a collection.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CollectionMatch {
    pub kind: CollectionKind,
    pub score: u32,
    /// The strongest words or phrases found in the text, at most three.
    pub terms: Vec<String>,
    /// Words in the file name that matched.
    pub name_terms: Vec<String>,
    pub amounts: u32,
    pub dates: u32,
}

/// Text beyond this many bytes is not looked at: what identifies a receipt
/// or a ticket is on its first pages.
const MAX_TEXT_BYTES: usize = 100_000;

/// File-name points that place a document on their own.
const NAME_ALONE_POINTS: u32 = 8;

/// A term found more than once earns at most this many extra points.
const MAX_REPEAT_BONUS: u32 = 2;

/// The collection `text` (and its file `name`) belongs to, if any.
pub fn classify(name: &str, text: &str) -> Option<CollectionMatch> {
    let text = truncate(text, MAX_TEXT_BYTES);
    let words = normalise(text);
    let name_words = normalise(strip_extension(name));
    let mut best: Option<CollectionMatch> = None;
    for lexicon in LEXICONS {
        let amounts = count_amounts(text);
        let dates = count_numeric_dates(text) + count_month_dates(&words, lexicon.months);
        for rules in lexicon.categories {
            if let Some(found) = score(rules, &words, &name_words, amounts, dates) {
                // Career vocabulary must not outweigh an explicit resume name or
                // the work/education/skills structure of a banking-job resume.
                if found.kind == CollectionKind::Resumes {
                    return Some(found);
                }
                if best.as_ref().is_none_or(|b| found.score > b.score) {
                    best = Some(found);
                }
            }
        }
    }
    best
}

fn score(rules: &CategoryRules, words: &str, name_words: &str, amounts: u32, dates: u32) -> Option<CollectionMatch> {
    if rules.kind == CollectionKind::Resumes && !is_resume(words, name_words) {
        return None;
    }
    if rules.kind == CollectionKind::BankStatements && !is_banking_document(words, name_words) {
        return None;
    }
    let mut points = 0u32;
    let mut matched: Vec<(&str, u32)> = Vec::new();
    for &(term, weight) in rules.terms {
        let count = count_phrase(words, term);
        if count > 0 {
            points += weight + (count - 1).min(MAX_REPEAT_BONUS);
            matched.push((term, weight));
        }
    }
    let mut name_terms = Vec::new();
    let mut name_points = 0;
    for &(term, weight) in rules.name_terms {
        if count_phrase(name_words, term) > 0 {
            name_points += weight;
            name_terms.push(term.to_string());
        }
    }
    points += name_points;
    // Numbers alone never make a collection: most documents have some.
    if matched.is_empty() && name_terms.is_empty() {
        return None;
    }
    let amounts = amounts.min(rules.max_amounts);
    let dates = dates.min(rules.max_dates);
    points += amounts * rules.amount_points + dates * rules.date_points;
    // People name files on purpose: "payslip-march.pdf" needs no text to back it up.
    if points < rules.threshold && name_points < NAME_ALONE_POINTS {
        return None;
    }
    matched.sort_by(|a, b| b.1.cmp(&a.1));
    Some(CollectionMatch {
        kind: rules.kind,
        score: points,
        terms: matched.iter().take(3).map(|(t, _)| t.to_string()).collect(),
        name_terms,
        amounts: if rules.amount_points > 0 { amounts } else { 0 },
        dates: if rules.date_points > 0 { dates } else { 0 },
    })
}

fn has_any(words: &str, phrases: &[&str]) -> bool {
    phrases.iter().any(|phrase| count_phrase(words, phrase) > 0)
}

fn is_resume(words: &str, name_words: &str) -> bool {
    if has_any(name_words, &["resume", "résumé", "cv", "curriculum vitae"])
        || has_any(words, &["curriculum vitae"])
    {
        return true;
    }
    // Broad words such as "skills" or "education" alone also occur in articles.
    let experience = has_any(words, &["work experience", "professional experience", "employment history"]);
    let education = has_any(words, &["education", "academic qualifications", "educational qualifications"]);
    let skills = has_any(words, &["skills", "core competencies", "technical expertise"]);
    experience && education && skills
}

fn is_banking_document(words: &str, name_words: &str) -> bool {
    const DOCUMENT_TERMS: &[&str] = &[
        "bank statement", "account statement", "statement of account", "credit card statement",
        "account opening", "loan agreement", "loan sanction", "loan statement", "fixed deposit receipt",
    ];
    if has_any(name_words, DOCUMENT_TERMS) || has_any(name_words, &["estatement"]) || has_any(words, DOCUMENT_TERMS) {
        return true;
    }
    // A generic "statement" or a bank mentioned in prose is insufficient.
    has_any(words, &["opening balance", "closing balance"])
        && has_any(words, &["account number", "a c no", "iban"])
        && has_any(words, &["transactions", "debit", "credit", "withdrawal", "deposit"])
}

fn truncate(text: &str, max: usize) -> &str {
    if text.len() <= max {
        return text;
    }
    let mut end = max;
    while !text.is_char_boundary(end) {
        end -= 1;
    }
    &text[..end]
}

fn strip_extension(name: &str) -> &str {
    match name.rfind('.') {
        Some(dot) if dot > 0 && name.len() - dot <= 6 => &name[..dot],
        _ => name,
    }
}

/// Lowercase words separated by single spaces, with a space at each end, so
/// `" term "` matches whole words only. Letters and digits form words; a
/// letter-digit boundary also splits ("form16" is "form 16").
pub(crate) fn normalise(text: &str) -> String {
    let mut out = String::with_capacity(text.len() + 2);
    out.push(' ');
    let mut last: Option<bool> = None; // Some(is_digit) inside a word
    for ch in text.chars() {
        if ch.is_alphanumeric() {
            let digit = ch.is_ascii_digit();
            if last.is_some_and(|was| was != digit) {
                out.push(' ');
            }
            out.extend(ch.to_lowercase());
            last = Some(digit);
        } else if last.take().is_some() {
            out.push(' ');
        }
    }
    if !out.ends_with(' ') {
        out.push(' ');
    }
    out
}

fn count_phrase(words: &str, term: &str) -> u32 {
    let needle = format!(" {term} ");
    // Overlapping matches matter for repeated words ("bill bill"), so step by one word.
    let mut count = 0;
    let mut from = 0;
    while let Some(at) = words[from..].find(&needle) {
        count += 1;
        from += at + needle.len() - 1;
        if count > MAX_REPEAT_BONUS + 1 {
            break;
        }
    }
    count
}

/// Money amounts: numbers with two decimals ("1,299.00", "45,50"), or any
/// number right next to a currency sign or code ("₹ 500", "500 INR", "$12").
pub(crate) fn count_amounts(text: &str) -> u32 {
    let mut count = 0;
    for (start, end) in number_runs(text) {
        let run = &text[start..end];
        if run.contains('/') || run.matches(['.', '-']).count() > 1 {
            continue; // a date
        }
        let two_decimals = {
            let b = run.as_bytes();
            b.len() >= 4 && matches!(b[b.len() - 3], b'.' | b',') && b[b.len() - 3..].iter().skip(1).all(u8::is_ascii_digit)
        };
        if two_decimals || near_currency(&text[..start], &text[end..]) {
            count += 1;
            if count >= 50 {
                break;
            }
        }
    }
    count
}

const CURRENCY_SIGNS: &[char] = &['$', '€', '£', '₹', '¥', '₩', '₽', '₺', '₫', '฿', '₱', '₦'];
const CURRENCY_CODES: &[&str] = &[
    "rs", "rs.", "inr", "usd", "eur", "gbp", "aud", "cad", "jpy", "cny", "aed", "sar", "sgd", "chf", "zar",
];

fn near_currency(before: &str, after: &str) -> bool {
    let before = before.trim_end();
    let after = after.trim_start();
    if before.ends_with(CURRENCY_SIGNS) || after.starts_with(CURRENCY_SIGNS) {
        return true;
    }
    let word_before: String =
        before.chars().rev().take_while(|c| c.is_alphabetic() || *c == '.').collect::<Vec<_>>().into_iter().rev().collect();
    let word_after: String = after.chars().take_while(|c| c.is_alphabetic()).collect();
    let is_code = |w: &str| {
        let w = w.to_lowercase();
        CURRENCY_CODES.contains(&w.as_str())
    };
    is_code(&word_before) || is_code(&word_after)
}

/// Dates written with numbers: 12/05/2024, 2024-05-12, 12.05.24.
pub(crate) fn count_numeric_dates(text: &str) -> u32 {
    let mut count = 0;
    for (start, end) in number_runs(text) {
        let parts: Vec<&str> = text[start..end].split(['/', '-', '.']).collect();
        if parts.len() != 3 || !parts.iter().all(|p| !p.is_empty() && p.bytes().all(|b| b.is_ascii_digit())) {
            continue;
        }
        let lens: Vec<usize> = parts.iter().map(|p| p.len()).collect();
        let day_first = lens[0] <= 2 && lens[1] <= 2 && (lens[2] == 2 || lens[2] == 4);
        let year_first = lens[0] == 4 && lens[1] <= 2 && lens[2] <= 2;
        if day_first || year_first {
            count += 1;
            if count >= 50 {
                break;
            }
        }
    }
    count
}

/// Dates with a month name after a day or before a day or year: "12 March
/// 2024", "Mar 5", "May 2026" (but not "2026 may we").
fn count_month_dates(words: &str, months: &[&str]) -> u32 {
    let tokens: Vec<&str> = words.split(' ').filter(|w| !w.is_empty()).take(30_000).collect();
    let digits = |w: &str| !w.is_empty() && w.bytes().all(|b| b.is_ascii_digit());
    let is_day = |w: &str| digits(w) && w.len() <= 2;
    let is_day_or_year = |w: &str| digits(w) && (w.len() <= 2 || w.len() == 4);
    let mut count = 0;
    for (i, token) in tokens.iter().enumerate() {
        if months.contains(token) {
            let before = i.checked_sub(1).map(|j| tokens[j]).is_some_and(is_day);
            let after = tokens.get(i + 1).is_some_and(|w| is_day_or_year(w));
            if before || after {
                count += 1;
                if count >= 50 {
                    break;
                }
            }
        }
    }
    count
}

/// Byte ranges of runs of ASCII digits joined by single `.`, `,`, `/` or `-`,
/// not glued to letters (so "A320" or "ISO9001" are not numbers).
fn number_runs(text: &str) -> Vec<(usize, usize)> {
    let bytes = text.as_bytes();
    let mut runs = Vec::new();
    let mut i = 0;
    while i < bytes.len() {
        if !bytes[i].is_ascii_digit() || (i > 0 && is_word_byte(bytes[i - 1])) {
            i += 1;
            continue;
        }
        let start = i;
        let mut end = i;
        while end < bytes.len() {
            if bytes[end].is_ascii_digit() {
                end += 1;
            } else if matches!(bytes[end], b'.' | b',' | b'/' | b'-')
                && bytes.get(end + 1).is_some_and(u8::is_ascii_digit)
            {
                end += 1;
            } else {
                break;
            }
        }
        let glued = bytes.get(end).is_some_and(|&b| b.is_ascii_alphabetic());
        if !glued {
            runs.push((start, end));
        }
        i = end.max(start + 1);
        if runs.len() >= 5_000 {
            break;
        }
    }
    runs
}

fn is_word_byte(b: u8) -> bool {
    b.is_ascii_alphanumeric()
}

/// All languages, English first.
pub static LEXICONS: &[Lexicon] = &[ENGLISH];

const ENGLISH: Lexicon = Lexicon {
    language: "en",
    months: &[
        "jan", "january", "feb", "february", "mar", "march", "apr", "april", "may", "jun", "june", "jul", "july",
        "aug", "august", "sep", "sept", "september", "oct", "october", "nov", "november", "dec", "december",
    ],
    categories: &[
        CategoryRules {
            kind: CollectionKind::Resumes,
            terms: &[
                ("curriculum vitae", 14),
                ("resume", 4),
                ("résumé", 4),
                ("work experience", 6),
                ("professional experience", 6),
                ("employment history", 6),
                ("education", 4),
                ("academic qualifications", 4),
                ("educational qualifications", 4),
                ("skills", 4),
                ("core competencies", 4),
                ("technical expertise", 4),
                ("professional summary", 3),
                ("career objective", 3),
                ("certifications", 2),
            ],
            name_terms: &[("resume", 10), ("résumé", 10), ("cv", 10), ("curriculum vitae", 10)],
            amount_points: 0,
            max_amounts: 0,
            date_points: 0,
            max_dates: 0,
            threshold: 14,
        },
        CategoryRules {
            kind: CollectionKind::Receipts,
            terms: &[
                ("receipt", 8),
                ("invoice", 8),
                ("tax invoice", 4),
                ("invoice number", 4),
                ("invoice no", 4),
                ("bill", 4),
                ("bill to", 4),
                ("billing period", 6),
                ("amount due", 6),
                ("total amount", 4),
                ("total due", 5),
                ("subtotal", 5),
                ("sub total", 5),
                ("grand total", 6),
                ("amount paid", 5),
                ("payment received", 5),
                ("gstin", 5),
                ("gst", 3),
                ("vat", 3),
                ("cgst", 3),
                ("sgst", 3),
                ("order number", 4),
                ("order id", 3),
                ("qty", 3),
                ("unit price", 4),
                ("thank you for your purchase", 6),
                ("thank you for shopping", 6),
                ("cashier", 4),
                ("due date", 3),
            ],
            name_terms: &[("receipt", 8), ("invoice", 8), ("bill", 6), ("inv", 3)],
            amount_points: 2,
            max_amounts: 6,
            date_points: 1,
            max_dates: 2,
            threshold: 14,
        },
        CategoryRules {
            kind: CollectionKind::Travel,
            terms: &[
                ("boarding pass", 10),
                ("e ticket", 8),
                ("eticket", 8),
                ("ticket", 5),
                ("pnr", 8),
                ("flight", 4),
                ("airline", 3),
                ("airlines", 3),
                ("departure", 4),
                ("arrival", 3),
                ("gate", 2),
                ("seat", 2),
                ("itinerary", 7),
                ("booking reference", 7),
                ("booking id", 5),
                ("reservation", 4),
                ("confirmation number", 5),
                ("hotel", 3),
                ("check in", 2),
                ("passenger", 5),
                ("passengers", 5),
                ("train", 3),
                ("coach", 2),
                ("terminal", 2),
                ("baggage", 4),
                ("journey", 3),
            ],
            name_terms: &[
                ("ticket", 8),
                ("tickets", 8),
                ("eticket", 8),
                ("boarding", 8),
                ("itinerary", 8),
                ("booking", 5),
                ("flight", 6),
                ("pnr", 6),
            ],
            amount_points: 0,
            max_amounts: 0,
            date_points: 1,
            max_dates: 3,
            threshold: 13,
        },
        CategoryRules {
            kind: CollectionKind::BankStatements,
            terms: &[
                ("account statement", 10),
                ("bank statement", 10),
                ("statement of account", 10),
                ("credit card statement", 10),
                ("account opening", 10),
                ("loan agreement", 10),
                ("loan sanction", 10),
                ("loan statement", 10),
                ("fixed deposit receipt", 10),
                ("borrower", 4),
                ("principal amount", 4),
                ("interest rate", 4),
                ("applicant name", 4),
                ("customer name", 4),
                ("account type", 4),
                ("statement period", 7),
                ("statement", 3),
                ("opening balance", 7),
                ("closing balance", 7),
                ("available balance", 5),
                ("balance", 2),
                ("account number", 4),
                ("a c no", 3),
                ("ifsc", 6),
                ("iban", 4),
                ("sort code", 5),
                ("transactions", 3),
                ("debit", 3),
                ("credit", 2),
                ("withdrawal", 4),
                ("deposit", 3),
                ("cheque", 3),
                ("neft", 4),
                ("imps", 4),
                ("branch", 2),
            ],
            name_terms: &[
                ("bank statement", 10), ("account statement", 10), ("statement of account", 10),
                ("credit card statement", 10), ("account opening", 10), ("loan agreement", 10),
                ("loan sanction", 10), ("loan statement", 10), ("fixed deposit receipt", 10),
                ("statement", 3), ("bank", 3), ("estatement", 8), ("acct", 3), ("account", 2),
            ],
            amount_points: 1,
            max_amounts: 10,
            date_points: 1,
            max_dates: 6,
            threshold: 16,
        },
        CategoryRules {
            kind: CollectionKind::Certificates,
            terms: &[
                ("certificate", 7),
                ("this is to certify", 10),
                ("certified that", 8),
                ("awarded to", 6),
                ("has successfully completed", 8),
                ("diploma", 7),
                ("degree", 4),
                ("date of birth", 5),
                ("passport", 7),
                ("driving licence", 8),
                ("driving license", 8),
                ("driver license", 8),
                ("identity card", 7),
                ("aadhaar", 9),
                ("permanent account number", 8),
                ("nationality", 4),
                ("place of birth", 5),
                ("date of issue", 4),
                ("date of expiry", 5),
                ("valid until", 3),
                ("government of", 3),
                ("election commission", 6),
                ("marks obtained", 6),
            ],
            name_terms: &[
                ("certificate", 8),
                ("cert", 4),
                ("passport", 8),
                ("licence", 6),
                ("license", 6),
                ("aadhaar", 9),
                ("aadhar", 9),
                ("diploma", 8),
                ("marksheet", 8),
                ("pan", 4),
                ("id", 3),
            ],
            amount_points: 0,
            max_amounts: 0,
            date_points: 0,
            max_dates: 0,
            threshold: 10,
        },
        CategoryRules {
            kind: CollectionKind::PayAndTax,
            terms: &[
                ("payslip", 10),
                ("pay slip", 10),
                ("salary slip", 10),
                ("net pay", 8),
                ("gross pay", 7),
                ("basic salary", 6),
                ("earnings", 3),
                ("deductions", 4),
                ("provident fund", 5),
                ("income tax", 5),
                ("tax return", 7),
                ("form 16", 9),
                ("p60", 8),
                ("assessment year", 7),
                ("taxable income", 7),
                ("employee id", 4),
                ("pay period", 6),
                ("ytd", 3),
            ],
            name_terms: &[("payslip", 10), ("salary", 6), ("form 16", 8), ("tax", 4), ("itr", 6), ("w2", 6)],
            amount_points: 1,
            max_amounts: 6,
            date_points: 0,
            max_dates: 0,
            threshold: 13,
        },
        CategoryRules {
            kind: CollectionKind::Medical,
            terms: &[
                ("prescription", 8),
                ("patient", 5),
                ("patient name", 4),
                ("diagnosis", 6),
                ("lab report", 7),
                ("test report", 4),
                ("hospital", 4),
                ("clinic", 3),
                ("doctor", 3),
                ("mg", 2),
                ("tablet", 3),
                ("dosage", 5),
                ("hemoglobin", 6),
                ("haemoglobin", 6),
                ("reference range", 6),
                ("discharge summary", 9),
                ("vaccination", 6),
                ("medical", 3),
            ],
            name_terms: &[
                ("prescription", 9),
                ("lab", 4),
                ("medical", 6),
                ("vaccine", 6),
                ("vaccination", 6),
                ("discharge", 6),
            ],
            amount_points: 0,
            max_amounts: 0,
            date_points: 0,
            max_dates: 0,
            threshold: 13,
        },
    ],
};

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_shop_receipt_is_a_receipt_and_says_why() {
        let text = "SuperMart\nTAX INVOICE\nQty  Item   Price\n2 Milk  ₹ 56.00\n1 Bread 45.00\nSubtotal 101.00\nGST 5.05\nGrand Total ₹106.05\nThank you for shopping! 12/05/2024";
        let found = classify("IMG_2041.pdf", text).unwrap();
        assert_eq!(found.kind, CollectionKind::Receipts);
        assert_eq!(found.terms.first().map(String::as_str), Some("invoice"));
        assert!(found.amounts >= 4, "{found:?}");
        assert_eq!(found.dates, 1);
    }

    #[test]
    fn a_boarding_pass_is_travel() {
        let text = "BOARDING PASS\nPassenger: DOE/JANE\nFlight AI 101  Gate 23  Seat 14C\nDeparture 08:40 12 Mar 2025\nPNR: X7YQ2P";
        assert_eq!(classify("pass.pdf", text).unwrap().kind, CollectionKind::Travel);
    }

    #[test]
    fn a_bank_statement_is_a_statement() {
        let mut text = String::from("Statement of Account\nAccount Number 0012345678 IFSC HDFC0001234\nOpening Balance 10,000.00\n");
        for day in 1..=8 {
            text.push_str(&format!("{day:02}/04/2024 NEFT transfer debit 1,250.00\n"));
        }
        text.push_str("Closing Balance 0.00");
        let found = classify("April.pdf", &text).unwrap();
        assert_eq!(found.kind, CollectionKind::BankStatements);
    }

    #[test]
    fn the_file_name_alone_can_place_a_document() {
        let found = classify("Train_eTicket_Delhi.pdf", "").unwrap();
        assert_eq!(found.kind, CollectionKind::Travel);
        assert_eq!(found.name_terms, vec!["eticket".to_string()]);
        assert_eq!(classify("payslip-march.pdf", "").unwrap().kind, CollectionKind::PayAndTax);
    }

    #[test]
    fn resumes_match_names_and_career_structure_not_banking_vocabulary() {
        for name in ["Kishore_Resume.pdf", "Ananya-CV.docx", "curriculum vitae.pdf", "résumé.odt"] {
            let found = classify(name, "").unwrap();
            assert_eq!(found.kind, CollectionKind::Resumes, "{name}");
            assert!(!found.name_terms.is_empty());
        }
        let text = "Professional summary\nBank branch manager\nWork Experience\n\
            Prepared bank statements, account opening forms, loan agreements, debit and credit transactions.\n\
            Education\nBachelor of Commerce\nSkills\nBanking operations and customer service";
        let found = classify("scan.pdf", text).unwrap();
        assert_eq!(found.kind, CollectionKind::Resumes);
        assert!(found.terms.iter().any(|term| term == "work experience"));
        assert!(classify("notes.pdf", "Resume payments when the bank opens. Skills training continues.").is_none());
    }

    #[test]
    fn banking_documents_need_a_document_type_or_account_ledger_structure() {
        for name in ["bank_statement_april.pdf", "account-opening.pdf", "loan-agreement.pdf", "credit_card_statement.pdf"] {
            let found = classify(name, "").unwrap();
            assert_eq!(found.kind, CollectionKind::BankStatements, "{name}");
            assert!(!found.name_terms.is_empty());
        }
        let opening = "Account opening form\nApplicant name: Jane Doe\nAccount type: Savings";
        assert_eq!(classify("scan.pdf", opening).unwrap().kind, CollectionKind::BankStatements);
        let loan = "Loan agreement\nBorrower Jane Doe\nPrincipal amount 250000\nInterest rate 8 percent";
        assert_eq!(classify("scan.pdf", loan).unwrap().kind, CollectionKind::BankStatements);
        let ledger = "Account number 123456\nOpening balance 100.00\nTransactions debit 20.00\nClosing balance 80.00";
        assert_eq!(classify("scan.pdf", ledger).unwrap().kind, CollectionKind::BankStatements);
        assert!(classify("statement.pdf", "A personal statement about education and career ambitions.").is_none());
        assert!(classify("banking-notes.pdf", "Bank branch transactions include debit, credit and deposit.").is_none());
    }

    #[test]
    fn ordinary_documents_join_nothing() {
        let essay = "The history of the river valley shows how farming changed over many centuries. \
            In 1850 the population was 2,000 and by 1900 it had doubled.";
        assert_eq!(classify("essay.docx", essay), None);
        // A mention in passing is not enough.
        assert_eq!(classify("notes.txt", "Remember to keep the receipt for the new chair."), None);
        assert_eq!(classify("report.pdf", ""), None);
    }

    #[test]
    fn words_match_whole_words_only() {
        assert_eq!(count_phrase(&normalise("billing period, billboard"), "bill"), 0);
        assert_eq!(count_phrase(&normalise("Bill. bill"), "bill"), 2);
        assert_eq!(count_phrase(&normalise("form16"), "form 16"), 1);
    }

    #[test]
    fn amounts_and_dates_are_counted_without_confusing_them() {
        assert_eq!(count_amounts("Total: $12 and 1,299.00 and 45,50 and Rs. 500 and 300 INR"), 5);
        assert_eq!(count_amounts("Call 98765 43210 on 12/05/2024 at 10:30, flight A320"), 0);
        assert_eq!(count_numeric_dates("12/05/2024, 2024-05-12 and 1.2.24 but not 1,299.00 or 12/2024"), 3);
        let words = normalise("Issued 12 March 2024, valid until Mar 2026; may we help?");
        assert_eq!(count_month_dates(&words, ENGLISH.months), 2);
    }

    #[test]
    fn huge_text_is_bounded() {
        let text = "invoice total ₹ 10.00 ".repeat(200_000);
        let start = std::time::Instant::now();
        assert_eq!(classify("x.pdf", &text).unwrap().kind, CollectionKind::Receipts);
        assert!(start.elapsed().as_secs() < 5);
    }
}
