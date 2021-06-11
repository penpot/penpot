;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.shapes.export
 (:require
  [app.common.data :as d]
  [app.common.geom.matrix :as gmt]
  [app.common.geom.shapes :as gsh]
  [app.util.json :as json]
  [app.util.object :as obj]
  [app.util.svg :as usvg]
  [cuerdas.core :as str]
  [rumext.alpha :as mf]))

(mf/defc render-xml
  [{{:keys [tag attrs content] :as node} :xml}]

  (cond
    (map? node)
    [:> (d/name tag) (clj->js (usvg/clean-attrs attrs))
     (for [child content]
       [:& render-xml {:xml child}])]

    (string? node)
    node

    :else
    nil))

(defn bool->str [val]
  (when (some? val) (str val)))

(defn add-data
  "Adds as metadata properties that we cannot deduce from the exported SVG"
  [props shape]
  (letfn [(add!
            ([props attr]
             (add! props attr str))

            ([props attr trfn]
             (let [val (get shape attr)
                   val (if (keyword? val) (d/name val) val)
                   ns-attr (str "penpot:" (-> attr d/name))]
               (cond-> props
                 (some? val)
                 (obj/set! ns-attr (trfn val))))))]
    (let [frame? (= :frame (:type shape))
          group? (= :group (:type shape))
          rect?  (= :rect (:type shape))
          text?  (= :text (:type shape))
          mask?  (and group? (:masked-group? shape))
          center (gsh/center-shape shape)]
      (-> props
          (add! :name)
          (add! :blocked)
          (add! :hidden)
          (add! :type)
          (add! :stroke-style)
          (add! :stroke-alignment)
          (add! :transform)
          (add! :transform-inverse)
          (add! :flip-x)
          (add! :flip-y)
          (add! :proportion)
          (add! :proportion-lock)
          (add! :rotation)
          (obj/set! "penpot:center-x" (-> center :x str))
          (obj/set! "penpot:center-y" (-> center :y str))

          (cond-> (and rect? (some? (:r1 shape)))
            (-> (add! :r1)
                (add! :r2)
                (add! :r3)
                (add! :r4)))

          (cond-> text?
            (-> (add! :grow-type)
                (add! :content json/encode)))

          (cond-> mask?
            (obj/set! "penpot:masked-group" "true"))))))

(mf/defc export-data
  [{:keys [shape]}]
  (let [props (-> (obj/new)
                  (add-data shape))]
    [:> "penpot:shape" props
     (for [{:keys [style hidden color offset-x offset-y blur spread]} (:shadow shape)]
       [:> "penpot:shadow" #js {:penpot:shadow-type (d/name style)
                                :penpot:hidden (str hidden)
                                :penpot:color (str (:color color))
                                :penpot:opacity (str (:opacity color))
                                :penpot:offset-x (str offset-x)
                                :penpot:offset-y (str offset-y)
                                :penpot:blur (str blur)
                                :penpot:spread (str spread)}])

     (when (some? (:blur shape))
       (let [{:keys [type hidden value]} (:blur shape)]
         [:> "penpot:blur" #js {:penpot:blur-type (d/name type)
                                :penpot:hidden    (str hidden)
                                :penpot:value     (str value)}]))

     (for [{:keys [scale suffix type]} (:exports shape)]
       [:> "penpot:export" #js {:penpot:type   (d/name type)
                                :penpot:suffix suffix
                                :penpot:scale  (str scale)}])

     (when (contains? shape :svg-attrs)
       (let [svg-transform (get shape :svg-transform)
             svg-attrs     (->> shape :svg-attrs keys (mapv d/name) (str/join ",") )
             svg-defs      (->> shape :svg-defs keys (mapv d/name) (str/join ","))]
         [:> "penpot:svg-import" #js {:penpot:svg-attrs          (when-not (empty? svg-attrs) svg-attrs)
                                      :penpot:svg-defs           (when-not (empty? svg-defs) svg-defs)
                                      :penpot:svg-transform      (when svg-transform (str svg-transform))
                                      :penpot:svg-viewbox-x      (get-in shape [:svg-viewbox :x])
                                      :penpot:svg-viewbox-y      (get-in shape [:svg-viewbox :y])
                                      :penpot:svg-viewbox-width  (get-in shape [:svg-viewbox :width])
                                      :penpot:svg-viewbox-height (get-in shape [:svg-viewbox :height])}
          (for [[def-id def-xml] (:svg-defs shape)]
            [:> "penpot:svg-def" #js {:def-id def-id}
             [:& render-xml {:xml def-xml}]])]))

     (when (= (:type shape) :svg-raw)
       (let [props (-> (obj/new)
                       (obj/set! "penpot:x" (:x shape))
                       (obj/set! "penpot:y" (:y shape))
                       (obj/set! "penpot:width" (:width shape))
                       (obj/set! "penpot:height" (:height shape))
                       (obj/set! "penpot:tag" (-> (get-in shape [:content :tag]) d/name))
                       (obj/merge! (-> (get-in shape [:content :attrs])
                                       (clj->js))))]
         [:> "penpot:svg-content" props
          (for [leaf (->> shape :content :content (filter string?))]
            [:> "penpot:svg-child" {} leaf])]))]))

