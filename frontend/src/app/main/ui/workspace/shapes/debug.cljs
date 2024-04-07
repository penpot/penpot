;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.shapes.debug
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.geom.shapes :as gsh]
   [app.common.geom.shapes.path :as gsp]
   [app.common.geom.shapes.text :as gst]
   [app.common.math :as mth]
   [app.common.svg.path.bool :as pb]
   [app.common.svg.path.shapes-to-path :as stp]
   [app.common.svg.path.subpath :as ups]
   [app.main.refs :as refs]
   [app.util.color :as uc]
   [app.util.debug :as dbg]
   [app.util.dom :as dom]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(mf/defc debug-bounding-boxes
  [{:keys [shape]}]
  (let [points (->> (:points shape)
                    (map #(dm/fmt "%,%" (dm/get-prop % :x) (dm/get-prop % :y)))
                    (str/join " "))
        color (mf/use-memo #(uc/random-color))
        sr (:selrect shape)]
    [:g.debug-bounding-boxes
     [:rect {:transform (gsh/transform-str shape)
             :x (:x sr)
             :y (:y sr)
             :width (:width sr)
             :height (:height sr)
             :fill color
             :opacity 0.2}]
     (for [p (:points shape)]
       [:circle {:cx (dm/get-prop p :x)
                 :cy (dm/get-prop p :y)
                 :r 2
                 :fill color}])
     [:polygon {:points points
                :stroke-width 1
                :stroke color}]]))

(mf/defc debug-text-bounds
  {::mf/wrap-props false}
  [props]
  (let [shape (unchecked-get props "shape")
        zoom (mf/deref refs/selected-zoom)
        bounding-box (gst/shape->rect shape)
        ctx (js* "document.createElement(\"canvas\").getContext(\"2d\")")]
    [:g {:transform (gsh/transform-str shape)}
     [:rect {:x (:x bounding-box)
             :y (:y bounding-box)
             :width (:width bounding-box)
             :height (:height bounding-box)
             :style {:fill "none"
                     :stroke "orange"
                     :stroke-width (/ 1 zoom)}}]

     (for [[index data] (d/enumerate (:position-data shape))]
       (let [{:keys [x y width height]} data
             res (dom/measure-text ctx (:font-size data) (:font-family data) (:text data))]
         [:g {:key (dm/str index)}
          ;; Text fragment bounding box
          [:rect {:x x
                  :y (- y height)
                  :width width
                  :height height
                  :style {:fill "none"
                          :stroke "red"
                          :stroke-width (/ 1 zoom)}}]

          ;; Text baseline
          [:line {:x1 (mth/round x)
                  :y1 (mth/round (- (:y data) (:height data)))
                  :x2 (mth/round (+ x width))
                  :y2 (mth/round (- (:y data) (:height data)))
                  :style {:stroke "blue"
                          :stroke-width (/ 1 zoom)}}]

          [:line {:x1 (:x data)
                  :y1 (- (:y data) (:descent res))
                  :x2 (+ (:x data) (:width data))
                  :y2 (- (:y data) (:descent res))
                  :style {:stroke "green"
                          :stroke-width (/ 2 zoom)}}]]))]))

(mf/defc debug-bool-shape
  {::mf/wrap-props false}
  [{:keys [shape]}]

  (let [objects (mf/deref refs/workspace-page-objects)
        zoom (mf/deref refs/selected-zoom)

        radius (/ 3 zoom)

        c1 (-> (get objects (first (:shapes shape)))
               (stp/convert-to-path objects))
        c2 (-> (get objects (second (:shapes shape)))
               (stp/convert-to-path objects))

        content-a (:content c1)
        content-b (:content c2)

        bool-type (:bool-type shape)
        should-reverse? (and (not= :union bool-type)
                             (= (ups/clockwise? content-b)
                                (ups/clockwise? content-a)))

        content-a (-> (:content c1)
                      (pb/close-paths)
                      (pb/add-previous))

        content-b (-> (:content c2)
                      (pb/close-paths)
                      (cond-> should-reverse? (ups/reverse-content))
                      (pb/add-previous))


        sr-a (gsp/content->selrect content-a)
        sr-b (gsp/content->selrect content-b)

        [content-a-split content-b-split] (pb/content-intersect-split content-a content-b sr-a sr-b)

        ;;content-a-geom (gsp/content->geom-data content-a)
        ;;content-b-geom (gsp/content->geom-data content-b)
        ;;content-a-split (->> content-a-split #_(filter #(pb/contains-segment? % content-b sr-b content-b-geom)))
        ;;content-b-split (->> content-b-split #_(filter #(pb/contains-segment? % content-a sr-a content-a-geom)))
        ]
    [:*
     (for [[i cmd] (d/enumerate content-a-split)]
       (let [p1 (:prev cmd)
             p2 (gsp/command->point cmd)

             hp (case (:command cmd)
                  :line-to  (-> (gsp/command->line cmd)
                                (gsp/line-values 0.5))

                  :curve-to (-> (gsp/command->bezier cmd)
                                (gsp/curve-values 0.5))
                  nil)]
         [:*
          (when p1
            [:circle {:data-i i :key (dm/str "c11-" i) :cx (:x p1) :cy (:y p1) :r radius :fill "red"}])
          [:circle {:data-i i :key (dm/str "c12-" i) :cx (:x p2) :cy (:y p2) :r radius :fill "red"}]

          (when hp
            [:circle {:data-i i :key (dm/str "c13-" i) :cx (:x hp) :cy (:y hp) :r radius :fill "orange"}])]))

     (for [[i cmd] (d/enumerate content-b-split)]
       (let [p1 (:prev cmd)
             p2 (gsp/command->point cmd)

             hp (case (:command cmd)
                  :line-to  (-> (gsp/command->line cmd)
                                (gsp/line-values 0.5))

                  :curve-to (-> (gsp/command->bezier cmd)
                                (gsp/curve-values 0.5))
                  nil)]
         [:*
          (when p1
            [:circle {:key (dm/str "c21-" i) :cx (:x p1) :cy (:y p1) :r radius :fill "blue"}])
          [:circle {:key (dm/str "c22-" i) :cx (:x p2) :cy (:y p2) :r radius :fill "blue"}]

          (when hp
            [:circle {:data-i i :key (dm/str "c13-" i) :cx (:x hp) :cy (:y hp) :r radius :fill "green"}])]))]))

(mf/defc shape-debug
  [{:keys [shape]}]
  [:*
   (when ^boolean (dbg/enabled? :bounding-boxes)
     [:& debug-bounding-boxes {:shape shape}])

   (when (and ^boolean (dbg/enabled? :bool-shapes)
              ^boolean (cfh/bool-shape? shape))
     [:& debug-bool-shape {:shape shape}])

   (when (and ^boolean (dbg/enabled? :text-outline)
              ^boolean (cfh/text-shape? shape)
              ^boolean (seq (:position-data shape)))
     [:& debug-text-bounds {:shape shape}])])
