;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.shapes.frame
  (:require
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.main.data.workspace :as dw]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.context :as muc]
   [app.main.ui.shapes.frame :as frame]
   [app.main.ui.shapes.shape :refer [shape-container]]
   [app.main.ui.shapes.text.embed :as ste]
   [app.util.dom :as dom]
   [app.util.keyboard :as kbd]
   [app.util.timers :as ts]
   [beicon.core :as rx]
   [okulary.core :as l]
   [rumext.alpha :as mf]))

(defn make-is-moving-ref
  [id]
  (let [check-moving (fn [local]
                       (and (= :move (:transform local))
                            (contains? (:selected local) id)))]
    (l/derived check-moving refs/workspace-local)))

(defn check-props
  ([props] (check-props props =))
  ([props eqfn?]
   (fn [np op]
     (every? #(eqfn? (unchecked-get np %)
                     (unchecked-get op %))
             props))))

(defn check-frame-props
  "Checks for changes in the props of a frame"
  [new-props old-props]
  (let [new-shape (unchecked-get new-props "shape")
        old-shape (unchecked-get old-props "shape")

        new-objects (unchecked-get new-props "objects")
        old-objects (unchecked-get old-props "objects")

        new-children (->> new-shape :shapes (mapv #(get new-objects %)))
        old-children (->> old-shape :shapes (mapv #(get old-objects %)))]
    (and (= new-shape old-shape)
         (= new-children old-children))))

;; This custom deffered don't deffer rendering when ghost rendering is
;; used.
(defn custom-deferred
  [component]
  (mf/fnc deferred
    {::mf/wrap-props false}
    [props]
    (let [tmp (mf/useState false)
          ^boolean render? (aget tmp 0)
          ^js set-render (aget tmp 1)]
      (mf/use-layout-effect
       (fn []
         (let [sem (ts/schedule-on-idle #(set-render true))]
           #(rx/dispose! sem))))
      (when render? (mf/create-element component props)))))

(defn frame-wrapper-factory
  [shape-wrapper]
  (let [frame-shape (frame/frame-shape shape-wrapper)]
    (mf/fnc frame-wrapper
      {::mf/wrap [#(mf/memo' % check-frame-props) custom-deferred]
       ::mf/wrap-props false}
      [props]
      (let [shape       (unchecked-get props "shape")
            objects     (unchecked-get props "objects")
            edition     (mf/deref refs/selected-edition)
            embed-fonts? (mf/use-ctx muc/embed-ctx)

            shape       (gsh/transform-shape shape)
            children    (mapv #(get objects %) (:shapes shape))
            text-childs (->> objects
                             vals
                             (filterv #(and (= :text (:type %))
                                            (= (:id shape) (:frame-id %)))))

            ds-modifier (get-in shape [:modifiers :displacement])]

        (when (and shape (not (:hidden shape)))
          [:g.frame-wrapper {:display (when (:hidden shape) "none")}
           [:> shape-container {:shape shape}
            (when embed-fonts?
              [:& ste/embed-fontfaces-style {:shapes text-childs}])
            [:& frame-shape
             {:shape shape
              :childs children}]]])))))

