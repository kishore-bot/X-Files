//! Small offline concept vectors for common business-document paraphrases.

const CONCEPTS: &[&[&str]] = &[
    &["payment terms", "payment due", "payment deadline", "when do i have to pay", "when should i pay", "when is payment due", "due within", "payable within", "net 30", "net 60"],
    &["late payment fee", "late fee", "overdue charge", "interest on arrears", "penalty for paying late"],
    &["return policy", "refund policy", "money back", "request a refund", "get a refund", "get reimbursed", "reimbursement"],
    &["automatic renewal", "automatically renews", "renews automatically", "auto renewal", "roll over", "rolling renewal", "renew without asking"],
    &["termination notice", "notice period", "prior written notice", "cancel the agreement", "end the contract"],
    &["warranty coverage", "limited warranty", "manufacturer guarantee", "defects in materials", "what does the warranty cover"],
    &["shipping costs", "shipping charges", "delivery fees", "freight charges", "cost to ship", "cost of delivery"],
    &["sales tax", "value added tax", "tax included", "tax exclusive", "vat included"],
    &["amount payable", "total due", "amount due", "balance payable", "how much do i owe", "what do i owe"],
    &["bank account", "bank transfer", "wire instructions", "remittance details", "where do i send the money", "how should i pay"],
];

#[derive(Clone, Copy)]
pub(crate) struct Concepts(u16);

impl Concepts {
    pub(crate) fn of(text: &str) -> Self {
        let mut words = String::with_capacity(text.len() + 2);
        words.push(' ');
        for word in text.split(|ch: char| !ch.is_ascii_alphanumeric()).filter(|word| !word.is_empty()) {
            words.extend(word.chars().map(|ch| ch.to_ascii_lowercase()));
            words.push(' ');
        }
        let mut bits = 0;
        for (index, phrases) in CONCEPTS.iter().enumerate() {
            let found = phrases.iter().any(|phrase| {
                words.match_indices(phrase).any(|(offset, _)| {
                    offset > 0 && words.as_bytes()[offset - 1] == b' ' && words.as_bytes().get(offset + phrase.len()) == Some(&b' ')
                })
            });
            if found {
                bits |= 1 << index;
            }
        }
        Self(bits)
    }

    pub(crate) fn is_empty(self) -> bool {
        self.0 == 0
    }

    pub(crate) fn similarity(self, other: Self) -> f32 {
        let shared = (self.0 & other.0).count_ones();
        if shared == 0 {
            return 0.0;
        }
        shared as f32 / ((self.0.count_ones() * other.0.count_ones()) as f32).sqrt()
    }
}
