use std::collections::HashMap;
use std::fs;
use std::io::Write;
use std::path::Path;
use std::sync;

use heck::{ToKebabCase, ToPascalCase};
use proc_macro::TokenStream;
use quote::quote;
use syn::{parse_macro_input, Block, GenericArgument, ItemFn, ReturnType, Type};

type Result<T> = std::result::Result<T, String>;

/// Attribute macro for WASM-exported functions. The function **must** return
/// `std::result::Result<T, E>` where T is a C ABI type and E implements
/// `std::error::Error` and `Into<u8>`. The macro:
/// - Clears the error code at entry.
/// - Runs the body in `std::panic::catch_unwind`.
/// - Unwraps the Result: `Ok(x)` → return x; `Err(e)` → set error code in memory and panic
///   (so ClojureScript can catch the exception and read the code via `read_error_code`).
/// - On panic from the body: sets critical error code (0x02) and resumes unwind.
#[proc_macro_attribute]
pub fn wasm_error(_attr: TokenStream, item: TokenStream) -> TokenStream {
    let mut input = parse_macro_input!(item as ItemFn);
    let body = (*input.block).clone();

    let (attrs, boxed_ty) = match &input.sig.output {
        ReturnType::Type(attrs, boxed_ty) => (attrs, boxed_ty),
        ReturnType::Default => {
            return quote! {
                compile_error!(
                    "#[wasm_error] requires the function to return std::result::Result<T, E> where E: std::error::Error + Into<u8>"
                );
            }
            .into();
        }
    };

    let (inner_ty, error_ty) = match crate_error_result_inner_type(boxed_ty) {
        Some(t) => (t, quote!(crate::error::Error)),
        None => {
            return quote! {
                    compile_error!(
                        "#[wasm_error] requires the function to return crate::error::Result<T>. T must be a C ABI type (u32, u8, bool, (), etc.)"
                    );
                }
                .into();
        }
    };

    let block: Block = syn::parse2(quote! {
        {
            crate::mem::clear_error_code();
            let __wasm_err_result = std::panic::catch_unwind(|| -> std::result::Result<#inner_ty, #error_ty> {
                #body
            });
            match __wasm_err_result {
                Ok(__inner) => match __inner {
                    Ok(__val) => __val,
                    Err(__e) => {
                        let _: &dyn std::error::Error = &__e;
                        let __msg = __e.to_string();
                        crate::mem::set_error_code(__e.into());
                        panic!("WASM error: {}",__msg);
                    }
                },
                Err(__payload) => {
                    crate::mem::set_error_code(0x02); // critical, same as Error::Critical
                    std::panic::resume_unwind(__payload);
                }
            }
        }
    })
    .expect("block parse");

    input.sig.output = ReturnType::Type(attrs.clone(), Box::new(inner_ty.clone()));
    input.block = Box::new(block);
    quote! { #input }.into()
}

/// If the type is crate::error::Result<T> or a single-segment Result<T> (e.g. with
/// `use crate::error::Result`), returns Some(T). Otherwise None.
fn crate_error_result_inner_type(ty: &Type) -> Option<&Type> {
    let path = match ty {
        Type::Path(tp) => &tp.path,
        _ => return None,
    };
    let segs: Vec<_> = path.segments.iter().collect();
    let last = path.segments.last()?;
    if last.ident != "Result" {
        return None;
    }
    let args = match &last.arguments {
        syn::PathArguments::AngleBracketed(a) => &a.args,
        _ => return None,
    };
    if args.len() != 1 {
        return None;
    }
    // Accept crate::error::Result<T> or bare Result<T> (from use)
    let ok = segs.len() == 1
        || (segs.len() == 3 && segs[0].ident == "crate" && segs[1].ident == "error");
    if !ok {
        return None;
    }
    match &args[0] {
        GenericArgument::Type(t) => Some(t),
        _ => None,
    }
}

#[proc_macro_derive(ToJs)]
pub fn derive_to_cljs(input: TokenStream) -> TokenStream {
    let input = syn::parse_macro_input!(input as syn::DeriveInput);
    let enum_id = input.ident.to_string();
    let data_enum = match input.data {
        syn::Data::Enum(data_enum) => data_enum,
        _ => panic!("ToCljs can only be derived for enums"),
    };

    let raw_variants = data_enum
        .variants
        .to_owned()
        .into_iter()
        .collect::<Vec<_>>();

    let variants = parse_variants(&raw_variants).expect("Failed to parse variants");
    let js_code = generate_js_for_enum(&enum_id, &mut variants.into_iter().collect::<Vec<_>>());

    if let Err(e) = write_enum_to_temp_file(&js_code) {
        eprintln!("Error writing enum {} to file: {}", enum_id, e);
    }

    TokenStream::new() // we don't need to return any generated code
}

fn parse_variants(variants: &[syn::Variant]) -> Result<HashMap<String, u32>> {
    let mut res = HashMap::new();
    for variant in variants {
        let value_expr = variant
            .discriminant
            .clone()
            .ok_or(format!(
                "No discriminant found for variant {}",
                variant.ident
            ))?
            .1;
        let discriminant = parse_discriminant_value(value_expr)?;
        res.insert(variant.ident.to_string(), discriminant);
    }

    Ok(res)
}

fn parse_discriminant_value(value: syn::Expr) -> Result<u32> {
    match value {
        syn::Expr::Lit(syn::ExprLit {
            lit: syn::Lit::Int(int),
            ..
        }) => Ok(int.base10_digits().parse().unwrap()),
        _ => Err(format!("Invalid discriminant value")),
    }
}

fn generate_js_for_enum(id: &str, variants: &mut [(String, u32)]) -> String {
    variants.sort_by_key(|(_, discriminant)| *discriminant);

    let output_variants: String = variants
        .into_iter()
        .map(|(variant, discriminant)| {
            format!(r#"  "{}": {},"#, variant.to_kebab_case(), discriminant)
        })
        .collect::<Vec<String>>()
        .join("\n");

    format!(
        "export const {} = {{\n{}\n}};",
        id.to_pascal_case(),
        output_variants
    )
}

static INIT: sync::Once = sync::Once::new();

fn write_enum_to_temp_file(js_code: &str) -> std::io::Result<()> {
    let out_dir = std::env::var("OUT_DIR").expect("OUT_DIR environment variable is not set");
    let out_path = Path::new(&out_dir).join("render_wasm_shared.js");

    // clean the file the first time this function is called
    INIT.call_once(|| {
        fs::OpenOptions::new()
            .create(true)
            .write(true)
            .truncate(true)
            .open(&out_path)
            .expect("Failed to open output file");
    });

    let mut file = fs::OpenOptions::new().append(true).open(&out_path)?;
    writeln!(file, "{}\n", js_code)?;

    Ok(())
}
