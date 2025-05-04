use skia_safe as skia;

use crate::mem::SerializableResult;
use crate::utils::{uuid_from_u32_quartet, uuid_to_u32_quartet};
use crate::uuid::Uuid;
use skia::Matrix;

#[derive(PartialEq, Debug, Clone)]
pub enum Modifier {
    Transform(TransformEntry),
    Reflow(Uuid),
}

impl Modifier {
    pub fn transform(id: Uuid, transform: Matrix) -> Self {
        Modifier::Transform(TransformEntry::new(id, transform))
    }
    pub fn parent(id: Uuid, transform: Matrix) -> Self {
        Modifier::Transform(TransformEntry::parent(id, transform))
    }
    pub fn reflow(id: Uuid) -> Self {
        Modifier::Reflow(id)
    }
}

#[derive(PartialEq, Debug, Clone)]
#[repr(C)]
pub struct TransformEntry {
    pub id: Uuid,
    pub transform: Matrix,
    pub propagate: bool,
}

impl TransformEntry {
    pub fn new(id: Uuid, transform: Matrix) -> Self {
        TransformEntry {
            id,
            transform,
            propagate: true,
        }
    }
    pub fn parent(id: Uuid, transform: Matrix) -> Self {
        TransformEntry {
            id,
            transform,
            propagate: false,
        }
    }
}

impl SerializableResult for TransformEntry {
    type BytesType = [u8; 40];

    fn from_bytes(bytes: Self::BytesType) -> Self {
        let id = uuid_from_u32_quartet(
            u32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]),
            u32::from_le_bytes([bytes[4], bytes[5], bytes[6], bytes[7]]),
            u32::from_le_bytes([bytes[8], bytes[9], bytes[10], bytes[11]]),
            u32::from_le_bytes([bytes[12], bytes[13], bytes[14], bytes[15]]),
        );

        let transform = Matrix::new_all(
            f32::from_le_bytes([bytes[16], bytes[17], bytes[18], bytes[19]]),
            f32::from_le_bytes([bytes[24], bytes[25], bytes[26], bytes[27]]),
            f32::from_le_bytes([bytes[32], bytes[33], bytes[34], bytes[35]]),
            f32::from_le_bytes([bytes[20], bytes[21], bytes[22], bytes[23]]),
            f32::from_le_bytes([bytes[28], bytes[29], bytes[30], bytes[31]]),
            f32::from_le_bytes([bytes[36], bytes[37], bytes[38], bytes[39]]),
            0.0,
            0.0,
            1.0,
        );
        TransformEntry::new(id, transform)
    }

    fn as_bytes(&self) -> Self::BytesType {
        let mut result: Self::BytesType = [0; 40];
        let (a, b, c, d) = uuid_to_u32_quartet(&self.id);
        result[0..4].clone_from_slice(&a.to_le_bytes());
        result[4..8].clone_from_slice(&b.to_le_bytes());
        result[8..12].clone_from_slice(&c.to_le_bytes());
        result[12..16].clone_from_slice(&d.to_le_bytes());

        result[16..20].clone_from_slice(&self.transform[0].to_le_bytes());
        result[20..24].clone_from_slice(&self.transform[3].to_le_bytes());
        result[24..28].clone_from_slice(&self.transform[1].to_le_bytes());
        result[28..32].clone_from_slice(&self.transform[4].to_le_bytes());
        result[32..36].clone_from_slice(&self.transform[2].to_le_bytes());
        result[36..40].clone_from_slice(&self.transform[5].to_le_bytes());

        result
    }

    // The generic trait doesn't know the size of the array. This is why the
    // clone needs to be here even if it could be generic.
    fn clone_to_slice(&self, slice: &mut [u8]) {
        slice.clone_from_slice(&self.as_bytes());
    }
}

#[derive(PartialEq, Debug, Clone, Copy)]
pub enum StructureEntryType {
    RemoveChild,
    AddChild,
}

impl StructureEntryType {
    pub fn from_u32(value: u32) -> Self {
        match value {
            1 => Self::RemoveChild,
            2 => Self::AddChild,
            _ => unreachable!(),
        }
    }
}

#[derive(PartialEq, Debug, Clone)]
#[repr(C)]
pub struct StructureEntry {
    pub entry_type: StructureEntryType,
    pub index: u32,
    pub parent: Uuid,
    pub id: Uuid,
}

impl StructureEntry {
    pub fn new(entry_type: StructureEntryType, index: u32, parent: Uuid, id: Uuid) -> Self {
        StructureEntry {
            entry_type,
            index,
            parent,
            id,
        }
    }

    pub fn from_bytes(bytes: [u8; 40]) -> Self {
        let entry_type = StructureEntryType::from_u32(u32::from_le_bytes([
            bytes[0], bytes[1], bytes[2], bytes[3],
        ]));

        let index = u32::from_le_bytes([bytes[4], bytes[5], bytes[6], bytes[7]]);

        let parent = uuid_from_u32_quartet(
            u32::from_le_bytes([bytes[8], bytes[9], bytes[10], bytes[11]]),
            u32::from_le_bytes([bytes[12], bytes[13], bytes[14], bytes[15]]),
            u32::from_le_bytes([bytes[16], bytes[17], bytes[18], bytes[19]]),
            u32::from_le_bytes([bytes[20], bytes[21], bytes[22], bytes[23]]),
        );

        let id = uuid_from_u32_quartet(
            u32::from_le_bytes([bytes[24], bytes[25], bytes[26], bytes[27]]),
            u32::from_le_bytes([bytes[28], bytes[29], bytes[30], bytes[31]]),
            u32::from_le_bytes([bytes[32], bytes[33], bytes[34], bytes[35]]),
            u32::from_le_bytes([bytes[36], bytes[37], bytes[38], bytes[39]]),
        );

        StructureEntry::new(entry_type, index, parent, id)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_serialization() {
        let entry = TransformEntry::new(
            Uuid::new_v4(),
            Matrix::new_all(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 0.0, 0.0, 1.0),
        );

        let bytes = entry.as_bytes();

        assert_eq!(entry, TransformEntry::from_bytes(bytes));
    }
}
