use crate::{run_script, run_script_int};

#[allow(dead_code)]
pub fn mark(name: String) {
    run_script!(format!("performance.mark('{}')", name))
}

#[allow(dead_code)]
pub fn measure(name: String, mark_begin: String, mark_end: String) {
    run_script!(format!("performance.measure('{}','{}','{}')", name, mark_begin, mark_end))
}

// FIXME: I think we could have problems in the future
// related to the resolution of DOMHighResTimeStamp and
// the use of i32 as the return value of this function.
#[allow(dead_code)]
pub fn get_time() -> i32 {
    run_script_int!("performance.now()")
}
