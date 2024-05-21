const Type = {
  FULL: "full",
  PARTIAL: "partial",
};

export const LayoutType = {
  ...Type,
  isLayoutType(type) {
    return typeof type === "string" && Object.values(Type).includes(type)
  }
}

export default LayoutType;
