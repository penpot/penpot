/**
 * Indicates the direction of the selection.
 *
 * @readonly
 * @enum {number}
 */
export const SelectionDirection = {
  /** The anchorNode is behind the focusNode  */
  FORWARD: 1,
  /** The focusNode and the anchorNode are collapsed */
  NONE: 0,
  /** The focusNode is behind the anchorNode */
  BACKWARD: -1,
};

export default SelectionDirection;
