use thiserror::Error;

pub const RECOVERABLE_ERROR: u8 = 0x01;
pub const CRITICAL_ERROR: u8 = 0x02;

// This is not really dead code, #[wasm_error] macro replaces this by something else.
#[allow(dead_code)]
pub type Result<T> = std::result::Result<T, Error>;

#[derive(Error, Debug)]
pub enum Error {
    #[error("[Recoverable] {0}")]
    RecoverableError(String),
    #[error("[Critical] {0}")]
    CriticalError(String),
}

impl From<Error> for u8 {
    fn from(error: Error) -> Self {
        match error {
            Error::RecoverableError(_) => RECOVERABLE_ERROR,
            Error::CriticalError(_) => CRITICAL_ERROR,
        }
    }
}
