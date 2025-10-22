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
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.geom.shapes :as gsh]
   [app.common.types.component :as ctk]
   [app.common.types.path :as path]
   [app.common.types.shape.attrs :refer [editable-attrs]]
   [app.common.types.shape.layout :as ctl]
   [app.common.types.text :as txt]
   [app.common.types.token :as tt]
   [app.common.weak :as weak]
   [app.main.refs :as refs]
   [app.main.ui.workspace.sidebar.options.menus.blur :refer [blur-attrs blur-menu]]
   [app.main.ui.workspace.sidebar.options.menus.color-selection :refer [color-selection-menu*]]
   [app.main.ui.workspace.sidebar.options.menus.component :refer [component-menu*]]
   [app.main.ui.workspace.sidebar.options.menus.constraints :refer [constraint-attrs constraints-menu]]
   [app.main.ui.workspace.sidebar.options.menus.exports :refer [exports-attrs exports-menu*]]
   [app.main.ui.workspace.sidebar.options.menus.fill :as fill]
   [app.main.ui.workspace.sidebar.options.menus.layer :refer [layer-attrs layer-menu*]]
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
   :fill              fill/fill-attrs
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

        merge-attr
        (fn [acc applied-tokens t-attr]
          "Merges a single token attribute (`t-attr`) into the accumulator map.
           - If the attribute is not present, associates it with the new value.
           - If the existing value equals the new value, keeps the accumulator unchanged.
           - If there is a conflict, sets the value to `:multiple`."
          (let [new-val  (get applied-tokens t-attr)
                existing (get acc t-attr ::not-found)]
            (cond
              (= existing ::not-found) (assoc acc t-attr new-val)
              (= existing new-val)     acc
              :else                    (assoc acc t-attr :multiple))))

        merge-shape-attr
        (fn [acc applied-tokens shape-attr]
          "Merges all token attributes derived from a single shape attribute
           into the accumulator map using `merge-attr`."
          (let [token-attrs (tt/shape-attr->token-attrs shape-attr)]
            (reduce #(merge-attr %1 applied-tokens %2) acc token-attrs)))

        merge-token-values
        (fn [acc shape-attrs applied-tokens]
          "Merges token values across all shape attributes.
           For each shape attribute, its corresponding token attributes are merged
           into the accumulator. If applied tokens are empty, the accumulator is returned unchanged."
          (if (seq applied-tokens)
            (reduce #(merge-shape-attr %1 applied-tokens %2) acc shape-attrs)
            acc))

        extract-attrs
        (fn [[ids values token-acc] {:keys [id type applied-tokens] :as shape}]
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
                                    :else (select-keys shape editable-attrs)))
                    new-token-acc (merge-token-values token-acc editable-attrs applied-tokens)]
                [(conj ids id)
                 (merge-attrs values shape-values)
                 new-token-acc])

              :text
              (let [shape-attrs (select-keys shape attrs)

                    content-attrs
                    (attrs/get-text-attrs-multi shape txt/default-text-attrs attrs)

                    new-values
                    (-> values
                        (merge-attrs shape-attrs)
                        (merge-attrs content-attrs))

                    new-token-acc (merge-token-values token-acc content-attrs applied-tokens)]
                [(conj ids id)
                 new-values
                 new-token-acc])

              :children
              (let [children (->> (:shapes shape []) (map #(get objects %)))
                    [new-ids new-values tokens] (get-attrs* children objects attr-group)]
                [(d/concat-vec ids new-ids) (merge-attrs values new-values) tokens])

              [])))]

    (reduce extract-attrs [[] {} {}] shapes)))

(def get-attrs
  (weak/memoize get-attrs*))

(defn- is-bool-descendant?
  [objects selected-shape-ids shape]

  (let [parent-id (:parent-id shape)
        parent    (get objects parent-id)]

    (cond
      (nil? shape)
      false

      ;; if it is one of the selected shapes, it is considerer not a
      ;; bool descendant
      (contains? selected-shape-ids (:id shape))
      false

      (cfh/bool-shape? parent)
      true

      :else
      (recur objects selected-shape-ids parent))))

(defn- check-options-props
  [new-props old-props]
  (and (= (unchecked-get new-props "shapes")
          (unchecked-get old-props "shapes"))
       (= (unchecked-get new-props "shapesWithChildren")
          (unchecked-get old-props "shapesWithChildren"))
       (= (unchecked-get new-props "pageId")
          (unchecked-get old-props "pageId"))
       (= (unchecked-get new-props "fileId")
          (unchecked-get old-props "fileId"))))

(mf/defc options*
  {::mf/wrap [#(mf/memo' % check-options-props)]}
  [{:keys [shapes shapes-with-children page-id file-id libraries] :as props}]
  (let [shape-ids
        (mf/with-memo [shapes]
          (into #{} d/xf:map-id shapes))

        is-layout-child-ref
        (mf/with-memo [shape-ids]
          (refs/is-layout-child? shape-ids))

        is-layout-child?
        (mf/deref is-layout-child-ref)

        is-flex-parent-ref
        (mf/with-memo [shape-ids]
          (refs/flex-layout-child? shape-ids))

        is-flex-parent?
        (mf/deref is-flex-parent-ref)

        is-grid-parent-ref
        (mf/with-memo [shape-ids]
          (refs/grid-layout-child? shape-ids))

        is-grid-parent?
        (mf/deref is-grid-parent-ref)

        has-flex-layout-container?
        (some ctl/flex-layout? shapes)

        all-layout-child-ref
        (mf/with-memo [shape-ids]
          (refs/all-layout-child? shape-ids))

        all-layout-child?
        (mf/deref all-layout-child-ref)

        all-flex-layout-container?
        (mf/with-memo [shapes]
          (every? ctl/flex-layout? shapes))

        show-caps?
        (mf/with-memo [shapes]
          (some #(and (cfh/path-shape? %)
                      (path/shape-with-open-path? %))
                shapes))

        has-text?
        (mf/with-memo [shapes]
          (some cfh/text-shape? shapes))

        objects
        (mf/with-memo [shapes-with-children]
          (let [objects (d/index-by :id shapes-with-children)]
            (reduce-kv (fn [objects id object]
                         (if (is-bool-descendant? objects shape-ids object)
                           (dissoc objects id)
                           objects))
                       objects
                       objects)))

        [layer-ids layer-values]
        (get-attrs shapes objects :layer)

        [text-ids text-values]
        (get-attrs shapes objects :text)

        [constraint-ids constraint-values]
        (get-attrs shapes objects :constraint)

        [fill-ids fill-values fill-tokens]
        (get-attrs shapes objects :fill)

        [shadow-ids shadow-values]
        (get-attrs shapes objects :shadow)

        [blur-ids blur-values]
        (get-attrs shapes objects :blur)

        [stroke-ids stroke-values stroke-tokens]
        (get-attrs shapes objects :stroke)

        [exports-ids exports-values]
        (get-attrs shapes objects :exports)

        [layout-container-ids layout-container-values]
        (get-attrs shapes objects :layout-container)

        [layout-item-ids layout-item-values {}]
        (get-attrs shapes objects :layout-item)

        components
        (mf/with-memo [shapes]
          (not-empty (filter ctk/instance-head? shapes)))

        workspace-modifiers
        (mf/deref refs/workspace-modifiers)

        shapes
        (mf/with-memo [workspace-modifiers shapes]
          (into []
                (map (fn [shape]
                       (let [shape-id  (dm/get-prop shape :id)
                             modifiers (dm/get-in workspace-modifiers [shape-id :modifiers])]
                         (gsh/transform-shape shape modifiers))))
                shapes))

        type :multiple

        ;; NOTE: we only need transformed shapes for the measure menu,
        ;; the rest of menus can live with shapes not transformed; we
        ;; also don't use the memoized version of get-attrs because it
        ;; makes no sense because the shapes object are changed on
        ;; each rerender.
        [measure-ids measure-values measure-tokens]
        (get-attrs* shapes objects :measure)]

    [:div {:class (stl/css :options)}
     (when-not (empty? layer-ids)
       [:> layer-menu* {:type type
                        :ids layer-ids
                        :values layer-values}])

     (when-not (empty? measure-ids)
       [:> measures-menu*
        {:type type
         :ids measure-ids
         :values measure-values
         :applied-tokens measure-tokens
         :shapes shapes}])

     (when (some? components)
       [:> component-menu* {:shapes components}])

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
       [:> fill/fill-menu* {:type type
                            :ids fill-ids
                            :values fill-values
                            :shapes shapes
                            :objects objects
                            :applied-tokens fill-tokens}])

     (when-not (empty? stroke-ids)
       [:& stroke-menu {:type type
                        :ids stroke-ids
                        :show-caps show-caps?
                        :values stroke-values
                        :shapes shapes
                        :objects objects
                        :disable-stroke-style has-text?
                        :applied-tokens stroke-tokens}])

     (when-not (empty? shapes)
       [:> color-selection-menu*
        {:file-id file-id
         :type type
         :shapes (vals objects)
         :libraries libraries}])

     (when-not (empty? shadow-ids)
       [:> shadow-menu* {:type type
                         :ids shadow-ids
                         :values (get shadow-values :shadow)}])

     (when-not (empty? blur-ids)
       [:& blur-menu {:type type :ids blur-ids :values blur-values}])

     (when-not (empty? exports-ids)
       [:> exports-menu* {:type type
                          :ids exports-ids
                          :shapes shapes
                          :values exports-values
                          :page-id page-id
                          :file-id file-id}])]))
