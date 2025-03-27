;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.shapes.multiple
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.attrs :as attrs]
   [app.common.data :as d]
   [app.common.geom.shapes :as gsh]
   [app.common.text :as txt]
   [app.common.types.component :as ctk]
   [app.common.types.shape.attrs :refer [editable-attrs]]
   [app.common.types.shape.layout :as ctl]
   [app.main.refs :as refs]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.workspace.sidebar.options.menus.blur :refer [blur-attrs blur-menu]]
   [app.main.ui.workspace.sidebar.options.menus.color-selection :refer [color-selection-menu*]]
   [app.main.ui.workspace.sidebar.options.menus.component :refer [component-menu]]
   [app.main.ui.workspace.sidebar.options.menus.constraints :refer [constraint-attrs constraints-menu]]
   [app.main.ui.workspace.sidebar.options.menus.exports :refer [exports-attrs exports-menu]]
   [app.main.ui.workspace.sidebar.options.menus.fill :refer [fill-attrs fill-menu]]
   [app.main.ui.workspace.sidebar.options.menus.layer :refer [layer-attrs layer-menu]]
   [app.main.ui.workspace.sidebar.options.menus.layout-container :refer [layout-container-flex-attrs layout-container-menu]]
   [app.main.ui.workspace.sidebar.options.menus.layout-item :refer [layout-item-attrs layout-item-menu]]
   [app.main.ui.workspace.sidebar.options.menus.measures :refer [select-measure-keys measure-attrs measures-menu*]]
   [app.main.ui.workspace.sidebar.options.menus.shadow :refer [shadow-attrs shadow-menu*]]
   [app.main.ui.workspace.sidebar.options.menus.stroke :refer [stroke-attrs stroke-menu]]
   [app.main.ui.workspace.sidebar.options.menus.text :as ot]
   [rumext.v2 :as mf]))

;; Define how to read each kind of attribute depending on the shape type:
;;   - shape: read the attribute directly from the shape.
;;   - children: read it from all the children, and then merging it.
;;   - ignore: do not read this attribute from this shape.
;;   - text: read it from all the content nodes, and then merging it.
(def type->read-mode
  {:frame
   {:measure          :shape
    :layer            :shape
    :constraint       :shape
    :fill             :shape
    :shadow           :shape
    :blur             :shape
    :stroke           :shape
    :text             :children
    :exports          :shape
    :layout-container :shape
    :layout-item      :shape}

   :group
   {:measure          :shape
    :layer            :shape
    :constraint       :shape
    :fill             :children
    :shadow           :shape
    :blur             :shape
    :stroke           :children
    :text             :children
    :exports          :shape
    :layout-container :ignore
    :layout-item      :shape}

   :path
   {:measure          :shape
    :layer            :shape
    :constraint       :shape
    :fill             :shape
    :shadow           :shape
    :blur             :shape
    :stroke           :shape
    :text             :ignore
    :exports          :shape
    :layout-container :ignore
    :layout-item      :shape}

   :text
   {:measure          :shape
    :layer            :shape
    :constraint       :shape
    :fill             :text
    :shadow           :shape
    :blur             :shape
    :stroke           :shape
    :text             :text
    :exports          :shape
    :layout-container :ignore
    :layout-item      :shape}

   :image
   {:measure          :shape
    :layer            :shape
    :constraint       :shape
    :fill             :ignore
    :shadow           :shape
    :blur             :shape
    :stroke           :ignore
    :text             :ignore
    :exports          :shape
    :layout-container :ignore
    :layout-item      :shape}

   :rect
   {:measure          :shape
    :layer            :shape
    :constraint       :shape
    :fill             :shape
    :shadow           :shape
    :blur             :shape
    :stroke           :shape
    :text             :ignore
    :exports          :shape
    :layout-container :ignore
    :layout-item      :shape}

   :circle
   {:measure          :shape
    :layer            :shape
    :constraint       :shape
    :fill             :shape
    :shadow           :shape
    :blur             :shape
    :stroke           :shape
    :text             :ignore
    :exports          :shape
    :layout-container :ignore
    :layout-item      :shape}

   :svg-raw
   {:measure          :shape
    :layer            :shape
    :constraint       :shape
    :fill             :shape
    :shadow           :shape
    :blur             :shape
    :stroke           :shape
    :text             :ignore
    :exports          :shape
    :layout-container :ignore
    :layout-item      :shape}

   :bool
   {:measure          :shape
    :layer            :shape
    :constraint       :shape
    :fill             :shape
    :shadow           :shape
    :blur             :shape
    :stroke           :shape
    :text             :ignore
    :exports          :shape
    :layout-container :ignore
    :layout-item      :shape}})

(def group->attrs
  {:measure           measure-attrs
   :layer             layer-attrs
   :constraint        constraint-attrs
   :fill              fill-attrs
   :shadow            shadow-attrs
   :blur              blur-attrs
   :stroke            stroke-attrs
   :text              txt/text-all-attrs
   :exports           exports-attrs
   :layout-container  layout-container-flex-attrs
   :layout-item       layout-item-attrs})

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

(def blur-keys [:type :value :hidden])

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
        (fn [[ids values] {:keys [id type] :as shape}]
          (let [read-mode      (get-in type->read-mode [type attr-group])
                editable-attrs (filter (get editable-attrs (:type shape)) attrs)]
            (case read-mode
              :ignore
              [ids values]

              :shape
              (let [;; Get the editable attrs from the shape, ensuring that all attributes
                    ;; are present, with value nil if they are not present in the shape.
                    shape-values (merge
                                  (into {} (map #(vector % nil)) editable-attrs)
                                  (cond
                                    (= attr-group :measure) (select-measure-keys shape)
                                    :else (select-keys shape editable-attrs)))]
                [(conj ids id)
                 (merge-attrs values shape-values)])

              :text
              (let [shape-attrs (select-keys shape attrs)

                    content-attrs
                    (attrs/get-text-attrs-multi shape txt/default-text-attrs attrs)

                    new-values
                    (-> values
                        (merge-attrs shape-attrs)
                        (merge-attrs content-attrs))]
                [(conj ids id)
                 new-values])

              :children
              (let [children (->> (:shapes shape []) (map #(get objects %)))
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

(defn- is-bool-descendant?
  [[_ shape] objects selected-shape-ids]

  (let [parent-id (:parent-id shape)
        parent (get objects parent-id)]
    (cond
      (nil? shape) false                                                   ;; failsafe
      (contains? selected-shape-ids (:id shape)) false                     ;; if it is one of the selected shapes, it is considerer not a bool descendant
      (= :bool (:type parent)) true                                        ;; if its parent is of type bool, it is a bool descendant
      :else (recur [parent-id parent] objects selected-shape-ids))))  ;; else, check its parent

(mf/defc options
  {::mf/wrap [#(mf/memo' % (mf/check-props ["shapes" "shapes-with-children" "page-id" "file-id"]))]
   ::mf/wrap-props false}
  [props]
  (let [shapes               (unchecked-get props "shapes")
        shapes-with-children (unchecked-get props "shapes-with-children")

        ;; remove children from bool shapes
        shape-ids (into #{} (map :id) shapes)

        objects (->> shapes-with-children (group-by :id) (d/mapm (fn [_ v] (first v))))
        objects
        (into {}
              (filter #(not (is-bool-descendant? % objects shape-ids)))
              objects)

        workspace-modifiers (mf/deref refs/workspace-modifiers)
        shapes (map #(gsh/transform-shape % (get-in workspace-modifiers [(:id %) :modifiers])) shapes)

        page-id (unchecked-get props "page-id")
        file-id (unchecked-get props "file-id")
        shared-libs (unchecked-get props "libraries")

        show-caps (some #(and (= :path (:type %)) (gsh/open-path? %)) shapes)

        ;; Selrect/points only used for measures and it's the one that changes the most. We separate it
        ;; so we can memoize it
        objects-no-measures (->> objects (d/mapm basic-shape))
        objects-no-measures (hooks/use-equal-memo objects-no-measures)

        type :multiple
        all-types (into #{} (map :type shapes))

        ids (->> shapes (map :id))
        is-layout-child-ref (mf/use-memo (mf/deps ids) #(refs/is-layout-child? ids))
        is-layout-child? (mf/deref is-layout-child-ref)

        is-flex-parent-ref (mf/use-memo (mf/deps ids) #(refs/flex-layout-child? ids))
        is-flex-parent? (mf/deref is-flex-parent-ref)

        is-grid-parent-ref (mf/use-memo (mf/deps ids) #(refs/grid-layout-child? ids))
        is-grid-parent? (mf/deref is-grid-parent-ref)

        has-text? (contains? all-types :text)

        has-flex-layout-container? (->> shapes (some ctl/flex-layout?))

        all-layout-child-ref (mf/use-memo (mf/deps ids) #(refs/all-layout-child? ids))
        all-layout-child? (mf/deref all-layout-child-ref)

        all-flex-layout-container? (->> shapes (every? ctl/flex-layout?))

        [measure-ids    measure-values]    (get-attrs shapes objects :measure)

        [layer-ids            layer-values
         text-ids             text-values
         constraint-ids       constraint-values
         fill-ids             fill-values
         shadow-ids           shadow-values
         blur-ids             blur-values
         stroke-ids           stroke-values
         exports-ids          exports-values
         layout-container-ids layout-container-values
         layout-item-ids      layout-item-values]
        (mf/use-memo
         (mf/deps shapes objects-no-measures)
         (fn []
           (into
            []
            (mapcat identity)
            [(get-attrs shapes objects-no-measures :layer)
             (get-attrs shapes objects-no-measures :text)
             (get-attrs shapes objects-no-measures :constraint)
             (get-attrs shapes objects-no-measures :fill)
             (get-attrs shapes objects-no-measures :shadow)
             (get-attrs shapes objects-no-measures :blur)
             (get-attrs shapes objects-no-measures :stroke)
             (get-attrs shapes objects-no-measures :exports)
             (get-attrs shapes objects-no-measures :layout-container)
             (get-attrs shapes objects-no-measures :layout-item)])))

        components (filter ctk/instance-head? shapes)]

    [:div {:class (stl/css :options)}
     (when-not (empty? layer-ids)
       [:& layer-menu {:type type :ids layer-ids :values layer-values}])

     (when-not (empty? measure-ids)
       [:> measures-menu* {:type type :all-types all-types :ids measure-ids :values measure-values :shape shapes}])

     (when-not (empty? components)
       [:& component-menu {:shapes components}])

     [:& layout-container-menu
      {:type type
       :ids layout-container-ids
       :values layout-container-values
       :multiple true}]

     (when (or is-layout-child? has-flex-layout-container?)
       [:& layout-item-menu
        {:type type
         :ids layout-item-ids
         :is-layout-child? all-layout-child?
         :is-layout-container? all-flex-layout-container?
         :is-flex-parent? is-flex-parent?
         :is-grid-parent? is-grid-parent?
         :values layout-item-values}])

     (when-not (or (empty? constraint-ids) ^boolean is-layout-child?)
       [:& constraints-menu {:ids constraint-ids :values constraint-values}])

     (when-not (empty? text-ids)
       [:& ot/text-menu {:type type :ids text-ids :values text-values}])

     (when-not (empty? fill-ids)
       [:& fill-menu {:type type :ids fill-ids :values fill-values}])

     (when-not (empty? stroke-ids)
       [:& stroke-menu {:type type :ids stroke-ids :show-caps show-caps :values stroke-values
                        :disable-stroke-style has-text?}])

     (when-not (empty? shapes)
       [:> color-selection-menu*
        {:file-id file-id
         :type type
         :shapes (vals objects-no-measures)
         :libraries shared-libs}])

     (when-not (empty? shadow-ids)
       [:> shadow-menu* {:type type
                         :ids shadow-ids
                         :values (get shadow-values :shadow)}])

     (when-not (empty? blur-ids)
       [:& blur-menu {:type type :ids blur-ids :values blur-values}])

     (when-not (empty? exports-ids)
       [:& exports-menu {:type type :ids exports-ids :values exports-values :page-id page-id :file-id file-id}])]))
