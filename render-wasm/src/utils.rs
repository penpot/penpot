use uuid::Uuid;

pub fn uuid_from_u32_quartet(a: u32, b: u32, c: u32, d: u32) -> Uuid {
    let hi: u64 = ((a as u64) << 32) | b as u64;
    let lo: u64 = ((c as u64) << 32) | d as u64;
    Uuid::from_u64_pair(hi, lo)
}
