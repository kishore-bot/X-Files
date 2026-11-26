//! Runs engine work on blocking threads, bounded by a time limit, a cancel flag and a concurrency cap.

use std::sync::Arc;
use std::time::{Duration, Instant};

use document_core::CancelToken;
use document_parsers::PdfToolObserver;
use tokio::sync::Semaphore;

use crate::error::{Result, ToolError};

/// What a running job may read: whether to stop, and how long it has.
#[derive(Clone)]
pub struct JobContext {
    cancel: CancelToken,
    deadline: Instant,
    timeout_secs: u64,
}

impl JobContext {
    pub fn cancel_token(&self) -> &CancelToken {
        &self.cancel
    }

    /// True once the time limit has passed or the job was cancelled.
    pub fn is_stopped(&self) -> bool {
        self.cancel.is_cancelled() || Instant::now() >= self.deadline
    }

    /// Fails with a timeout error once the job should stop. Call between units of work.
    pub fn check(&self) -> Result<()> {
        if self.is_stopped() {
            Err(ToolError::Timeout(self.timeout_secs))
        } else {
            Ok(())
        }
    }
}

/// Lets the PDF tools poll the job's stop state between pages.
pub struct Progress<'a>(pub &'a JobContext);

impl PdfToolObserver for Progress<'_> {
    fn on_progress(&mut self, _done: u32, _total: u32) {}

    fn is_cancelled(&self) -> bool {
        self.0.is_stopped()
    }
}

// Dropping the awaiting request also signals the worker. The worker owns its slot until it exits.
struct CancelOnDrop(CancelToken);

impl Drop for CancelOnDrop {
    fn drop(&mut self) {
        self.0.cancel();
    }
}

pub struct Jobs {
    timeout: Duration,
    slots: Arc<Semaphore>,
}

impl Jobs {
    pub fn new(timeout: Duration, concurrent: usize) -> Self {
        Self { timeout, slots: Arc::new(Semaphore::new(concurrent.max(1))) }
    }

    /// Runs `work` on a blocking thread. When the limit passes the job is cancelled and the caller
    /// gets a timeout error; the thread stops at its next check, and its slot frees only then.
    pub async fn run<T, F>(&self, work: F) -> Result<T>
    where
        T: Send + 'static,
        F: FnOnce(JobContext) -> Result<T> + Send + 'static,
    {
        let permit = Arc::clone(&self.slots)
            .acquire_owned()
            .await
            .map_err(|_| ToolError::Internal("the job queue is closed".to_owned()))?;
        let ctx = JobContext {
            cancel: CancelToken::default(),
            deadline: Instant::now() + self.timeout,
            timeout_secs: self.timeout.as_secs(),
        };
        let _cancel_on_drop = CancelOnDrop(ctx.cancel.clone());
        let for_worker = ctx.clone();
        let handle = tokio::task::spawn_blocking(move || {
            let _permit = permit;
            work(for_worker)
        });
        match tokio::time::timeout(self.timeout, handle).await {
            Ok(Ok(result)) => result,
            Ok(Err(join)) => Err(ToolError::Internal(format!("the operation stopped unexpectedly ({join})"))),
            Err(_) => Err(ToolError::Timeout(self.timeout.as_secs())),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::sync::oneshot;

    #[tokio::test]
    async fn dropping_the_request_signals_the_blocking_worker() {
        let jobs = Jobs::new(Duration::from_secs(2), 1);
        let (started, running) = oneshot::channel();
        let (finished, stopped) = oneshot::channel();
        let request = tokio::spawn(async move {
            jobs.run(move |ctx| {
                let _ = started.send(());
                while !ctx.is_stopped() {
                    std::thread::sleep(Duration::from_millis(2));
                }
                let _ = finished.send(ctx.cancel_token().is_cancelled());
                Ok(())
            })
            .await
        });

        running.await.unwrap();
        request.abort();
        assert!(tokio::time::timeout(Duration::from_secs(1), stopped).await.unwrap().unwrap());
    }
}
