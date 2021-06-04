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
   [app.main.data.workspace.changes :as dch]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.context :as muc]
   [app.main.ui.shapes.frame :as frame]
   [app.main.ui.shapes.shape :refer [shape-container]]
   [app.main.ui.shapes.text.embed :as ste]
   [app.util.dom :as dom]
   [app.util.keyboard :as kbd]
   [app.util.object :as obj]
   [app.util.timers :as ts]
   [beicon.core :as rx]
   [okulary.core :as l]
   [rumext.alpha :as mf]))

(def obs-config
  #js {:attributes true
       :childList true
       :subtree true
       :characterData true})

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
      (let [shape        (unchecked-get props "shape")
            objects      (unchecked-get props "objects")
            thumbnail?   (unchecked-get props "thumbnail?")

            edition      (mf/deref refs/selected-edition)
            embed-fonts? (mf/use-ctx muc/embed-ctx)

            shape        (gsh/transform-shape shape)
            children     (mapv #(get objects %) (:shapes shape))
            text-childs  (->> (vals objects)
                              (filterv #(and (= :text (:type %))
                                             (= (:id shape) (:frame-id %)))))

            rendered?    (mf/use-state false)

            show-thumbnail? (and thumbnail? (some? (:thumbnail shape)))

            on-dom
            (mf/use-callback
             (fn [node]
               (ts/schedule-on-idle #(reset! rendered? (some? node)))))]

        (when (and shape (not (:hidden shape)))
          [:g.frame-wrapper {:display (when (:hidden shape) "none")}

           (when-not show-thumbnail?
             [:> shape-container {:shape shape
                                  :ref on-dom}

              (when embed-fonts?
                [:& ste/embed-fontfaces-style {:shapes text-childs}])

              [:& frame-shape {:shape shape
                               :childs children}]])

           (when (or (not @rendered?) show-thumbnail?)
             [:& thumbnail {:shape shape}])])))))

