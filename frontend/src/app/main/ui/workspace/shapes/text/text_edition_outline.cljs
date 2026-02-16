;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.shapes.text.text-edition-outline
  (:require
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.texts :as dwt]
   [app.main.features :as features]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.render-wasm.api :as wasm.api]
   [rumext.v2 :as mf]))

(mf/defc text-edition-outline
  [{:keys [shape zoom modifiers]}]
  (if (features/active-feature? @st/state "render-wasm/v1")
    (let [selrect-transform (mf/deref refs/workspace-selrect)
          [selrect transform] (dsh/get-selrect selrect-transform shape)

          [sr-width sr-height]
          (if (or (mth/close? (:width selrect) 0.01) (mth/close? (:height selrect) 0.01))
            (let [{:keys [width height]} (wasm.api/get-text-dimensions (:id shape))]
              [width height])
            [(:width selrect) (:height selrect)])]
      [:rect.main.viewport-selrect
       {:x (:x selrect)
        :y (:y selrect)
        :width sr-width
        :height sr-height
        :transform transform
        :style {:stroke "var(--color-accent-tertiary)"
                :stroke-width (/ 1 zoom)
                :fill "none"}}])

    (let [modifiers (get-in modifiers [(:id shape) :modifiers])

          text-modifier-ref
          (mf/use-memo (mf/deps (:id shape)) #(refs/workspace-text-modifier-by-id (:id shape)))

          text-modifier
          (mf/deref text-modifier-ref)

          shape (cond-> shape
                  (some? modifiers)
                  (gsh/transform-shape modifiers)

                  (some? text-modifier)
                  (dwt/apply-text-modifier text-modifier))

          transform (gsh/transform-str shape)
          {:keys [x y width height]} shape]

      [:rect.main.viewport-selrect
       {:x x
        :y y
        :width width
        :height height
        :transform transform
        :style {:stroke "var(--color-accent-tertiary)"
                :stroke-width (/ 1 zoom)
                :fill "none"}}])))
