import { useContext } from 'react'
import { FillEditorContext } from './fill-editor-context'

export function useFillEditor() {
  return useContext(FillEditorContext)
}
