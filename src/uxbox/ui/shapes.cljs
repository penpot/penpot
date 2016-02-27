(ns uxbox.ui.shapes
  "A ui related implementation for uxbox.shapes ns."
  (:require [uxbox.ui.shapes.core :as usc]
            [uxbox.ui.shapes.text]
            [uxbox.ui.shapes.icon]
            [uxbox.ui.shapes.rect]
            [uxbox.ui.shapes.group]
            [uxbox.ui.shapes.line]
            [uxbox.ui.shapes.circle]))

(def ^:const shape usc/shape)
