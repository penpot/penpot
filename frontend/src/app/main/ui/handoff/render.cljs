;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.handoff.render
  "The main container for a frame in handoff mode"
  (:require
   [rumext.alpha :as mf]
   [app.util.object :as obj]
   [app.util.dom :as dom]
   [app.common.data :as d]
   [app.common.pages :as cp]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as geom]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.data.viewer :as dv]
   [app.main.ui.shapes.filters :as filters]
   [app.main.ui.shapes.circle :as circle]
   [app.main.ui.shapes.frame :as frame]
   [app.main.ui.shapes.group :as group]
   [app.main.ui.shapes.svg-raw :as svg-raw]
   [app.main.ui.shapes.image :as image]
   [app.main.ui.shapes.path :as path]
   [app.main.ui.shapes.rect :as rect]
   [app.main.ui.shapes.text :as text]
   [app.main.ui.handoff.selection-feedback :refer [selection-feedback]]
   [app.main.ui.shapes.shape :refer [shape-container]]))

(declare shape-container-factory)

(defn handle-hover-shape [{:keys [type id]} hover?]
  #(when-not (#{:group :frame} type)
     (do
       (dom/prevent-default %)
       (dom/stop-propagation %)
       (st/emit! (dv/hover-shape id hover?)))))

(defn select-shape [{:keys [type id]}]
  (fn [event]
    (when-not (#{:group :frame} type)
      (do
        (dom/stop-propagation event)
        (dom/prevent-default event)
        (cond
          (.-shiftKey event)
          (st/emit! (dv/toggle-selection id))

          :else
          (st/emit! (dv/select-shape id)))))))

(defn shape-wrapper-factory
  [component]
  (mf/fnc shape-wrapper
    {::mf/wrap-props false}
    [props]
    (let [shape (unchecked-get props "shape")
          childs (unchecked-get props "childs")
          frame  (unchecked-get props "frame")]

      (if (and (= :svg-raw (:type shape))
               (not= :svg (get-in shape [:content :tag])))
        [:& component {:shape shape
                       :frame frame
                       :childs childs}]
        [:> shape-container {:shape shape
                             :on-mouse-enter (handle-hover-shape shape true)
                             :on-mouse-leave (handle-hover-shape shape false)
                             :on-click (select-shape shape)}
         [:& component {:shape shape
                        :frame frame
                        :childs childs
                        :is-child-selected? true}]]))))

(defn frame-container-factory
  [objects]
  (let [shape-container (shape-container-factory objects)
        frame-shape     (frame/frame-shape shape-container)
        frame-wrapper   (shape-wrapper-factory frame-shape)]
    (mf/fnc frame-container
      {::mf/wrap-props false}
      [props]
      (let [shape (unchecked-get props "shape")
            childs (mapv #(get objects %) (:shapes shape))
            shape  (geom/transform-shape shape)

            props (-> (obj/new)
                      (obj/merge! props)
                      (obj/merge! #js {:shape shape
                                       :childs childs}))]
        [:> frame-wrapper props]))))

(defn group-container-factory
  [objects]
  (let [shape-container (shape-container-factory objects)
        group-shape     (group/group-shape shape-container)
        group-wrapper   (shape-wrapper-factory group-shape)]
    (mf/fnc group-container
      {::mf/wrap-props false}
      [props]
      (let [shape  (unchecked-get props "shape")
            childs (mapv #(get objects %) (:shapes shape))
            props (-> (obj/new)
                      (obj/merge! props)
                      (obj/merge! #js {:childs childs}))]
        [:> group-wrapper props]))))

(defn svg-raw-container-factory
  [objects]
  (let [shape-container (shape-container-factory objects)
        svg-raw-shape     (svg-raw/svg-raw-shape shape-container)
        svg-raw-wrapper   (shape-wrapper-factory svg-raw-shape)]
    (mf/fnc group-container
      {::mf/wrap-props false}
      [props]
      (let [shape  (unchecked-get props "shape")
            childs (mapv #(get objects %) (:shapes shape))
            props (-> (obj/new)
                      (obj/merge! props)
                      (obj/merge! #js {:childs childs}))]
        [:> svg-raw-wrapper props]))))

(defn shape-container-factory
  [objects show-interactions?]
  (let [path-wrapper   (shape-wrapper-factory path/path-shape)
        text-wrapper   (shape-wrapper-factory text/text-shape)
        rect-wrapper   (shape-wrapper-factory rect/rect-shape)
        image-wrapper  (shape-wrapper-factory image/image-shape)
        circle-wrapper (shape-wrapper-factory circle/circle-shape)]
    (mf/fnc shape-container
      {::mf/wrap-props false}
      [props]
      (let [shape (unchecked-get props "shape")
            frame (unchecked-get props "frame")
            group-container (mf/use-memo
                             (mf/deps objects)
                             #(group-container-factory objects))
            svg-raw-container (mf/use-memo
                               (mf/deps objects)
                               #(svg-raw-container-factory objects))]
        (when (and shape (not (:hidden shape)))
          (let [shape (-> (geom/transform-shape shape)
                          (geom/translate-to-frame frame))
                opts #js {:shape shape
                          :frame frame}]
            (case (:type shape)
              :text    [:> text-wrapper opts]
              :rect    [:> rect-wrapper opts]
              :path    [:> path-wrapper opts]
              :image   [:> image-wrapper opts]
              :circle  [:> circle-wrapper opts]
              :group   [:> group-container opts]
              :svg-raw [:> svg-raw-container opts])))))))

(defn adjust-frame-position [frame-id objects]
  (let [frame        (get objects frame-id)
        modifier     (-> (gpt/point (:x frame) (:y frame))
                         (gpt/negate)
                         (gmt/translate-matrix))

        update-fn    #(assoc-in %1 [%2 :modifiers :displacement] modifier)
        modifier-ids (d/concat [frame-id] (cp/get-children frame-id objects))]
    (reduce update-fn objects modifier-ids)))

(defn make-vbox [frame]
  (str "0 0 " (:width frame 0) " " (:height frame 0)))

(mf/defc render-frame-svg
  {::mf/wrap [mf/memo]}
  [{:keys [objects frame-id zoom] :or {zoom 1} :as props}]

  (let [objects      (adjust-frame-position frame-id objects)
        frame        (get objects frame-id)
        width        (* (:width frame) zoom)
        height       (* (:height frame) zoom)
        vbox         (make-vbox frame)
        render-frame (mf/use-memo
                      (mf/deps objects)
                      #(frame-container-factory objects))]

    [:svg {:id "svg-frame"
           :view-box vbox
           :width width
           :height height
           :version "1.1"
           :xmlnsXlink "http://www.w3.org/1999/xlink"
           :xmlns "http://www.w3.org/2000/svg"}

     [:& render-frame {:shape frame
                       :view-box vbox}]

     [:& selection-feedback {:frame frame}]]))

