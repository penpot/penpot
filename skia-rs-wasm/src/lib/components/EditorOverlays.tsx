import { LayersPanel } from './LayersPanel/LayersPanel'
import { RightSidePanel } from './RightSidePanel/RightSidePanel'
import { ShapeToolbar } from './ShapeToolbar'

export function EditorOverlays() {
  return (
    <>
      <LayersPanel />
      <RightSidePanel />
      <ShapeToolbar />
    </>
  )
}
