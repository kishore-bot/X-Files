//! "At a glance", exported over UniFFI. The algorithms live in
//! `document-glance`; this module only maps types.

use document_glance::ItemKind;

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum NativeGlanceKind {
    Date,
    Amount,
    Phone,
    Email,
    Link,
    Reference,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct NativeGlanceItem {
    pub kind: NativeGlanceKind,
    /// As written in the document.
    pub text: String,
    /// `YYYY-MM-DD`, a plain number, digits, an email, an http(s) URL or the reference.
    pub value: String,
    pub label: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct NativeGlance {
    pub key_sentences: Vec<String>,
    pub keywords: Vec<String>,
    pub items: Vec<NativeGlanceItem>,
    pub words: u32,
    pub reading_minutes: u32,
    /// The language's English name, when it is clear.
    pub language: Option<String>,
}

/// Key sentences, keywords, things to act on, length and language of `text`.
#[uniffi::export]
pub fn glance_text(text: String) -> NativeGlance {
    let g = document_glance::glance(&text);
    NativeGlance {
        key_sentences: g.key_sentences,
        keywords: g.keywords,
        items: g
            .items
            .into_iter()
            .map(|i| NativeGlanceItem { kind: i.kind.into(), text: i.text, value: i.value, label: i.label })
            .collect(),
        words: g.words,
        reading_minutes: g.reading_minutes,
        language: g.language.map(|l| l.name.to_owned()),
    }
}

impl From<ItemKind> for NativeGlanceKind {
    fn from(kind: ItemKind) -> Self {
        match kind {
            ItemKind::Date => Self::Date,
            ItemKind::Amount => Self::Amount,
            ItemKind::Phone => Self::Phone,
            ItemKind::Email => Self::Email,
            ItemKind::Link => Self::Link,
            ItemKind::Reference => Self::Reference,
        }
    }
}
