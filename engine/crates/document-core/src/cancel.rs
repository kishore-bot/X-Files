use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};

use crate::{EngineError, Result};

/// Cooperative cancellation flag shared between the caller and a session.
///
/// Cancelling never interrupts a running parser; parsers and the coordinator
/// poll [`CancelToken::check`] at safe points (between batches and inside long
/// loops), so cancellation latency is bounded by the work done between checks.
#[derive(Debug, Clone, Default)]
pub struct CancelToken(Arc<AtomicBool>);

impl CancelToken {
    pub fn cancel(&self) {
        self.0.store(true, Ordering::Relaxed);
    }

    pub fn is_cancelled(&self) -> bool {
        self.0.load(Ordering::Relaxed)
    }

    pub fn check(&self) -> Result<()> {
        if self.is_cancelled() { Err(EngineError::cancelled()) } else { Ok(()) }
    }
}
