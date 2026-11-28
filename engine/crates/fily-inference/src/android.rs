use std::num::NonZeroU32;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{self, Receiver, SyncSender};
use std::sync::{Arc, Mutex, OnceLock};
use std::thread;
use std::time::Instant;

use llama_cpp_2::context::LlamaContext;
use llama_cpp_2::context::params::LlamaContextParams;
use llama_cpp_2::llama_backend::LlamaBackend;
use llama_cpp_2::llama_batch::LlamaBatch;
use llama_cpp_2::model::LlamaModel;
use llama_cpp_2::model::params::LlamaModelParams;
use llama_cpp_2::sampling::LlamaSampler;
use llama_cpp_2::token::LlamaToken;
use llama_cpp_2::{LlamaStateSeqFlags, SeqState};

use crate::reuse::reusable_len;
use crate::{Completion, FilyError};

const CONTEXT_TOKENS: usize = 4096;
const BATCH_TOKENS: usize = 512;
const MAX_PROMPT_BYTES: usize = 128 * 1024;
const MAX_GENERATION_TOKENS: usize = 256;
static BACKEND: OnceLock<Result<LlamaBackend, String>> = OnceLock::new();

enum Request {
    Complete {
        prompt: String,
        grammar: String,
        max_tokens: usize,
        answer: SyncSender<Result<Completion, FilyError>>,
    },
}

pub struct FilyEngine {
    worker: SyncSender<Request>,
    cancelled: Arc<AtomicBool>,
    lane: Mutex<()>,
}

impl FilyEngine {
    pub fn load(path: String, prefer_gpu: bool, prefix: String) -> Result<Self, String> {
        let (commands, incoming) = mpsc::sync_channel(1);
        let (started, status) = mpsc::sync_channel(1);
        let cancelled = Arc::new(AtomicBool::new(false));
        let signal = cancelled.clone();
        thread::Builder::new()
            .name("fily-inference".into())
            .spawn(move || run(path, prefer_gpu, prefix, incoming, started, signal))
            .map_err(|error| error.to_string())?;
        status.recv().map_err(|_| "Fily worker stopped during model load")??;
        Ok(Self { worker: commands, cancelled, lane: Mutex::new(()) })
    }

    pub fn complete(&self, prompt: String, grammar: String, max_tokens: u32) -> Result<Completion, FilyError> {
        let _serial = self.lane.lock().map_err(|_| FilyError::Backend("Fily worker is unavailable".into()))?;
        if prompt.len() > MAX_PROMPT_BYTES || grammar.len() > MAX_PROMPT_BYTES {
            return Err(FilyError::Limit("The Fily prompt exceeds its input limit".into()));
        }
        // The flag is cleared after a request, not before it, so a cancel that lands while this
        // request is being handed to the worker still stops it.
        let result = self.run(prompt, grammar, max_tokens);
        self.cancelled.store(false, Ordering::Release);
        result
    }

    fn run(&self, prompt: String, grammar: String, max_tokens: u32) -> Result<Completion, FilyError> {
        if self.cancelled.load(Ordering::Acquire) {
            return Err(FilyError::Cancelled);
        }
        let (answer, result) = mpsc::sync_channel(1);
        self.worker
            .send(Request::Complete {
                prompt,
                grammar,
                max_tokens: (max_tokens as usize).clamp(1, MAX_GENERATION_TOKENS),
                answer,
            })
            .map_err(|_| FilyError::Backend("Fily worker stopped".into()))?;
        result.recv().map_err(|_| FilyError::Backend("Fily worker stopped during inference".into()))?
    }

    pub fn cancel(&self) {
        self.cancelled.store(true, Ordering::Release);
    }
}

impl Drop for FilyEngine {
    fn drop(&mut self) {
        self.cancel();
        // The last sender is dropped after this method. The worker then releases its model.
    }
}

fn run(
    path: String,
    prefer_gpu: bool,
    stable_prefix: String,
    incoming: Receiver<Request>,
    started: SyncSender<Result<(), String>>,
    cancelled: Arc<AtomicBool>,
) {
    let backend = match BACKEND.get_or_init(|| LlamaBackend::init().map_err(|error| error.to_string())) {
        Ok(backend) => backend,
        Err(error) => {
            let _ = started.send(Err(error.clone()));
            return;
        }
    };
    let gpu = prefer_gpu && backend.supports_gpu_offload();
    if gpu && run_with_model(backend, &path, true, &stable_prefix, &incoming, &started, &cancelled).is_ok() {
        return;
    }
    if let Err(error) = run_with_model(backend, &path, false, &stable_prefix, &incoming, &started, &cancelled) {
        let _ = started.send(Err(error));
    }
}

/// The saved stable prefix: its tokens and the sequence state right after they were decoded.
struct Prefix {
    tokens: Vec<LlamaToken>,
    state: SeqState,
}

/// What the context's KV cache holds: the tokens at positions `0..tokens.len()`. Only the worker
/// thread touches it. A model reload or a GPU-to-CPU fallback starts a new worker, so it also
/// starts a new, empty cache.
struct Kv {
    tokens: Vec<LlamaToken>,
    prefix: Option<Prefix>,
}

fn run_with_model(
    backend: &LlamaBackend,
    path: &str,
    gpu: bool,
    stable_prefix: &str,
    incoming: &Receiver<Request>,
    started: &SyncSender<Result<(), String>>,
    cancelled: &AtomicBool,
) -> Result<(), String> {
    let params = LlamaModelParams::default().with_n_gpu_layers(if gpu { u32::MAX } else { 0 });
    let model = LlamaModel::load_from_file(backend, path, &params).map_err(|error| error.to_string())?;
    let threads = thread::available_parallelism().map_or(4, |count| count.get().min(4)) as i32;
    let params = LlamaContextParams::default()
        .with_n_ctx(NonZeroU32::new(CONTEXT_TOKENS as u32))
        .with_n_batch(BATCH_TOKENS as u32)
        .with_n_ubatch(BATCH_TOKENS as u32)
        .with_n_threads(threads)
        .with_n_threads_batch(threads);
    let mut context = model.new_context(backend, params).map_err(|error| error.to_string())?;
    let vocab = model.vocab();
    let prefix_tokens = vocab.tokenize(stable_prefix.as_bytes(), false, true);
    let mut kv = Kv { tokens: Vec::new(), prefix: None };
    if !prefix_tokens.is_empty() && prefix_tokens.len() < CONTEXT_TOKENS / 2 {
        decode(&mut context, &prefix_tokens, 0, cancelled).map_err(String::from)?;
        kv.tokens = prefix_tokens.clone();
        kv.prefix = context
            .state_seq_get(0, LlamaStateSeqFlags::empty())
            .ok()
            .map(|state| Prefix { tokens: prefix_tokens, state });
    }
    if started.send(Ok(())).is_err() {
        return Ok(());
    }

    while let Ok(Request::Complete { prompt, grammar, max_tokens, answer }) = incoming.recv() {
        let result = serve(&model, &mut context, &mut kv, &prompt, &grammar, max_tokens, cancelled, gpu);
        let _ = answer.send(result);
    }
    Ok(())
}

/// Runs one request. A failure empties the cache (back to the saved prefix), so a partly
/// decoded prompt is never reused.
#[allow(clippy::too_many_arguments)]
fn serve(
    model: &LlamaModel,
    context: &mut LlamaContext<'_>,
    kv: &mut Kv,
    prompt: &str,
    grammar: &str,
    max_tokens: usize,
    cancelled: &AtomicBool,
    gpu: bool,
) -> Result<Completion, FilyError> {
    let result = generate(model, context, kv, prompt, grammar, max_tokens, cancelled, gpu);
    if result.is_err() {
        reset(context, kv);
    }
    result
}

/// Empties the cache, then restores the saved prefix when that works.
fn reset(context: &mut LlamaContext<'_>, kv: &mut Kv) {
    context.clear_kv_cache();
    kv.tokens.clear();
    if let Some(prefix) = &kv.prefix {
        if context.state_seq_set(&prefix.state, 0).is_ok() {
            kv.tokens = prefix.tokens.clone();
        }
    }
}

/// Leaves the context holding the longest reusable head of `tokens` and nothing more. Returns how
/// many leading tokens of `tokens` are already in the context.
fn prepare(context: &mut LlamaContext<'_>, kv: &mut Kv, tokens: &[LlamaToken]) -> usize {
    let reuse = reusable_len(&kv.tokens, tokens);
    if reuse == kv.tokens.len() {
        return reuse;
    }
    if context.kv_cache_seq_rm(0, Some(reuse as u32), None).is_ok() {
        kv.tokens.truncate(reuse);
        return reuse;
    }
    // Some layers (recurrent state) cannot drop a tail. Start again from the saved prefix when it
    // is a head of this prompt, otherwise from an empty cache.
    reset(context, kv);
    let head = reusable_len(&kv.tokens, tokens);
    if head < kv.tokens.len() {
        context.clear_kv_cache();
        kv.tokens.clear();
        return 0;
    }
    head
}

fn decode(context: &mut LlamaContext<'_>, tokens: &[LlamaToken], start: usize, cancelled: &AtomicBool) -> Result<(), FilyError> {
    let mut batch = LlamaBatch::new(BATCH_TOKENS, 1);
    for (offset, chunk) in tokens.chunks(BATCH_TOKENS).enumerate() {
        if cancelled.load(Ordering::Acquire) {
            return Err(FilyError::Cancelled);
        }
        batch.clear();
        let position = offset * BATCH_TOKENS;
        for (index, token) in chunk.iter().enumerate() {
            let at = position + index;
            batch.add(*token, (start + at) as i32, &[0], at + 1 == tokens.len()).map_err(|error| error.to_string())?;
        }
        context.decode(&mut batch).map_err(|error| error.to_string())?;
    }
    Ok(())
}

#[allow(clippy::too_many_arguments)]
fn generate(
    model: &LlamaModel,
    context: &mut LlamaContext<'_>,
    kv: &mut Kv,
    prompt: &str,
    grammar: &str,
    max_tokens: usize,
    cancelled: &AtomicBool,
    gpu: bool,
) -> Result<Completion, FilyError> {
    let start = Instant::now();
    let vocab = model.vocab();
    let tokens = vocab.tokenize(prompt.as_bytes(), false, true);
    if tokens.is_empty() || tokens.len() + max_tokens > CONTEXT_TOKENS {
        return Err(FilyError::Limit("Fily's 4096-token context is full".into()));
    }

    let reused = prepare(context, kv, &tokens);
    let fresh = &tokens[reused..];
    let prefill_start = Instant::now();
    decode(context, fresh, reused, cancelled)?;
    kv.tokens.extend_from_slice(fresh);
    let prefill_ms = prefill_start.elapsed().as_millis() as u64;

    let mut sampler = if grammar.is_empty() {
        LlamaSampler::greedy()
    } else {
        let grammar = LlamaSampler::grammar(model, grammar, "root").map_err(|error| error.to_string())?;
        LlamaSampler::chain_simple([grammar, LlamaSampler::greedy()])
    };
    let mut generated = Vec::new();
    let mut bytes = Vec::new();
    let mut first_token_ms = 0;
    let mut next = LlamaBatch::new(1, 1);
    for index in 0..max_tokens {
        if cancelled.load(Ordering::Acquire) {
            return Err(FilyError::Cancelled);
        }
        let token = sampler.sample(context, -1);
        if vocab.is_eog(token) {
            break;
        }
        if index == 0 {
            first_token_ms = start.elapsed().as_millis() as u64;
        }
        bytes.extend(vocab.token_to_piece(token, false, None));
        generated.push(token);
        if !grammar.is_empty() && serde_json::from_slice::<serde_json::Value>(&bytes).is_ok() {
            break;
        }
        next.clear();
        next.add(token, (tokens.len() + index) as i32, &[0], true).map_err(|error| error.to_string())?;
        context.decode(&mut next).map_err(|error| error.to_string())?;
        kv.tokens.push(token);
    }
    Ok(Completion {
        text: String::from_utf8_lossy(&bytes).trim().to_owned(),
        tokens: generated.len() as u32,
        first_token_ms,
        elapsed_ms: start.elapsed().as_millis() as u64,
        gpu,
        prefill_tokens: fresh.len() as u32,
        reused_tokens: reused as u32,
        prefill_ms,
    })
}
