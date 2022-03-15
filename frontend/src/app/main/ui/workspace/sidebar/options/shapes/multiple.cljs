;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.sidebar.options.shapes.multiple
  (:require
   [app.common.attrs :as attrs]
   [app.common.data :as d]
   [app.common.geom.shapes :as gsh]
   [app.common.pages.common :as cpc]
   [app.common.text :as txt]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.workspace.sidebar.options.menus.blur :refer [blur-attrs blur-menu]]
   [app.main.ui.workspace.sidebar.options.menus.color-selection :refer [color-selection-menu]]
   [app.main.ui.workspace.sidebar.options.menus.constraints :refer [constraint-attrs constraints-menu]]
   [app.main.ui.workspace.sidebar.options.menus.exports :refer [exports-attrs exports-menu]]
   [app.main.ui.workspace.sidebar.options.menus.fill :refer [fill-attrs fill-menu]]
   [app.main.ui.workspace.sidebar.options.menus.layer :refer [layer-attrs layer-menu]]
   [app.main.ui.workspace.sidebar.options.menus.measures :refer [measure-attrs measures-menu]]
   [app.main.ui.workspace.sidebar.options.menus.shadow :refer [shadow-attrs shadow-menu]]
   [app.main.ui.workspace.sidebar.options.menus.stroke :refer [stroke-attrs stroke-menu]]
   [app.main.ui.workspace.sidebar.options.menus.text :as ot]
   [rumext.alpha :as mf]))

;; Define how to read each kind of attribute depending on the shape type:
;;   - shape: read the attribute directly from the shape.
;;   - children: read it from all the children, and then merging it.
;;   - ignore: do not read this attribute from this shape.
;;   - text: read it from all the content nodes, and then merging it.
(def type->read-mode
  {:frame
   {:measure    :shape
    :layer      :shape
    :constraint :shape
    :fill       :shape
    :shadow     :children
    :blur       :children
    :stroke     :shape
    :text       :children
    :exports    :shape}

   :group
   {:measure    :shape
    :layer      :shape
    :constraint :shape
    :fill       :children
    :shadow     :shape
    :blur       :shape
    :stroke     :children
    :text       :children
    :exports    :shape}

   :path
   {:measure    :shape
    :layer      :shape
    :constraint :shape
    :fill       :shape
    :shadow     :shape
    :blur       :shape
    :stroke     :shape
    :text       :ignore
    :exports    :shape}

   :text
   {:measure    :shape
    :layer      :shape
    :constraint :shape
    :fill       :text
    :shadow     :shape
    :blur       :shape
    :stroke     :shape
    :text       :text
    :exports    :shape}

   :image
   {:measure    :shape
    :layer      :shape
    :constraint :shape
    :fill       :ignore
    :shadow     :shape
    :blur       :shape
    :stroke     :ignore
    :text       :ignore
    :exports    :shape}

   :rect
   {:measure    :shape
    :layer      :shape
    :constraint :shape
    :fill       :shape
    :shadow     :shape
    :blur       :shape
    :stroke     :shape
    :text       :ignore
    :exports    :shape}

   :circle
   {:measure    :shape
    :layer      :shape
    :constraint :shape
    :fill       :shape
    :shadow     :shape
    :blur       :shape
    :stroke     :shape
    :text       :ignore
    :exports    :shape}

   :svg-raw
   {:measure    :shape
    :layer      :shape
    :constraint :shape
    :fill       :shape
    :shadow     :shape
    :blur       :shape
    :stroke     :shape
    :text       :ignore
    :exports    :shape}

   :bool
   {:measure    :shape
    :layer      :shape
    :constraint :shape
    :fill       :shape
    :shadow     :shape
    :blur       :shape
    :stroke     :shape
    :text       :ignore
    :exports    :shape}})

(def group->attrs
  {:measure    measure-attrs
   :layer      layer-attrs
   :constraint constraint-attrs
   :fill       fill-attrs
   :shadow     shadow-attrs
   :blur       blur-attrs
   :stroke     stroke-attrs
   :text       ot/attrs
   :exports    exports-attrs})

(def shadow-keys [:style :color :offset-x :offset-y :blur :spread])

(defn shadow-eq
  "Function to check if two shadows are equivalent to the multiple selection (ignores their ids)"
  [s1 s2]
  (and (= (count s1) (count s2))
       (->> (map vector s1 s2)
            (every? (fn [[v1 v2]]
                      (= (select-keys v1 shadow-keys)
                         (select-keys v2 shadow-keys)))))))

(defn shadow-sel
  "Function to select the attributes that interest us for the multiple selections"
  [v]
  (mapv #(select-keys % shadow-keys) v))

(def blur-keys [:type :value])

(defn blur-eq
  "Checks if two blurs are equivalent for the multiple selection"
  [v1 v2]
  (= (select-keys v1 blur-keys) (select-keys v2 blur-keys)))

(defn blur-sel
  "Select interesting keys for multiple selection"
  [v]
  (when v (select-keys v blur-keys)))

(defn get-attrs*
  "Given a group of attributes that we want to extract and the shapes to extract them from
  returns a list of tuples [id, values] with the extracted properties for the shapes that
  applies (some of them ignore some attributes)"
  [shapes objects attr-group]
  (let [attrs (group->attrs attr-group)

        merge-attrs
        (fn [v1 v2]
          (cond
            (= attr-group :shadow) (attrs/get-attrs-multi [v1 v2] attrs shadow-eq shadow-sel)
            (= attr-group :blur)   (attrs/get-attrs-multi [v1 v2] attrs blur-eq blur-sel)
            :else                  (attrs/get-attrs-multi [v1 v2] attrs)))

        extract-attrs
        (fn [[ids values] {:keys [id type content] :as shape}]
          (let [read-mode      (get-in type->read-mode [type attr-group])
                editable-attrs (filter (get cpc/editable-attrs (:type shape)) attrs)]
            (case read-mode
              :ignore   [ids values]

              :shape    (let [;; Get the editable attrs from the shape, ensuring that all attributes
                              ;; are present, with value nil if they are not present in the shape.
                              shape-values (merge
                                             (into {} (map #(vector % nil)) editable-attrs)
                                             (select-keys shape editable-attrs))]
                          [(conj ids id)
                           (merge-attrs values shape-values)])

              :text     [(conj ids id)
                         (-> values
                             (merge-attrs (select-keys shape attrs))
                             (merge-attrs (merge
                                            (select-keys txt/default-text-attrs attrs)
                                            (attrs/get-attrs-multi (txt/node-seq content) attrs))))]

              :children (let [children (->> (:shapes shape []) (map #(get objects %)))
                              [new-ids new-values] (get-attrs* children objects attr-group)]
                          [(d/concat-vec ids new-ids) (merge-attrs values new-values)])

              [])))]

    (reduce extract-attrs [[] []] shapes)))

(def get-attrs (memoize get-attrs*))

(defn basic-shape [_ shape]
  (cond-> shape
    :always
    (dissoc :selrect :points :x :y :width :height :transform :transform-inverse :rotation :svg-transform :svg-viewbox :thumbnail)

    (= (:type shape) :path)
    (dissoc :content)))

(mf/defc options
  {::mf/wrap [#(mf/memo' % (mf/check-props ["shapes" "shapes-with-children" "page-id" "file-id"]))]
   ::mf/wrap-props false}
  [props]
  (let [shapes (unchecked-get props "shapes")
        shapes-with-children (unchecked-get props "shapes-with-children")
        page-id (unchecked-get props "page-id")
        file-id (unchecked-get props "file-id")
        objects (->> shapes-with-children (group-by :id) (d/mapm (fn [_ v] (first v))))
        show-caps (some #(and (= :path (:type %)) (gsh/open-path? %)) shapes)

        ;; Selrect/points only used for measures and it's the one that changes the most. We separate it
        ;; so we can memoize it
        objects-no-measures (->> objects (d/mapm basic-shape))
        objects-no-measures (hooks/use-equal-memo objects-no-measures)

        type :multiple
        all-types (into #{} (map :type shapes))

        [measure-ids    measure-values]    (get-attrs shapes objects :measure)

        [layer-ids      layer-values
         constraint-ids constraint-values
         fill-ids       fill-values
         shadow-ids     shadow-values
         blur-ids       blur-values
         stroke-ids     stroke-values
         text-ids       text-values
         exports-ids    exports-values]
        (mf/use-memo
         (mf/deps objects-no-measures)
         (fn []
           (into
            []
            (mapcat identity)
            [(get-attrs shapes objects-no-measures :layer)
             (get-attrs shapes objects-no-measures :constraint)
             (get-attrs shapes objects-no-measures :fill)
             (get-attrs shapes objects-no-measures :shadow)
             (get-attrs shapes objects-no-measures :blur)
             (get-attrs shapes objects-no-measures :stroke)
             (get-attrs shapes objects-no-measures :text)
             (get-attrs shapes objects-no-measures :exports)])))]

    [:div.options
     (when-not (empty? measure-ids)
       [:& measures-menu {:type type :all-types all-types :ids measure-ids :values measure-values :shape shapes}])

     (when-not (empty? constraint-ids)
       [:& constraints-menu {:ids constraint-ids :values constraint-values}])

     (when-not (empty? layer-ids)
       [:& layer-menu {:type type :ids layer-ids :values layer-values}])

     (when-not (empty? fill-ids)
       [:& fill-menu {:type type :ids fill-ids :values fill-values}])

     (when-not (empty? stroke-ids)
       [:& stroke-menu {:type type :ids stroke-ids :show-caps show-caps :values stroke-values}])
     
     (when-not (empty? shapes)
       [:& color-selection-menu {:type type :shapes (vals objects-no-measures)}])

     (when-not (empty? shadow-ids)
       [:& shadow-menu {:type type :ids shadow-ids :values shadow-values}])

     (when-not (empty? blur-ids)
       [:& blur-menu {:type type :ids blur-ids :values blur-values}])

     (when-not (empty? text-ids)
       [:& ot/text-menu {:type type :ids text-ids :values text-values}])

     (when-not (empty? exports-ids)
       [:& exports-menu {:type type :ids exports-ids :values exports-values :page-id page-id :file-id file-id}])]))
