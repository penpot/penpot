use uuid::Uuid;

pub fn uuid_from_u32_quartet(a: u32, b: u32, c: u32, d: u32) -> Uuid {
    let hi: u64 = ((a as u64) << 32) | b as u64;
    let lo: u64 = ((c as u64) << 32) | d as u64;
    Uuid::from_u64_pair(hi, lo)
}

pub fn uuid_to_u32_quartet(id: &Uuid) -> (u32, u32, u32, u32) {
    let (hi, lo) = id.as_u64_pair();
    let hihi32 = (hi >> 32) as u32;
    let hilo32 = hi as u32;
    let lohi32 = (lo >> 32) as u32;
    let lolo32 = lo as u32;
    (hihi32, hilo32, lohi32, lolo32)
}
