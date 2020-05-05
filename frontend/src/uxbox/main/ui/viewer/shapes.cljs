;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.ui.viewer.shapes
  "The main container for a frame in viewer mode"
  (:require
   [rumext.alpha :as mf]
   [uxbox.common.pages :as cp]
   [uxbox.main.data.viewer :as dv]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.main.ui.shapes.circle :as circle]
   [uxbox.main.ui.shapes.frame :as frame]
   [uxbox.main.ui.shapes.group :as group]
   [uxbox.main.ui.shapes.icon :as icon]
   [uxbox.main.ui.shapes.image :as image]
   [uxbox.main.ui.shapes.path :as path]
   [uxbox.main.ui.shapes.rect :as rect]
   [uxbox.main.ui.shapes.text :as text]
   [uxbox.util.geom.matrix :as gmt]
   [uxbox.util.geom.point :as gpt]
   [uxbox.util.geom.shapes :as geom]))

;; TODO: reivisit show interactions ref
;; TODO: revisit refs/frames


;; --- Interaction actions (in viewer mode)

(defn on-mouse-down
  [event {:keys [interactions] :as shape}]
  (let [interaction (first (filter #(= (:action-type % :click)) interactions))]
    (case (:action-type interaction)
      :navigate
      (let [frame-id (:destination interaction)]
        (st/emit! (dv/go-to-frame frame-id)))
      nil)))

(declare shape-wrapper-factory)

(defn frame-wrapper-factory
  [objects]
  (let [shape-wrapper (shape-wrapper-factory objects)
        frame-shape   (frame/frame-shape shape-wrapper)]
    (mf/fnc frame-wrapper
      [{:keys [shape] :as props}]
      (let [childs (mapv #(get objects %) (:shapes shape))
            shape (geom/transform-shape shape)]
        [:& frame-shape {:shape shape :childs childs}]))))

(defn group-wrapper-factory
  [objects]
  (let [shape-wrapper (shape-wrapper-factory objects)
        group-shape   (group/group-shape shape-wrapper)]
    (mf/fnc group-wrapper
      [{:keys [shape frame] :as props}]
      (let [children (mapv #(get objects %) (:shapes shape))]
        [:& group-shape {:frame frame
                         :shape shape
                         :children children}]))))

(defn generic-wrapper-factory
  [component]
  (mf/fnc generic-wrapper
    {::mf/wrap-props false}
    [props]
    (let [{:keys [x y width height]
           :as shape} (->> (unchecked-get props "shape")
                           (geom/selection-rect-shape))
          show-interactions? (unchecked-get props "show-interactions?")
          on-mouse-down (mf/use-callback
                         (mf/deps shape)
                         #(on-mouse-down % shape))]

      [:g.shape {:on-mouse-down on-mouse-down
                 :cursor (when (:interactions shape) "pointer")}
       [:& component {:shape shape}]
       (when (and (:interactions shape) show-interactions?)
         [:rect {:x (- x 1)
                 :y (- y 1)
                 :width (+ width 2)
                 :height (+ height 2)
                 :fill "#31EFB8"
                 :stroke "#31EFB8"
                 :stroke-width 1
                 :fill-opacity 0.2}])])))

(def rect-wrapper (generic-wrapper-factory rect/rect-shape))
(def icon-wrapper (generic-wrapper-factory icon/icon-shape))
(def image-wrapper (generic-wrapper-factory image/image-shape))
(def path-wrapper (generic-wrapper-factory path/path-shape))
(def text-wrapper (generic-wrapper-factory text/text-shape))
(def circle-wrapper (generic-wrapper-factory circle/circle-shape))

(defn shape-wrapper-factory
  [objects]
  (mf/fnc shape-wrapper
    [{:keys [frame shape] :as props}]
    (let [group-wrapper (mf/use-memo (mf/deps objects)
                                     #(group-wrapper-factory objects))]
      (when (and shape (not (:hidden shape)))
        (let [shape (geom/transform-shape frame shape)
              opts #js {:shape shape}]
          (case (:type shape)
            :curve  [:> path-wrapper opts]
            :text   [:> text-wrapper opts]
            :icon   [:> icon-wrapper opts]
            :rect   [:> rect-wrapper opts]
            :path   [:> path-wrapper opts]
            :image  [:> image-wrapper opts]
            :circle [:> circle-wrapper opts]
            :group  [:> group-wrapper {:shape shape :frame frame}]
            nil))))))

(mf/defc frame-svg
  {::mf/wrap [mf/memo]}
  [{:keys [objects frame zoom] :or {zoom 1} :as props}]
  (let [modifier (-> (gpt/point (:x frame) (:y frame))
                     (gpt/negate)
                     (gmt/translate-matrix))

        frame-id (:id frame)
        modifier-ids (concat [frame-id] (cp/get-children frame-id objects))
        update-fn #(assoc-in %1 [%2 :modifiers :displacement] modifier)
        objects (reduce update-fn objects modifier-ids)
        frame (assoc-in frame [:modifiers :displacement] modifier)

        width  (* (:width frame) zoom)
        height (* (:height frame) zoom)
        vbox   (str "0 0 " (:width frame 0)
                    " "    (:height frame 0))
        wrapper (mf/use-memo
                 (mf/deps objects)
                 #(frame-wrapper-factory objects))]

    [:svg {:view-box vbox
           :width width
           :height height
           :version "1.1"
           :xmlnsXlink "http://www.w3.org/1999/xlink"
           :xmlns "http://www.w3.org/2000/svg"}
     [:& wrapper {:shape frame :view-box vbox}]]))

