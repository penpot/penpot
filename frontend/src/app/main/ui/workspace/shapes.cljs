;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.shapes
  "A workspace specific shapes wrappers.

  Shapes that has some peculiarities are defined in its own
  namespace under app.ui.workspace.shapes.* prefix, all the
  others are defined using a generic wrapper implemented in
  common."
  (:require
   [app.common.pages :as cp]
   [app.common.uuid :as uuid]
   [app.main.refs :as refs]
   [app.main.ui.shapes.circle :as circle]
   [app.main.ui.shapes.image :as image]
   [app.main.ui.shapes.rect :as rect]
   [app.main.ui.shapes.text.fontfaces :as ff]
   [app.main.ui.workspace.shapes.bool :as bool]
   [app.main.ui.workspace.shapes.bounding-box :refer [bounding-box]]
   [app.main.ui.workspace.shapes.common :as common]
   [app.main.ui.workspace.shapes.frame :as frame]
   [app.main.ui.workspace.shapes.group :as group]
   [app.main.ui.workspace.shapes.path :as path]
   [app.main.ui.workspace.shapes.svg-raw :as svg-raw]
   [app.main.ui.workspace.shapes.text :as text]
   [app.util.object :as obj]
   [debug :refer [debug?]]
   [okulary.core :as l]
   [rumext.alpha :as mf]))

(declare shape-wrapper)
(declare group-wrapper)
(declare svg-raw-wrapper)
(declare bool-wrapper)
(declare frame-wrapper)

(def circle-wrapper (common/generic-wrapper-factory circle/circle-shape))
(def image-wrapper (common/generic-wrapper-factory image/image-shape))
(def rect-wrapper (common/generic-wrapper-factory rect/rect-shape))

(defn make-is-moving-ref
  [id]
  (fn []
    (let [check-moving (fn [local]
                         (and (= :move (:transform local))
                              (contains? (:selected local) id)))]
      (l/derived check-moving refs/workspace-local))))

(mf/defc root-shape
  "Draws the root shape of the viewport and recursively all the shapes"
  {::mf/wrap-props false}
  [props]
  (let [objects       (obj/get props "objects")
        active-frames (obj/get props "active-frames")
        root-shapes   (get-in objects [uuid/zero :shapes])
        shapes        (->> root-shapes (mapv #(get objects %)))

        root-children (->> shapes
                           (filter #(not= :frame (:type %)))
                           (mapcat #(cp/get-object-with-children (:id %) objects)))]

    [:*
     [:& ff/fontfaces-style {:shapes root-children}]
     (for [item shapes]
       (if (= (:type item) :frame)
         [:& frame-wrapper {:shape item
                            :key (:id item)
                            :objects objects
                            :thumbnail? (not (get active-frames (:id item) false))}]

         [:& shape-wrapper {:shape item
                            :key (:id item)}]))]))

(mf/defc shape-wrapper
  {::mf/wrap [#(mf/memo' % (mf/check-props ["shape"]))]
   ::mf/wrap-props false}
  [props]
  (let [shape  (obj/get props "shape")
        opts  #js {:shape shape}

        svg-element? (and (= (:type shape) :svg-raw)
                          (not= :svg (get-in shape [:content :tag])))]

    (when (and (some? shape) (not (:hidden shape)))
      [:*
       (if-not svg-element?
         (case (:type shape)
           :path    [:> path/path-wrapper opts]
           :text    [:> text/text-wrapper opts]
           :group   [:> group-wrapper opts]
           :rect    [:> rect-wrapper opts]
           :image   [:> image-wrapper opts]
           :circle  [:> circle-wrapper opts]
           :svg-raw [:> svg-raw-wrapper opts]
           :bool    [:> bool-wrapper opts]

           ;; Only used when drawing a new frame.
           :frame [:> frame-wrapper opts]

           nil)

         ;; Don't wrap svg elements inside a <g> otherwise some can break
         [:> svg-raw-wrapper opts])

       (when (debug? :bounding-boxes)
         [:> bounding-box opts])])))

(def group-wrapper (group/group-wrapper-factory shape-wrapper))
(def svg-raw-wrapper (svg-raw/svg-raw-wrapper-factory shape-wrapper))
(def bool-wrapper (bool/bool-wrapper-factory shape-wrapper))
(def frame-wrapper (frame/frame-wrapper-factory shape-wrapper))

