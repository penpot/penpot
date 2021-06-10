;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.shapes.export
 (:require
  [app.common.data :as d]
  [app.common.geom.matrix :as gmt]
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

(defn add-data
  "Adds as metadata properties that we cannot deduce from the exported SVG"
  [props shape]
  (let [add!
        (fn [props attr val]
          (let [ns-attr (str "penpot:" (-> attr d/name))]
            (-> props
                (obj/set! ns-attr val))))
        frame? (= :frame (:type shape))
        group? (= :group (:type shape))
        rect?  (= :rect (:type shape))
        text?  (= :text (:type shape))
        mask?  (and group? (:masked-group? shape))]
    (-> props
        (add! :name              (-> shape :name))
        (add! :blocked           (-> shape (:blocked false) str))
        (add! :hidden            (-> shape (:hidden false) str))
        (add! :type              (-> shape :type d/name))

        (add! :stroke-style      (-> shape (:stroke-style :none) d/name))
        (add! :stroke-alignment  (-> shape (:stroke-alignment :center) d/name))

        (add! :transform         (-> shape (:transform (gmt/matrix)) str))
        (add! :transform-inverse (-> shape (:transform-inverse (gmt/matrix)) str))

        (cond-> (and rect? (some? (:r1 shape)))
          (-> (add! :r1 (-> shape (:r1 0) str))
              (add! :r2 (-> shape (:r2 0) str))
              (add! :r3 (-> shape (:r3 0) str))
              (add! :r4 (-> shape (:r4 0) str))))

        (cond-> text?
          (-> (add! :grow-type (-> shape :grow-type))
              (add! :content (-> shape :content json/encode))))

        (cond-> mask?
          (add! :masked-group "true")))))

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

