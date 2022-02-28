;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.shapes.export
  "Components that generates penpot specific svg nodes with
  exportation data. This xml nodes serves mainly to enable
  importation."
  (:require
   [app.common.data :as d]
   [app.common.geom.shapes :as gsh]
   [app.main.ui.context :as muc]
   [app.util.json :as json]
   [app.util.object :as obj]
   [app.util.svg :as usvg]
   [cuerdas.core :as str]
   [rumext.alpha :as mf]))

(def include-metadata-ctx (mf/create-context false))

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

(defn uuid->string [m]
  (->> m
       (d/deep-mapm
        (fn [[k v]]
          (if (uuid? v)
            [k (str v)]
            [k v])))))

(defn bool->str [val]
  (when (some? val) (str val)))

(defn add-factory [shape]
  (fn add!
    ([props attr]
     (add! props attr str))

    ([props attr trfn]
     (let [val (get shape attr)
           val (if (keyword? val) (d/name val) val)
           ns-attr (str "penpot:" (-> attr d/name))]
       (cond-> props
         (some? val)
         (obj/set! ns-attr (trfn val)))))))

(defn add-data
  "Adds as metadata properties that we cannot deduce from the exported SVG"
  [props shape]
  (let [add! (add-factory shape)
        group? (= :group (:type shape))
        rect?  (= :rect (:type shape))
        image? (= :image (:type shape))
        text?  (= :text (:type shape))
        path?  (= :path (:type shape))
        mask?  (and group? (:masked-group? shape))
        bool?  (= :bool (:type shape))
        center (gsh/center-shape shape)]
    (-> props
        (add! :name)
        (add! :blocked)
        (add! :hidden)
        (add! :type)
        (add! :stroke-style)
        (add! :stroke-alignment)
        (add! :hide-fill-on-export)
        (add! :transform)
        (add! :transform-inverse)
        (add! :flip-x)
        (add! :flip-y)
        (add! :proportion)
        (add! :proportion-lock)
        (add! :rotation)
        (obj/set! "penpot:center-x" (-> center :x str))
        (obj/set! "penpot:center-y" (-> center :y str))

        ;; Constraints
        (add! :constraints-h)
        (add! :constraints-v)
        (add! :fixed-scroll)

        (cond-> (and (or rect? image?) (some? (:r1 shape)))
          (-> (add! :r1)
              (add! :r2)
              (add! :r3)
              (add! :r4)))

        (cond-> (and image? (some? (:rx shape)))
          (-> (add! :rx)
              (add! :ry)))

        (cond-> path?
          (-> (add! :stroke-cap-start)
              (add! :stroke-cap-end)))

        (cond-> text?
          (-> (add! :grow-type)
              (add! :content (comp json/encode uuid->string))
              (add! :position-data (comp json/encode uuid->string))))

        (cond-> mask?
          (obj/set! "penpot:masked-group" "true"))

        (cond-> bool?
          (add! :bool-type)))))


(defn add-library-refs [props shape]
  (let [add! (add-factory shape)]
    (-> props
        (add! :fill-color-ref-id)
        (add! :fill-color-ref-file)
        (add! :stroke-color-ref-id)
        (add! :stroke-color-ref-file)
        (add! :typography-ref-id)
        (add! :typography-ref-file)
        (add! :component-file)
        (add! :component-id)
        (add! :component-root)
        (add! :shape-ref))))

(defn prefix-keys [m]
  (letfn [(prefix-entry [[k v]]
            [(str "penpot:" (d/name k)) v])]
    (into {} (map prefix-entry) m)))

(defn- export-grid-data [{:keys [grids]}]
  (when (d/not-empty? grids)
    (mf/html
     [:> "penpot:grids" #js {}
      (for [{:keys [type display params]} grids]
        (let [props (->> (dissoc params :color)
                         (prefix-keys)
                         (clj->js))]
          [:> "penpot:grid"
           (-> props
               (obj/set! "penpot:color" (get-in params [:color :color]))
               (obj/set! "penpot:opacity" (get-in params [:color :opacity]))
               (obj/set! "penpot:type" (d/name type))
               (cond-> (some? display)
                 (obj/set! "penpot:display" (str display))))]))])))

(mf/defc export-flows
  [{:keys [flows]}]
  [:> "penpot:flows" #js {}
    (for [{:keys [id name starting-frame]} flows]
      [:> "penpot:flow" #js {:id id
                             :name name
                             :starting-frame starting-frame}])])

(mf/defc export-guides
  [{:keys [guides]}]
  [:> "penpot:guides" #js {}
   (for [{:keys [position frame-id axis]} (vals guides)]
     [:> "penpot:guide" #js {:position position
                             :frame-id frame-id
                             :axis (d/name axis)}])])

(mf/defc export-page
  [{:keys [options]}]
  (let [saved-grids (get options :saved-grids)
        flows       (get options :flows)
        guides      (get options :guides)]
    [:> "penpot:page" #js {}
     (when (d/not-empty? saved-grids)
       (let [parse-grid (fn [[type params]] {:type type :params params})
             grids (->> saved-grids (mapv parse-grid))]
         [:& export-grid-data {:grids grids}]))

     (when (d/not-empty? flows)
       [:& export-flows {:flows flows}])

     (when (d/not-empty? guides)
       [:& export-guides {:guides guides}])]))

(defn- export-shadow-data [{:keys [shadow]}]
  (mf/html
   (for [{:keys [style hidden color offset-x offset-y blur spread]} shadow]
     [:> "penpot:shadow"
      #js {:penpot:shadow-type (d/name style)
           :penpot:hidden (str hidden)
           :penpot:color (str (:color color))
           :penpot:opacity (str (:opacity color))
           :penpot:offset-x (str offset-x)
           :penpot:offset-y (str offset-y)
           :penpot:blur (str blur)
           :penpot:spread (str spread)}])))

(defn- export-blur-data [{:keys [blur]}]
  (when-let [{:keys [type hidden value]} blur]
    (mf/html
     [:> "penpot:blur"
      #js {:penpot:blur-type (d/name type)
           :penpot:hidden    (str hidden)
           :penpot:value     (str value)}])))

(defn export-exports-data [{:keys [exports]}]
  (mf/html
   (for [{:keys [scale suffix type]} exports]
     [:> "penpot:export"
      #js {:penpot:type   (d/name type)
           :penpot:suffix suffix
           :penpot:scale  (str scale)}])))

(defn str->style
  [style-str]
  (if (string? style-str)
    (->> (str/split style-str ";")
         (map str/trim)
         (map #(str/split % ":"))
         (group-by first)
         (map (fn [[key val]]
                (vector (keyword key) (second (first val)))))
         (into {}))
    style-str))

(defn style->str
  [style]
  (->> style
       (map (fn [[key val]] (str (d/name key) ":" val)))
       (str/join "; ")))

(defn- export-svg-data [shape]
  (mf/html
   [:*
    (when (contains? shape :svg-attrs)
      (let [svg-transform (get shape :svg-transform)
            svg-attrs     (->> shape :svg-attrs keys (mapv d/name) (str/join ",") )
            svg-defs      (->> shape :svg-defs keys (mapv d/name) (str/join ","))]
        [:> "penpot:svg-import"
         #js {:penpot:svg-attrs          (when-not (empty? svg-attrs) svg-attrs)
              ;; Style and filter are special properties so we need to save it otherwise will be indistingishible from
              ;; standard properties
              :penpot:svg-style          (when (contains? (:svg-attrs shape) :style) (style->str (get-in shape [:svg-attrs :style])))
              :penpot:svg-filter         (when (contains? (:svg-attrs shape) :filter) (get-in shape [:svg-attrs :filter]))
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
      (let [shape (-> shape (d/update-in-when [:content :attrs :style] str->style))
            props
            (-> (obj/new)
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


(defn- export-fills-data [{:keys [fills]}]
  (when-let [fills (seq fills)]
    (mf/html
     [:> "penpot:fills" #js {}
      (for [[index fill] (d/enumerate fills)]
        [:> "penpot:fill"
         #js {:penpot:fill-color          (if (some? (:fill-color-gradient fill))
                                              (str/format "url(#%s)" (str "fill-color-gradient_" (mf/use-ctx muc/render-ctx) "_" index))
                                              (d/name (:fill-color fill)))
              :penpot:fill-color-ref-file (d/name (:fill-color-ref-file fill))
              :penpot:fill-color-ref-id   (d/name (:fill-color-ref-id fill))
              :penpot:fill-opacity        (d/name (:fill-opacity fill))}])])))

(defn- export-strokes-data [{:keys [strokes]}]
  (when-let [strokes (seq strokes)]
    (mf/html
     [:> "penpot:strokes" #js {}
      (for [[index stroke] (d/enumerate strokes)]
        [:> "penpot:stroke"
         #js {:penpot:stroke-color          (if (some? (:stroke-color-gradient stroke))
                                              (str/format "url(#%s)" (str "stroke-color-gradient_" (mf/use-ctx muc/render-ctx) "_" index))
                                              (d/name (:stroke-color stroke)))
              :penpot:stroke-color-ref-file (d/name (:stroke-color-ref-file stroke))
              :penpot:stroke-color-ref-id   (d/name (:stroke-color-ref-id stroke))
              :penpot:stroke-opacity        (d/name (:stroke-opacity stroke))
              :penpot:stroke-style          (d/name (:stroke-style stroke))
              :penpot:stroke-width          (d/name (:stroke-width stroke))
              :penpot:stroke-alignment      (d/name (:stroke-alignment stroke))
              :penpot:stroke-cap-start      (d/name (:stroke-cap-start stroke))
              :penpot:stroke-cap-end        (d/name (:stroke-cap-end stroke))}])])))


(defn- export-interactions-data [{:keys [interactions]}]
  (when-let [interactions (seq interactions)]
    (mf/html
     [:> "penpot:interactions" #js {}
      (for [interaction interactions]
        [:> "penpot:interaction"
         #js {:penpot:event-type (d/name (:event-type interaction))
              :penpot:action-type (d/name (:action-type interaction))
              :penpot:delay ((d/nilf str) (:delay interaction))
              :penpot:destination ((d/nilf str) (:destination interaction))
              :penpot:overlay-pos-type ((d/nilf d/name) (:overlay-pos-type interaction))
              :penpot:overlay-position-x ((d/nilf get-in) interaction [:overlay-position :x])
              :penpot:overlay-position-y ((d/nilf get-in) interaction [:overlay-position :y])
              :penpot:url (:url interaction)
              :penpot:close-click-outside ((d/nilf str) (:close-click-outside interaction))
              :penpot:background-overlay ((d/nilf str) (:background-overlay interaction))
              :penpot:preserve-scroll ((d/nilf str) (:preserve-scroll interaction))}])])))


(mf/defc export-data
  [{:keys [shape]}]
  (let [props (-> (obj/new) (add-data shape) (add-library-refs shape))]
    [:> "penpot:shape" props
     (export-shadow-data       shape)
     (export-blur-data         shape)
     (export-exports-data      shape)
     (export-svg-data          shape)
     (export-interactions-data shape)
     (export-fills-data        shape)
     (export-strokes-data      shape)
     (export-grid-data         shape)]))

