//! Shared state for every tool: the path policy, the job runner and the input size limit.

use std::fs;
use std::path::PathBuf;
use std::time::Duration;

use crate::error::{Result, ToolError};
use crate::jobs::Jobs;
use crate::sandbox::Sandbox;

/// Heavy operations allowed at once. Each one can use a full core and a lot of memory.
const CONCURRENT_JOBS: usize = 2;

pub struct Services {
    pub sandbox: Sandbox,
    pub jobs: Jobs,
    pub max_input_bytes: u64,
}

impl Services {
    pub fn new(sandbox: Sandbox, timeout: Duration, max_input_bytes: u64) -> Self {
        Self { sandbox, jobs: Jobs::new(timeout, CONCURRENT_JOBS), max_input_bytes }
    }

    /// An existing input inside the roots, within the size limit.
    pub fn input(&self, raw: &str) -> Result<PathBuf> {
        let path = self.sandbox.input(raw)?;
        let size = fs::metadata(&path)?.len();
        if size > self.max_input_bytes {
            return Err(ToolError::invalid(format!(
                "{raw} is {:.1} MB; the limit is {} MB (set --max-input-mb to raise it)",
                size as f64 / 1_048_576.0,
                self.max_input_bytes / 1_048_576
            )));
        }
        Ok(path)
    }

    pub fn output(&self, raw: &str, overwrite: bool, extensions: &[&str], inputs: &[PathBuf]) -> Result<PathBuf> {
        self.sandbox.output(raw, overwrite, extensions, inputs)
    }
}
