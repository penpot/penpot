;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.workspace.shapes.svg-raw
  (:require
   [app.main.refs :as refs]
   [app.main.ui.shapes.svg-raw :as svg-raw]
   [app.main.ui.shapes.shape :refer [shape-container]]
   [app.main.ui.workspace.effects :as we]
   [rumext.alpha :as mf]
   [app.common.geom.shapes :as gsh]
   [app.main.ui.context :as muc]))

;; This is a list of svg tags that can be grouped in shape-container
;; this allows them to have gradients, shadows and masks
(def svg-elements #{:svg :circle :ellipse :image :line :path :polygon :polyline :rect :symbol :text :textPath})

(defn svg-raw-wrapper-factory
  [shape-wrapper]
  (let [svg-raw-shape (svg-raw/svg-raw-shape shape-wrapper)]
    (mf/fnc svg-raw-wrapper
      {::mf/wrap [#(mf/memo' % (mf/check-props ["shape" "frame"]))]
       ::mf/wrap-props false}
      [props]
      (let [shape (unchecked-get props "shape")
            frame (unchecked-get props "frame")

            {:keys [id x y width height]} shape

            childs-ref (mf/use-memo (mf/deps shape) #(refs/objects-by-id (:shapes shape)))
            childs     (mf/deref childs-ref)

            {:keys [id x y width height]} shape
            transform (gsh/transform-matrix shape)

            tag (get-in shape [:content :tag])

            handle-mouse-down   (we/use-mouse-down shape)
            handle-context-menu (we/use-context-menu shape)
            handle-pointer-enter (we/use-pointer-enter shape)
            handle-pointer-leave (we/use-pointer-leave shape)
            handle-double-click  (we/use-double-click shape)

            def-ctx? (mf/use-ctx muc/def-ctx)]

        (cond
          (and (contains? svg-elements tag) (not def-ctx?))
          [:> shape-container { :shape shape }
           [:& svg-raw-shape
            {:frame frame
             :shape shape
             :childs childs}]

           [:rect.actions
            {:x x
             :y y
             :transform transform
             :width width
             :height height
             :fill "transparent"
             :on-mouse-down handle-mouse-down
             :on-double-click handle-double-click
             :on-context-menu handle-context-menu
             :on-pointer-over handle-pointer-enter
             :on-pointer-out handle-pointer-leave}]]

          ;; We cannot wrap inside groups the shapes that go inside the defs tag
          ;; we use the context so we know when we should not render the container
          (= tag :defs)
          [:& (mf/provider muc/def-ctx) {:value true}
           [:& svg-raw-shape {:frame frame
                              :shape shape
                              :childs childs}]]

          :else
          [:& svg-raw-shape {:frame frame
                             :shape shape
                             :childs childs}])))))

