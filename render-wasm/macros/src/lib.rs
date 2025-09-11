use std::collections::HashMap;
use std::fs;
use std::io::Write;
use std::path::Path;
use std::sync;

use heck::{ToKebabCase, ToPascalCase};
use proc_macro::TokenStream;

type Result<T> = std::result::Result<T, String>;

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
