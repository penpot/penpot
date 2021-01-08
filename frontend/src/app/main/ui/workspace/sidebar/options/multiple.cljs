;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.workspace.sidebar.options.multiple
  (:require
   [app.common.data :as d]
   [rumext.alpha :as mf]
   [app.common.attrs :as attrs]
   [app.util.text :as ut]
   [app.main.ui.workspace.sidebar.options.measures :refer [measure-attrs measures-menu]]
   [app.main.ui.workspace.sidebar.options.fill :refer [fill-attrs fill-menu]]
   [app.main.ui.workspace.sidebar.options.shadow :refer [shadow-attrs shadow-menu]]
   [app.main.ui.workspace.sidebar.options.blur :refer [blur-attrs blur-menu]]
   [app.main.ui.workspace.sidebar.options.stroke :refer [stroke-attrs stroke-menu]]
   [app.main.ui.workspace.sidebar.options.text :as ot]))

;; We define a map that goes from type to
;; attribute and how to handle them
(def type->props
  {:frame
   {:measure :shape
    :fill    :shape
    :shadow  :children
    :blur    :children
    :stroke  :children
    :text    :children}

   :group
   {:measure :shape
    :fill    :children
    :shadow  :shape
    :blur    :shape
    :stroke  :children
    :text    :children}

   :path
   {:measure :shape
    :fill    :shape
    :shadow  :shape
    :blur    :shape
    :stroke  :shape
    :text    :ignore}

   :text
   {:measure :shape
    :fill    :text
    :shadow  :shape
    :blur    :shape
    :stroke  :ignore
    :text    :text}

   :image
   {:measure :shape
    :fill    :ignore
    :shadow  :shape
    :blur    :shape
    :stroke  :ignore
    :text    :ignore}

   :rect
   {:measure :shape
    :fill    :shape
    :shadow  :shape
    :blur    :shape
    :stroke  :shape
    :text    :ignore}

   :circle
   {:measure :shape
    :fill    :shape
    :shadow  :shape
    :blur    :shape
    :stroke  :shape
    :text    :ignore}

   :svg-raw
   {:measure :shape
    :fill    :shape
    :shadow  :shape
    :blur    :shape
    :stroke  :shape
    :text    :ignore}})

(def props->attrs
  {:measure measure-attrs
   :fill    fill-attrs
   :shadow  shadow-attrs
   :blur    blur-attrs
   :stroke  stroke-attrs
   :text    ot/text-attrs})

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

(defn get-attrs
  "Given a `type` of options that we want to extract and the shapes to extract them from
  returns a list of tuples [id, values] with the extracted properties for the shapes that
  applies (some of them ignore some attributes)"
  [shapes objects attr-type]
  (let [attrs (props->attrs attr-type)
        merge-attrs
        (fn [v1 v2]
          (cond
            (= attr-type :shadow) (attrs/get-attrs-multi [v1 v2] attrs shadow-eq shadow-sel)
            (= attr-type :blur)   (attrs/get-attrs-multi [v1 v2] attrs blur-eq blur-sel)
            :else                 (attrs/get-attrs-multi [v1 v2] attrs)))

        extract-attrs
        (fn [[ids values] {:keys [id type shapes content] :as shape}]
          (let [conj (fnil conj [])
                props (get-in type->props [type attr-type])
                result (case props
                         :ignore   [ids values]
                         :shape    [(conj ids id)
                                    (merge-attrs values (select-keys shape attrs))]
                         :text     [(conj ids id)
                                    (merge-attrs values (ut/get-text-attrs-multi content attrs))]
                         :children (let [children (->> (:shapes shape []) (map #(get objects %)))]
                                     (get-attrs children objects attr-type)))]
            result))]
    (reduce extract-attrs [] shapes)))

(mf/defc options
  {::mf/wrap [mf/memo]
   ::mf/wrap-props false}
  [props]
  (let [shapes (unchecked-get props "shapes")
        shapes-with-children (unchecked-get props "shapes-with-children")
        objects (->> shapes-with-children (group-by :id) (d/mapm (fn [_ v] (first v))))

        type :multiple
        [measure-ids measure-values] (get-attrs shapes objects :measure)
        [fill-ids    fill-values]    (get-attrs shapes objects :fill)
        [shadow-ids  shadow-values]  (get-attrs shapes objects :shadow)
        [blur-ids    blur-values]    (get-attrs shapes objects :blur)
        [stroke-ids  stroke-values]  (get-attrs shapes objects :stroke)
        [text-ids    text-values]    (get-attrs shapes objects :text)]

    [:div.options
     (when-not (empty? measure-ids)
       [:& measures-menu {:type type :ids measure-ids :values measure-values}])

     (when-not (empty? fill-ids)
       [:& fill-menu {:type type :ids fill-ids :values fill-values}])

     (when-not (empty? shadow-ids)
       [:& shadow-menu {:type type :ids shadow-ids :values shadow-values}])

     (when-not (empty? blur-ids)
       [:& blur-menu {:type type :ids blur-ids :values blur-values}])

     (when-not (empty? stroke-ids)
       [:& stroke-menu {:type type :ids stroke-ids :values stroke-values}])

     (when-not (empty? text-ids)
       [:& ot/text-menu {:type type :ids text-ids :values text-values}])]))
