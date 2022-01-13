;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.shapes.export
 (:require
  [app.common.data :as d]
  [app.common.geom.shapes :as gsh]
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

        (cond-> (and rect? (some? (:r1 shape)))
          (-> (add! :r1)
              (add! :r2)
              (add! :r3)
              (add! :r4)))

        (cond-> path?
          (-> (add! :stroke-cap-start)
              (add! :stroke-cap-end)))

        (cond-> text?
          (-> (add! :grow-type)
              (add! :content (comp json/encode uuid->string))))

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


(mf/defc export-grid-data
  [{:keys [grids]}]
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
              (obj/set! "penpot:display" (str display))))]))])

(mf/defc export-flows
  [{:keys [flows]}]
  [:> "penpot:flows" #js {}
    (for [{:keys [id name starting-frame]} flows]
      [:> "penpot:flow" #js {:id id
                             :name name
                             :starting-frame starting-frame}])])

(mf/defc export-page
  [{:keys [options]}]
  (let [saved-grids (get options :saved-grids)
        flows       (get options :flows)]
    (when (or (seq saved-grids) (seq flows))
       (let [parse-grid
             (fn [[type params]]
               {:type type :params params})
             grids (->> saved-grids (mapv parse-grid))]
         [:> "penpot:page" #js {}
          (when (seq saved-grids)
            [:& export-grid-data {:grids grids}])
          (when (seq flows)
            [:& export-flows {:flows flows}])]))))

(mf/defc export-shadow-data
  [{:keys [shadow]}]
  (for [{:keys [style hidden color offset-x offset-y blur spread]} shadow]
    [:> "penpot:shadow"
     #js {:penpot:shadow-type (d/name style)
          :penpot:hidden (str hidden)
          :penpot:color (str (:color color))
          :penpot:opacity (str (:opacity color))
          :penpot:offset-x (str offset-x)
          :penpot:offset-y (str offset-y)
          :penpot:blur (str blur)
          :penpot:spread (str spread)}]))

(mf/defc export-blur-data [{:keys [blur]}]
  (when (some? blur)
    (let [{:keys [type hidden value]} blur]
      [:> "penpot:blur"
       #js {:penpot:blur-type (d/name type)
            :penpot:hidden    (str hidden)
            :penpot:value     (str value)}])))

(mf/defc export-exports-data [{:keys [exports]}]
  (for [{:keys [scale suffix type]} exports]
    [:> "penpot:export"
     #js {:penpot:type   (d/name type)
          :penpot:suffix suffix
          :penpot:scale  (str scale)}]))

(defn style->str
  [style]
  (->> style
       (map (fn [[key val]] (str (d/name key) ":" val)))
       (str/join "; ")))

(mf/defc export-svg-data [shape]
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
     (let [props
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
          [:> "penpot:svg-child" {} leaf])]))])

(mf/defc export-interactions-data
  [{:keys [interactions]}]
  (when-not (empty? interactions)
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
             :penpot:preserve-scroll ((d/nilf str) (:preserve-scroll interaction))}])]))

(mf/defc export-data
  [{:keys [shape]}]
  (let [props (-> (obj/new) (add-data shape) (add-library-refs shape))]
    [:> "penpot:shape" props
     [:& export-shadow-data       shape]
     [:& export-blur-data         shape]
     [:& export-exports-data      shape]
     [:& export-svg-data          shape]
     [:& export-interactions-data shape]
     [:& export-grid-data         shape]]))

