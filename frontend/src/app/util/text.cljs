(ns app.util.text
  (:require
   [cuerdas.core :as str]
   [app.common.attrs :refer [get-attrs-multi]]))

(defonce default-text-attrs
  {:typography-ref-file nil
   :typography-ref-id nil
   :font-id "sourcesanspro"
   :font-family "sourcesanspro"
   :font-variant-id "regular"
   :font-size "14"
   :font-weight "400"
   :font-style "normal"
   :line-height "1.2"
   :letter-spacing "0"
   :text-transform "none"
   :text-align "left"
   :text-decoration "none"
   :fill-color nil
   :fill-opacity 1})

(def typography-fields
  [:font-id
   :font-family
   :font-variant-id
   :font-size
   :font-weight
   :font-style
   :line-height
   :letter-spacing
   :text-transform])

(def default-typography
  (merge
   {:name "Source Sans Pro Regular"}
   (select-keys default-text-attrs typography-fields)))

(defn some-node
  [predicate node]
  (or (predicate node)
      (some #(some-node predicate %) (:children node))))

(defn map-node
  [map-fn node]
  (cond-> (map-fn node)
    (:children node) (update :children (fn [children] (mapv #(map-node map-fn %) children)))))

(defn content->text
  [node]
  (str
   (if (:children node)
     (str/join (if (= "paragraph-set" (:type node)) "\n" "") (map content->text (:children node)))
     (:text node ""))))

(defn parse-style-text-blocks
  [node attrs]
  (letfn
      [(rec-style-text-map [acc node style]
         (let [node-style (merge style (select-keys node attrs))
               head (or (-> acc first) [{} ""])
               [head-style head-text] head

               new-acc
               (cond
                 (:children node)
                 (reduce #(rec-style-text-map %1 %2 node-style) acc (:children node))

                 (not= head-style node-style)
                 (cons [node-style (:text node "")] acc)

                 :else
                 (cons [node-style (str head-text "" (:text node))] (rest acc)))

               ;; We add an end-of-line when finish a paragraph
               new-acc
               (if (= (:type node) "paragraph")
                 (let [[hs ht] (first new-acc)]
                   (cons [hs (str ht "\n")] (rest new-acc)))
                 new-acc)]
           new-acc))]

    (-> (rec-style-text-map [] node {})
        reverse)))

(defn search-text-attrs
  [node attrs]
  (let [rec-fn
        (fn rec-fn [current node]
          (let [current (reduce rec-fn current (:children node []))]
            (merge current
                   (select-keys node attrs))))]
    (rec-fn {} node)))


(defn get-text-attrs-multi
  [node attrs]
  (let [rec-fn
        (fn rec-fn [current node]
          (let [current (reduce rec-fn current (:children node []))]
            (get-attrs-multi [current node] attrs)))]
    (merge (select-keys default-text-attrs attrs)
           (rec-fn {} node))))

