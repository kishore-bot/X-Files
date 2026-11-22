//! `xfiles-mcp`: the Xfiles document engine as a Model Context Protocol server over stdio.
//!
//! Host-only binary. Standard output carries protocol messages, so diagnostics go to stderr.

mod config;
mod document;
mod error;
mod jobs;
mod output;
mod sandbox;
mod server;
mod services;
mod text;
mod tools;
mod validate;

use std::process::ExitCode;

use rmcp::ServiceExt;
use rmcp::transport::stdio;

use crate::config::{Command, USAGE};
use crate::sandbox::Sandbox;
use crate::server::XfilesServer;
use crate::services::Services;

#[tokio::main]
async fn main() -> ExitCode {
    let command = match config::parse(std::env::args().skip(1)) {
        Ok(command) => command,
        Err(message) => {
            eprintln!("xfiles-mcp: {message}");
            return ExitCode::from(2);
        }
    };
    let config = match command {
        Command::Help => {
            println!("{USAGE}");
            return ExitCode::SUCCESS;
        }
        Command::Version => {
            println!("xfiles-mcp {}", env!("CARGO_PKG_VERSION"));
            return ExitCode::SUCCESS;
        }
        Command::Serve(config) => config,
    };
    let sandbox = match Sandbox::new(&config.roots) {
        Ok(sandbox) => sandbox,
        Err(message) => {
            eprintln!("xfiles-mcp: {message}");
            return ExitCode::from(2);
        }
    };
    eprintln!("xfiles-mcp {}: serving {} root folder(s)", env!("CARGO_PKG_VERSION"), sandbox.roots().len());

    let services = Services::new(sandbox, config.timeout, config.max_input_bytes);
    match XfilesServer::new(services).serve(stdio()).await {
        Ok(session) => match session.waiting().await {
            Ok(_) => ExitCode::SUCCESS,
            Err(err) => {
                eprintln!("xfiles-mcp: the session ended with an error: {err}");
                ExitCode::FAILURE
            }
        },
        Err(err) => {
            eprintln!("xfiles-mcp: could not start the MCP session: {err}");
            ExitCode::FAILURE
        }
    }
}
