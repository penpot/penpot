use crate::mem::SerializableResult;
use crate::utils::uuid_from_u32_quartet;
use crate::utils::uuid_to_u32_quartet;
use std::fmt;
use std::ops::{Deref, DerefMut};
use uuid::Uuid as ExternalUuid;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct Uuid(ExternalUuid);

impl Deref for Uuid {
    type Target = ExternalUuid;

    fn deref(&self) -> &Self::Target {
        &self.0
    }
}

impl DerefMut for Uuid {
    fn deref_mut(&mut self) -> &mut Self::Target {
        &mut self.0
    }
}

impl Uuid {
    pub fn nil() -> Self {
        Self(ExternalUuid::nil())
    }

    pub fn from_u64_pair(high: u64, low: u64) -> Self {
        Self(ExternalUuid::from_u64_pair(high, low))
    }

    #[cfg(test)]
    pub fn new_v4() -> Self {
        Self(ExternalUuid::new_v4())
    }
}

impl From<ExternalUuid> for Uuid {
    fn from(uuid: ExternalUuid) -> Self {
        Self(uuid)
    }
}

impl fmt::Display for Uuid {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        write!(f, "{}", self.0)
    }
}

impl SerializableResult for Uuid {
    type BytesType = [u8; 16];

    fn from_bytes(bytes: Self::BytesType) -> Self {
        Self(*uuid_from_u32_quartet(
            u32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]),
            u32::from_le_bytes([bytes[4], bytes[5], bytes[6], bytes[7]]),
            u32::from_le_bytes([bytes[8], bytes[9], bytes[10], bytes[11]]),
            u32::from_le_bytes([bytes[12], bytes[13], bytes[14], bytes[15]]),
        ))
    }

    fn as_bytes(&self) -> Self::BytesType {
        let mut result: Self::BytesType = [0; 16];
        let (a, b, c, d) = uuid_to_u32_quartet(self);
        result[0..4].clone_from_slice(&a.to_le_bytes());
        result[4..8].clone_from_slice(&b.to_le_bytes());
        result[8..12].clone_from_slice(&c.to_le_bytes());
        result[12..16].clone_from_slice(&d.to_le_bytes());

        result
    }

    // The generic trait doesn't know the size of the array. This is why the
    // clone needs to be here even if it could be generic.
    fn clone_to_slice(&self, slice: &mut [u8]) {
        slice.clone_from_slice(&self.as_bytes());
    }
}
