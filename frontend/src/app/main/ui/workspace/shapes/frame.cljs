;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.shapes.frame
  (:require
   [app.common.geom.shapes :as gsh]
   [app.common.pages :as cp]
   [app.main.ui.shapes.frame :as frame]
   [app.main.ui.shapes.shape :refer [shape-container]]
   [app.main.ui.shapes.text.fontfaces :as ff]
   [app.util.object :as obj]
   [app.util.timers :as ts]
   [beicon.core :as rx]
   [rumext.alpha :as mf]))

(defn check-frame-props
  "Checks for changes in the props of a frame"
  [new-props old-props]
  (let [new-shape (unchecked-get new-props "shape")
        old-shape (unchecked-get old-props "shape")

        new-thumbnail? (unchecked-get new-props "thumbnail?")
        old-thumbnail? (unchecked-get old-props "thumbnail?")

        new-objects (unchecked-get new-props "objects")
        old-objects (unchecked-get old-props "objects")

        new-children (->> new-shape :shapes (mapv #(get new-objects %)))
        old-children (->> old-shape :shapes (mapv #(get old-objects %)))]
    (and (= new-shape old-shape)
         (= new-thumbnail? old-thumbnail?)
         (= new-children old-children))))

(mf/defc thumbnail
  {::mf/wrap-props false}
  [props]
  (let [shape (obj/get props "shape")]
    (when (:thumbnail shape)
      [:image.frame-thumbnail
       {:id (str "thumbnail-" (:id shape))
        :xlinkHref (:thumbnail shape)
        :x (:x shape)
        :y (:y shape)
        :width (:width shape)
        :height (:height shape)
        ;; DEBUG
        ;; :style {:filter "sepia(1)"}
        }])))

;; This custom deferred don't defer rendering when ghost rendering is
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
            thumbnail?  (unchecked-get props "thumbnail?")

            shape        (gsh/transform-shape shape)
            children     (mapv #(get objects %) (:shapes shape))

            all-children (cp/get-children-objects (:id shape) objects)

            rendered?   (mf/use-state false)

            show-thumbnail? (and thumbnail? (some? (:thumbnail shape)))

            on-dom
            (mf/use-callback
             (fn [node]
               (ts/schedule-on-idle #(reset! rendered? (some? node)))))]

        (when (some? shape)
          [:g.frame-wrapper {:display (when (:hidden shape) "none")}

           (when-not show-thumbnail?
             [:> shape-container {:shape shape :ref on-dom}
              [:& ff/fontfaces-style {:shapes all-children}]
              [:& frame-shape {:shape shape
                               :childs children}]])

           (when (or (not @rendered?) show-thumbnail?)
             [:& thumbnail {:shape shape}])])))))

