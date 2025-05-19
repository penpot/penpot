#[allow(unused_imports)]
#[cfg(target_arch = "wasm32")]
use crate::get_now;

#[allow(dead_code)]
#[cfg(target_arch = "wasm32")]
pub fn get_time() -> i32 {
    crate::get_now!() as i32
}

#[allow(dead_code)]
#[cfg(not(target_arch = "wasm32"))]
pub fn get_time() -> i32 {
    let now = std::time::Instant::now();
    now.elapsed().as_millis() as i32
}

#[macro_export]
macro_rules! mark {
    ($name:expr) => {
        #[cfg(all(feature = "profile-macros", target_arch = "wasm32"))]
        {
            use $crate::run_script;
            run_script!(format!("performance.mark('{}')", $name));
        }
    };
}

#[macro_export]
macro_rules! measure {
    ($name:expr) => {
        #[cfg(all(feature = "profile-macros", target_arch = "wasm32"))]
        {
            use $crate::run_script;
            run_script!(format!("performance.measure('{}')", $name));
        }
    };
    ($name:expr, $mark_begin:expr) => {
        #[cfg(all(feature = "profile-macros", target_arch = "wasm32"))]
        {
            use $crate::run_script;
            run_script!(format!(
                "performance.measure('{}','{}')",
                $name, $mark_begin
            ));
        }
    };
    ($name:expr, $mark_begin:expr, $mark_end:expr) => {
        #[cfg(all(feature = "profile-macros", target_arch = "wasm32"))]
        {
            use $crate::run_script;
            run_script!(format!(
                "performance.measure('{}','{}','{}')",
                $name, $mark_begin, $mark_end
            ));
        }
    };
}

#[macro_export]
macro_rules! begin_mark_name {
    ($name:expr) => {
        format!("{}::begin", $name)
    };
}

#[macro_export]
macro_rules! end_mark_name {
    ($name:expr) => {
        format!("{}::end", $name)
    };
}

#[macro_export]
macro_rules! measure_marks {
    ($name:expr) => {
        #[cfg(all(feature = "profile-macros", target_arch = "wasm32"))]
        {
            use $crate::{begin_mark_name, end_mark_name, measure};
            measure!($name, begin_mark_name!($name), end_mark_name!($name));
        }
    };
}

#[macro_export]
macro_rules! clear_marks {
    () => {
        use $crate::run_script;
        run_script!("performance.clearMarks()");
    };
    ($($name:expr),*) => {
        format!("{}", [$(format!("performance.clearMarks('{}')", $name)),*].join("; "))
    };
}

#[macro_export]
macro_rules! clear_measures {
    () => {
        use $crate::run_script;
        run_script!("performance.clearMeasures()");
    };
    ($($name:expr),*) => {
        format!("{}", [$(format!("performance.clearMeasures('{}')", $name)),*].join("; "))
    };
}

#[macro_export]
macro_rules! begin_measure {
    ($name:expr) => {
        #[cfg(all(feature = "profile-macros", target_arch = "wasm32"))]
        {
            use $crate::{begin_mark_name, mark};
            mark!(begin_mark_name!($name));
        }
    };
    ($name:expr, $clear_marks:expr) => {
        #[cfg(all(feature = "profile-macros", target_arch = "wasm32"))]
        {
            use $crate::{begin_mark_name, clear_marks, end_mark_name, mark};
            if $clear_marks {
                clear_marks!(begin_mark_name!($name), end_mark_name($name));
            }
            mark!(begin_mark_name!($name));
        }
    };
}

#[macro_export]
macro_rules! end_measure {
    ($name:expr) => {
        #[cfg(all(feature = "profile-macros", target_arch = "wasm32"))]
        {
            use $crate::{end_mark_name, mark, measure_marks};
            mark!(end_mark_name!($name));
            measure_marks!($name);
        }
    };
    ($name:expr, $clear_marks:expr) => {
        #[cfg(all(feature = "profile-macros", target_arch = "wasm32"))]
        {
            use $crate::{begin_mark_name, clear_marks, end_mark_name, mark, measure_marks};
            mark!(end_mark_name!($name));
            measure_marks!($name);
            if $clear_marks {
                clear_marks!(begin_mark_name!($name), end_mark_name($name));
            }
        }
    };
}

// We need to reexport the macro to make it public.
#[allow(unused_imports)]
pub use clear_marks;

#[allow(unused_imports)]
pub use clear_measures;

#[allow(unused_imports)]
pub use mark;

#[allow(unused_imports)]
pub use measure;

#[allow(unused_imports)]
pub use begin_measure;

#[allow(unused_imports)]
pub use end_measure;
