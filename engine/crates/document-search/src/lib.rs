//! Offline search for EzFiles, in classical algorithms only: no models, no
//! network. Std only.
//!
//! - [`NameIndex`]: ranked, typo-tolerant matching of file names.
//! - [`SearchIndex`]: a BM25 full-text index with snippets, persisted in a
//!   private directory with bounded memory and disk.
//! - [`PassageIndex`] and [`PassageCache`]: the passages of one document that
//!   answer a question, chunked, ranked by BM25 and concept similarity, and packed into a budget.
//! - [`PlainText`] and [`html_to_text`]: plain text for indexing.

#![forbid(unsafe_code)]

mod concepts;
mod fold;
mod index;
mod lru;
mod names;
mod passage;
mod snippet;
mod store;
mod text;
mod tokens;

pub use fold::fold;
pub use index::{Added, Entry, Hit, IndexConfig, SearchIndex, analyze, query_terms};
pub use names::{NameIndex, NameMatch};
pub use passage::{Passage, PassageCache, PassageConfig, PassageIndex, Selection, SEPARATOR_CHARS};
pub use snippet::{QueryTerm, Snippet, best_snippet, page_of};
pub use text::{PlainText, html_to_text};
pub use tokens::{for_each_token, name_words, stem, tokens};
