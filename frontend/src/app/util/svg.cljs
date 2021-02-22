;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020-2021 UXBOX Labs SL

(ns app.util.svg
  (:require
   [app.common.uuid :as uuid]
   [app.common.data :as cd]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [cuerdas.core :as str]))

(defonce replace-regex #"#([^\W]+)")

(defn extract-ids [val]
  (->> (re-seq replace-regex val)
       (mapv second)))

(defn clean-attrs
  "Transforms attributes to their react equivalent"
  [attrs]
  (letfn [(transform-key [key]
            (-> (name key)
                (str/replace ":" "-")
                (str/camel)
                (keyword)))

          (format-styles [style-str]
            (->> (str/split style-str ";")
                 (map str/trim)
                 (map #(str/split % ":"))
                 (group-by first)
                 (map (fn [[key val]]
                        (vector
                         (transform-key key)
                         (second (first val)))))
                 (into {})))

          (map-fn [[key val]]
            (let [key (keyword key)]
              (cond
                (= key :class) [:className val]
                (and (= key :style) (string? val)) [key (format-styles val)]
                :else (vector (transform-key key) val))))]

    (->> attrs
         (map map-fn)
         (into {}))))

(defn update-attr-ids
  "Replaces the ids inside a property"
  [attrs replace-fn]
  (letfn [(update-ids [key val]
            (cond
              (map? val)
              (cd/mapm update-ids val)

              (= key :id)
              (replace-fn val)

              :else
              (let [replace-id
                    (fn [result it]
                      (str/replace result it (replace-fn it)))]
                (reduce replace-id val (extract-ids val)))))]
    (cd/mapm update-ids attrs)))

(defn replace-attrs-ids
  "Replaces the ids inside a property"
  [attrs ids-mapping]
  (if (and ids-mapping (not (empty? ids-mapping)))
    (update-attr-ids attrs (fn [id] (get ids-mapping id id)))
    ;; Ids-mapping is null
    attrs))

(defn generate-id-mapping [content]
  (letfn [(visit-node [result node]
            (let [element-id (get-in node [:attrs :id])
                  result (cond-> result
                           element-id (assoc element-id (str (uuid/next))))]
              (reduce visit-node result (:content node))))]
    (visit-node {} content)))

(defn extract-defs [{:keys [tag content] :as node}]
  
  (if-not (map? node)
    [{} node]
    (letfn [(def-tag? [{:keys [tag]}] (= tag :defs))

            (assoc-node [result node]
              (assoc result (-> node :attrs :id) node))

            (node-data [node]
              (->> (:content node) (reduce assoc-node {})))]

      (let [current-def (->> content
                             (filterv def-tag?)
                             (map node-data)
                             (reduce merge))
            result      (->> content
                             (filter (comp not def-tag?))
                             (map extract-defs))

            current-def (->> result (map first) (reduce merge current-def))
            content     (->> result (mapv second))]

        [current-def (assoc node :content content)]))))

(defn find-attr-references [attrs]
  (->> attrs
       (mapcat (fn [[_ attr-value]] (extract-ids attr-value)))))

(defn find-node-references [node]
  (let [current (->> (find-attr-references (:attrs node)) (into #{}))
        children (->> (:content node) (map find-node-references) (flatten) (into #{}))]
    (-> (cd/concat current children)
        (vec))))

(defn find-def-references [defs references]
  (loop [result (into #{} references)
         checked? #{}
         to-check (first references)
         pending (rest references)]

    (cond
      (nil? to-check)
      result
      
      (checked? to-check)
      (recur result
             checked?
             (first pending)
             (rest pending))

      :else
      (let [node (get defs to-check)
            new-refs (find-node-references node)]
        (recur (cd/concat result new-refs)
               (conj checked? to-check)
               (first pending)
               (rest pending))))))

(defn svg-transform-matrix [shape]
  (if (:svg-viewbox shape)
    (let [{svg-x :x
           svg-y :y
           svg-width :width
           svg-height :height} (:svg-viewbox shape)
          {:keys [x y width height]} (:selrect shape)

          scale-x (/ width svg-width)
          scale-y (/ height svg-height)]
      
      (gmt/multiply
       (gmt/matrix)
       (gsh/transform-matrix shape)
       (gmt/translate-matrix (gpt/point (- x (* scale-x svg-x)) (- y (* scale-y svg-y))))
       (gmt/scale-matrix (gpt/point scale-x scale-y))))

    ;; :else
    (gmt/matrix)))

