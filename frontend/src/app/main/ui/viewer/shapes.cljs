;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.viewer.shapes
  "The main container for a frame in viewer mode"
  (:require
   [rumext.alpha :as mf]
   [app.common.data :as d]
   [app.common.pages :as cp]
   [app.common.pages-helpers :as cph]
   [app.main.data.viewer :as dv]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.shapes.filters :as filters]
   [app.main.ui.shapes.circle :as circle]
   [app.main.ui.shapes.frame :as frame]
   [app.main.ui.shapes.group :as group]
   [app.main.ui.shapes.image :as image]
   [app.main.ui.shapes.path :as path]
   [app.main.ui.shapes.rect :as rect]
   [app.main.ui.shapes.text :as text]
   [app.util.object :as obj]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as geom]
   [app.main.ui.shapes.shape :refer [shape-container]]))

(defn on-mouse-down
  [event {:keys [interactions] :as shape}]
  (let [interaction (first (filter #(= (:event-type %) :click) interactions))]
    (case (:action-type interaction)
      :navigate
      (let [frame-id (:destination interaction)]
        (st/emit! (dv/go-to-frame frame-id)))

      nil)))

(defn generic-wrapper-factory
  "Wrap some svg shape and add interaction controls"
  [component show-interactions?]
  (mf/fnc generic-wrapper
    {::mf/wrap-props false}
    [props]
    (let [shape (unchecked-get props "shape")
          {:keys [x y width height]} (:selrect shape)

          childs (unchecked-get props "childs")
          frame  (unchecked-get props "frame")

          on-mouse-down (mf/use-callback
                         (mf/deps shape)
                         #(on-mouse-down % shape))]

      [:> shape-container {:shape shape
                           :on-mouse-down on-mouse-down
                           :cursor (when (seq (:interactions shape)) "pointer")}
       [:& component {:shape shape
                      :frame frame
                      :childs childs
                      :is-child-selected? true}]
       (when (and (:interactions shape) show-interactions?)
         [:rect {:x (- x 1)
                 :y (- y 1)
                 :width (+ width 2)
                 :height (+ height 2)
                 :fill "#31EFB8"
                 :stroke "#31EFB8"
                 :stroke-width 1
                 :fill-opacity 0.2}])])))

(defn frame-wrapper
  [shape-container show-interactions?]
  (generic-wrapper-factory (frame/frame-shape shape-container) show-interactions?))

(defn group-wrapper
  [shape-container show-interactions?]
  (generic-wrapper-factory (group/group-shape shape-container) show-interactions?))

(defn rect-wrapper
  [show-interactions?]
  (generic-wrapper-factory rect/rect-shape show-interactions?))

(defn image-wrapper
  [show-interactions?]
  (generic-wrapper-factory image/image-shape show-interactions?))

(defn path-wrapper
  [show-interactions?]
  (generic-wrapper-factory path/path-shape show-interactions?))

(defn text-wrapper
  [show-interactions?]
  (generic-wrapper-factory text/text-shape show-interactions?))

(defn circle-wrapper
  [show-interactions?]
  (generic-wrapper-factory circle/circle-shape show-interactions?))

(declare shape-container-factory)

(defn frame-container-factory
  [objects show-interactions?]
  (let [shape-container (shape-container-factory objects show-interactions?)
        frame-wrapper   (frame-wrapper shape-container show-interactions?)]
    (mf/fnc frame-container
      {::mf/wrap-props false}
      [props]
      (let [shape (unchecked-get props "shape")
            childs (mapv #(get objects %) (:shapes shape))
            shape  (geom/transform-shape shape)
            props  (obj/merge! #js {} props
                               #js {:shape shape
                                    :childs childs
                                    :show-interactions? show-interactions?})]
        [:> frame-wrapper props]))))

(defn group-container-factory
  [objects show-interactions?]
  (let [shape-container (shape-container-factory objects show-interactions?)
        group-wrapper (group-wrapper shape-container show-interactions?)]
    (mf/fnc group-container
      {::mf/wrap-props false}
      [props]
      (let [shape  (unchecked-get props "shape")
            childs (mapv #(get objects %) (:shapes shape))
            props  (obj/merge! #js {} props
                               #js {:childs childs
                                    :show-interactions? show-interactions?})]
        [:> group-wrapper props]))))

(defn shape-container-factory
  [objects show-interactions?]
  (let [path-wrapper   (path-wrapper show-interactions?)
        text-wrapper   (text-wrapper show-interactions?)
        rect-wrapper   (rect-wrapper show-interactions?)
        image-wrapper  (image-wrapper show-interactions?)
        circle-wrapper (circle-wrapper show-interactions?)]
    (mf/fnc shape-container
      {::mf/wrap-props false}
      [props]
      (let [group-container (mf/use-memo
                              (mf/deps objects)
                              #(group-container-factory objects show-interactions?))
            shape (unchecked-get props "shape")
            frame (unchecked-get props "frame")]
        (when (and shape (not (:hidden shape)))
          (let [shape (-> (geom/transform-shape shape)
                          (geom/translate-to-frame frame))
                opts #js {:shape shape}]
            (case (:type shape)
              :frame  [:g.empty]
              :text   [:> text-wrapper opts]
              :rect   [:> rect-wrapper opts]
              :path   [:> path-wrapper opts]
              :image  [:> image-wrapper opts]
              :circle [:> circle-wrapper opts]
              :group  [:> group-container
                       {:shape shape
                        :frame frame}])))))))

(mf/defc frame-svg
  {::mf/wrap [mf/memo]}
  [{:keys [objects frame zoom show-interactions?] :or {zoom 1} :as props}]
  (let [modifier (-> (gpt/point (:x frame) (:y frame))
                     (gpt/negate)
                     (gmt/translate-matrix))

        update-fn    #(assoc-in %1 [%2 :modifiers :displacement] modifier)

        frame-id     (:id frame)
        modifier-ids (d/concat [frame-id] (cph/get-children frame-id objects))
        objects      (reduce update-fn objects modifier-ids)
        frame        (assoc-in frame [:modifiers :displacement] modifier)

        width        (* (:width frame) zoom)
        height       (* (:height frame) zoom)
        vbox         (str "0 0 " (:width frame 0)
                          " "    (:height frame 0))
        wrapper      (mf/use-memo
                      (mf/deps objects)
                      #(frame-container-factory objects show-interactions?))]

    [:svg {:view-box vbox
           :width width
           :height height
           :version "1.1"
           :xmlnsXlink "http://www.w3.org/1999/xlink"
           :xmlns "http://www.w3.org/2000/svg"}
     [:& wrapper {:shape frame
                  :show-interactions? show-interactions?
                  :view-box vbox}]]))

