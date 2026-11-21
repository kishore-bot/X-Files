use fily_inference::{FilyEngine, FilyError};
use std::sync::Arc;

#[derive(uniffi::Record)]
pub struct FilyCompletion {
    pub text: String,
    pub tokens: u32,
    pub first_token_ms: u64,
    pub elapsed_ms: u64,
    pub gpu: bool,
}

#[derive(uniffi::Object)]
pub struct FilyModel(FilyEngine);

#[uniffi::export]
impl FilyModel {
    #[uniffi::constructor]
    pub fn new(path: String, prefer_gpu: bool, prefix: String) -> Result<Arc<Self>, crate::ReadError> {
        FilyEngine::load(path, prefer_gpu, prefix)
            .map(|engine| Arc::new(Self(engine)))
            .map_err(|detail| crate::ReadError::Internal { detail })
    }

    pub fn complete(&self, prompt: String, grammar: String, max_tokens: u32) -> Result<FilyCompletion, crate::ReadError> {
        self.0.complete(prompt, grammar, max_tokens).map(|result| FilyCompletion {
            text: result.text,
            tokens: result.tokens,
            first_token_ms: result.first_token_ms,
            elapsed_ms: result.elapsed_ms,
            gpu: result.gpu,
        }).map_err(|error| match error {
            FilyError::Cancelled => crate::ReadError::Cancelled { detail: "Fily request cancelled".into() },
            FilyError::Limit(detail) => crate::ReadError::LimitExceeded { detail },
            FilyError::Backend(detail) => crate::ReadError::Internal { detail },
        })
    }

    pub fn cancel(&self) {
        self.0.cancel();
    }
}
