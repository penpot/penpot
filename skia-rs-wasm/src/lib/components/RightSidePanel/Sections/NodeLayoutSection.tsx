import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
export interface NodeLayoutSectionProps {
  layoutFieldsDisabled: boolean
  xDisplay: number
  yDisplay: number
  widthDisplay: number
  heightDisplay: number
  rotationDisplay: number
  onXChange: (v: number) => void
  onYChange: (v: number) => void
  onWidthChange: (v: number) => void
  onHeightChange: (v: number) => void
  onRotationChange: (v: number) => void
  onLayoutCommit: () => void
}

export function NodeLayoutSection({
  layoutFieldsDisabled,
  xDisplay,
  yDisplay,
  widthDisplay,
  heightDisplay,
  rotationDisplay,
  onXChange,
  onYChange,
  onWidthChange,
  onHeightChange,
  onRotationChange,
  onLayoutCommit,
}: NodeLayoutSectionProps) {
  return (
    <>
      <div className="grid grid-cols-2 gap-2">
        <div className="space-y-1">
          <Label htmlFor="rsp-x">X</Label>
          <Input
            id="rsp-x"
            type="number"
            disabled={layoutFieldsDisabled}
            value={Number.isFinite(xDisplay) ? xDisplay : 0}
            onChange={(e) => onXChange(parseFloat(e.target.value) || 0)}
            onBlur={() => void onLayoutCommit()}
          />
        </div>
        <div className="space-y-1">
          <Label htmlFor="rsp-y">Y</Label>
          <Input
            id="rsp-y"
            type="number"
            disabled={layoutFieldsDisabled}
            value={Number.isFinite(yDisplay) ? yDisplay : 0}
            onChange={(e) => onYChange(parseFloat(e.target.value) || 0)}
            onBlur={() => void onLayoutCommit()}
          />
        </div>
        <div className="space-y-1">
          <Label htmlFor="rsp-w">W</Label>
          <Input
            id="rsp-w"
            type="number"
            disabled={layoutFieldsDisabled}
            value={Number.isFinite(widthDisplay) ? widthDisplay : 0}
            onChange={(e) => onWidthChange(Math.max(1, parseFloat(e.target.value) || 1))}
            onBlur={() => void onLayoutCommit()}
          />
        </div>
        <div className="space-y-1">
          <Label htmlFor="rsp-h">H</Label>
          <Input
            id="rsp-h"
            type="number"
            disabled={layoutFieldsDisabled}
            value={Number.isFinite(heightDisplay) ? heightDisplay : 0}
            onChange={(e) => onHeightChange(Math.max(1, parseFloat(e.target.value) || 1))}
            onBlur={() => void onLayoutCommit()}
          />
        </div>
      </div>

      <div className="space-y-2">
        <Label htmlFor="rsp-rot">Rotation (deg)</Label>
        <Input
          id="rsp-rot"
          type="number"
          disabled={layoutFieldsDisabled}
          value={Number.isFinite(rotationDisplay) ? rotationDisplay : 0}
          onChange={(e) => onRotationChange(parseFloat(e.target.value) || 0)}
          onBlur={() => void onLayoutCommit()}
        />
      </div>
    </>
  )
}
