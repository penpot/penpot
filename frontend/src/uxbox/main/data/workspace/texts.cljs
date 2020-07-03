;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.data.workspace.texts
  (:require
   ["slate" :as slate :refer [Editor Node Transforms Text]]
   ["slate-react" :as rslate]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [clojure.walk :as walk]
   [goog.object :as gobj]
   [potok.core :as ptk]
   [uxbox.common.geom.shapes :as geom]
   [uxbox.main.data.workspace.common :as dwc]
   [uxbox.main.fonts :as fonts]
   [uxbox.util.object :as obj]))

(defn create-editor
  []
  (rslate/withReact (slate/createEditor)))

(defn assign-editor
  [id editor]
  (ptk/reify ::assign-editor
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc-in [:workspace-local :editors id] editor)
          (update-in [:workspace-local :editor-n] (fnil inc 0))))))

;; --- Helpers

(defn- calculate-full-selection
  [editor]
  (let [children   (obj/get editor "children")
        paragraphs (obj/get-in children [0 "children" 0 "children"])
        lastp      (aget paragraphs (dec (alength paragraphs)))
        lastptxt   (.string Node lastp)]
    #js {:anchor #js {:path #js [0 0 0]
                      :offset 0}
         :focus #js {:path #js [0 0 (dec (alength paragraphs))]
                     :offset (alength lastptxt)}}))

(defn- editor-select-all!
  [editor]
  (let [children   (obj/get editor "children")
        paragraphs (obj/get-in children [0 "children" 0 "children"])
        range      (calculate-full-selection editor)]
    (.select Transforms editor range)))

(defn- editor-set!
  ([editor props]
   (editor-set! editor props #js {}))
  ([editor props options]
   (.setNodes Transforms editor props options)
   editor))

(defn- transform-nodes
  [pred transform data]
  (walk/postwalk
   (fn [item]
     (if (and (map? item) (pred item))
       (transform item)
       item))
   data))

;; --- Editor Related Helpers

(defn- ^boolean is-text-node?
  [node]
  (cond
    (object? node) (.isText Text node)
    (map? node) (string? (:text node))
    (nil? node) false
    :else (throw (ex-info "unexpected type" {:node node}))))

(defn- ^boolean is-paragraph-node?
  [node]
  (cond
    (object? node) (= (.-type node) "paragraph")
    (map? node) (= "paragraph" (:type node))
    (nil? node) false
    :else (throw (ex-info "unexpected type" {:node node}))))

(defn- ^boolean is-root-node?
  [node]
  (cond
    (object? node) (= (.-type node) "root")
    (map? node) (= "root" (:type node))
    (nil? node) false
    :else (throw (ex-info "unexpected type" {:node node}))))

(defn- editor-current-values
  [editor pred attrs universal?]
  (let [options #js {:match pred :universal universal?}
        _ (when (nil? (obj/get editor "selection"))
            (obj/set! options "at" (calculate-full-selection editor)))
        result (.nodes Editor editor options)
        match  (ffirst (es6-iterator-seq result))]
    (when (object? match)
      (let [attrs  (clj->js attrs)
            result (areduce attrs i ret #js {}
                            (let [val (obj/get match (aget attrs i))]
                              (if val
                                (obj/set! ret (aget attrs i) val)
                                ret)))]
        (js->clj result :keywordize-keys true)))))

(defn- nodes-seq
  [match? node]
  (->> (tree-seq map? :children node)
       (filter match?)))

(defn- shape-current-values
  [shape pred attrs]
  (let [root  (:content shape)
        nodes (nodes-seq pred root)]
    (geom/get-attrs-multi nodes attrs)))

(defn current-text-values
  [{:keys [editor default attrs shape]}]
  (if editor
    (editor-current-values editor is-text-node? attrs true)
    (shape-current-values shape is-text-node? attrs)))

(defn current-paragraph-values
  [{:keys [editor attrs shape]}]
  (if editor
    (editor-current-values editor is-paragraph-node? attrs false)
    (shape-current-values shape is-paragraph-node? attrs)))

(defn current-root-values
  [{:keys [editor attrs shape]}]
  (if editor
    (editor-current-values editor is-root-node? attrs false)
    (shape-current-values shape is-root-node? attrs)))

(defn- merge-attrs
  [node attrs]
  (reduce-kv (fn [node k v]
               (if (nil? v)
                 (dissoc node k)
                 (assoc node k v)))
             node
             attrs))

(defn impl-update-shape-attrs
  ([shape attrs]
   ;; NOTE: this arity is used in workspace for properly update the
   ;; fill color using colorpalette, then the predicate should be
   ;; defined.
   (impl-update-shape-attrs shape attrs is-text-node?))
  ([{:keys [type content] :as shape} attrs pred]
   (assert (= :text type) "should be shape type")
   (let [merge-attrs #(merge-attrs % attrs)]
     (update shape :content #(transform-nodes pred merge-attrs %)))))

(defn update-attrs
  [{:keys [id editor attrs pred split]
    :or {pred is-text-node?}}]
  (if editor
    (ptk/reify ::update-attrs
      ptk/EffectEvent
      (effect [_ state stream]
        (editor-set! editor (clj->js attrs) #js {:match pred :split split})))

    (ptk/reify ::update-attrs
      ptk/WatchEvent
      (watch [_ state stream]
        (rx/of (dwc/update-shapes [id] #(impl-update-shape-attrs % attrs pred)))))))

(defn update-text-attrs
  [options]
  (update-attrs (assoc options :pred is-text-node? :split true)))

(defn update-paragraph-attrs
  [options]
  (update-attrs (assoc options :pred is-paragraph-node? :split false)))

(defn update-root-attrs
  [options]
  (update-attrs (assoc options :pred is-root-node? :split false)))
