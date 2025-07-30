;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.text
  (:require
   [app.common.colors :as clr]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.transit :as t]
   [clojure.walk :as walk]
   [cuerdas.core :as str]))

;; -- Attrs

(def text-typography-attrs
  [:typography-ref-id
   :typography-ref-file])

(def text-fill-attrs
  [:fill-color
   :fill-opacity
   :fill-color-ref-id
   :fill-color-ref-file
   :fill-color-gradient])

(def text-font-attrs
  [:font-id
   :font-family
   :font-variant-id
   :font-size
   :font-weight
   :font-style])

(def text-align-attrs
  [:text-align])

(def text-direction-attrs
  [:text-direction])

(def text-spacing-attrs
  [:line-height
   :letter-spacing])

(def text-valign-attrs
  [:vertical-align])

(def text-decoration-attrs
  [:text-decoration])

(def text-transform-attrs
  [:text-transform])

(def text-fills
  [:fills])

(def shape-attrs
  [:grow-type])

(def root-attrs
  text-valign-attrs)

(def paragraph-attrs
  (d/concat-vec
   text-align-attrs
   text-direction-attrs))

(def text-node-attrs
  (d/concat-vec
   text-typography-attrs
   text-font-attrs
   text-spacing-attrs
   text-decoration-attrs
   text-transform-attrs
   text-fills))

(def text-all-attrs (d/concat-set shape-attrs root-attrs paragraph-attrs text-node-attrs))

(def text-style-attrs
  (d/concat-vec root-attrs paragraph-attrs text-node-attrs))

(def default-root-attrs
  {:vertical-align "top"})

(def default-text-attrs
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
   :text-direction "ltr"
   :fills [{:fill-color clr/black
            :fill-opacity 1}]})

(def default-attrs
  (merge default-root-attrs default-text-attrs))

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

(defn node-seq
  ([root] (node-seq identity root))
  ([match? root]
   (->> (tree-seq map? :children root)
        (filter match?)
        (seq))))

(defn is-text-node?
  [node]
  (and (nil? (:type node))
       (string? (:text node))))

(defn is-paragraph-set-node?
  [node]
  (= "paragraph-set" (:type node)))

(defn is-paragraph-node?
  [node]
  (= "paragraph" (:type node)))

(defn is-root-node?
  [node]
  (= "root" (:type node)))

(defn is-node?
  [node]
  (or ^boolean (is-text-node? node)
      ^boolean (is-paragraph-node? node)
      ^boolean (is-paragraph-set-node? node)
      ^boolean (is-root-node? node)))

(defn is-content-node?
  "Only matches content nodes, ignoring the paragraph-set nodes."
  [node]
  (or ^boolean (is-text-node? node)
      ^boolean (is-paragraph-node? node)
      ^boolean (is-root-node? node)))

(defn transform-nodes
  ([transform root]
   (transform-nodes identity transform root))
  ([pred transform root]
   (walk/postwalk
    (fn [item]
      (if (and (is-node? item) (pred item))
        (transform item)
        item))
    root)))

(defn update-text-content
  [shape pred-fn update-fn attrs]
  (let [update-attrs-fn #(update-fn % attrs)
        transform   #(transform-nodes pred-fn update-attrs-fn %)]
    (-> shape
        (update :content transform))))

(defn generate-shape-name
  [text]
  (subs text 0 (min 280 (count text))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; DraftJS <-> Penpot Conversion
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn encode-style-value
  [v]
  (t/encode-str v))

(defn decode-style-value
  [v]
  (t/decode-str v))

(defn encode-style
  [key val]
  (let [k (d/name key)
        v (encode-style-value val)]
    (str "PENPOT$$$" k "$$$" v)))

(defn decode-style
  [style]
  (let [[_ k v] (str/split style "$$$" 3)]
    [(keyword k) (decode-style-value v)]))

(defn attrs-to-styles
  [attrs]
  (reduce-kv (fn [res k v]
               (conj res (encode-style k v)))
             #{}
             attrs))

(defn styles-to-attrs
  [styles]
  (persistent!
   (reduce (fn [result style]
             (if (str/starts-with? style "PENPOT")
               (if (= style "PENPOT_SELECTION")
                 (assoc! result :penpot-selection true)
                 (let [[_ k v] (str/split style "$$$" 3)]
                   (assoc! result (keyword k) (decode-style-value v))))
               result))
           (transient {})
           (seq styles))))

(defn- parse-draft-styles
  "Parses draft-js style ranges, converting encoded style name into a
  key/val pair of data."
  [styles]
  (->> styles
       (filter #(str/starts-with? (get % :style) "PENPOT$$$"))
       (map (fn [item]
              (let [[_ k v] (-> (get item :style)
                                (str/split "$$$" 3))]
                {:key (keyword k)
                 :val (decode-style-value v)
                 :offset (get item :offset)
                 :length (get item :length)})))))

(defn- build-style-index
  "Generates a character based index with associated styles map."
  [length ranges]
  (loop [result (->> (range length)
                     (mapv (constantly {}))
                     (transient))
         ranges (seq ranges)]
    (if-let [{:keys [offset length] :as item} (first ranges)]
      (recur (reduce (fn [result index]
                       (let [prev (get result index)]
                         (assoc! result index (assoc prev (:key item) (:val item)))))
                     result
                     (range offset (+ offset length)))
             (rest ranges))
      (persistent! result))))

(defn- text->code-points
  [text]
  #?(:cljs (into [] (js/Array.from text))
     :clj  (into [] (iterator-seq (.iterator (.codePoints ^String text))))))

(defn- code-points->text
  [cpoints start end]
  #?(:cljs (apply str (subvec cpoints start end))
     :clj  (let [sb (StringBuilder. (- ^long end ^long start))]
             (run! #(.appendCodePoint sb (int %)) (subvec cpoints start end))
             (.toString sb))))

(defn- fix-gradients
  "Conversion from draft doesn't convert correctly the fills gradient types. This
  function change the type from string to keyword of the gradient type"
  [data]
  (letfn [(fix-type [type]
            (cond-> type
              (string? type) keyword))

          (update-fill [fill]
            (d/update-in-when fill [:fill-color-gradient :type] fix-type))

          (update-all-fills [fills]
            (mapv update-fill fills))]
    (d/update-when data :fills update-all-fills)))

(defn convert-from-draft
  [content]
  (letfn [(extract-text [cpoints part]
            (let [start (ffirst part)
                  end   (inc (first (last part)))
                  text  (code-points->text cpoints start end)
                  attrs (second (first part))]
              (-> attrs
                  (fix-gradients)
                  (assoc :text text))))

          (split-texts [text styles data]
            (let [cpoints  (text->code-points text)
                  children (->> (parse-draft-styles styles)
                                (build-style-index (count cpoints))
                                (d/enumerate)
                                (partition-by second)
                                (mapv #(extract-text cpoints %)))]
              (cond-> children
                (empty? children)
                (conj (assoc data :text "")))))

          (build-paragraph [block]
            (let [key    (get block :key)
                  text   (get block :text)
                  styles (get block :inlineStyleRanges)
                  data   (->> (get block :data)
                              fix-gradients)]
              (-> data
                  (assoc :key key)
                  (assoc :type "paragraph")
                  (assoc :children (split-texts text styles data)))))]

    {:type "root"
     :children
     [{:type "paragraph-set"
       :children (->> (get content :blocks)
                      (mapv build-paragraph))}]}))

(defn convert-to-draft
  [root]
  (letfn [(process-attr [children ranges [k v]]
            (loop [children (seq children)
                   start    nil
                   offset   0
                   ranges   ranges]
              (if-let [{:keys [text] :as item} (first children)]
                (let [cpoints (text->code-points text)]
                  (if (= v (get item k ::novalue))
                    (recur (rest children)
                           (if (nil? start) offset start)
                           (+ offset (count cpoints))
                           ranges)
                    (if (some? start)
                      (recur (rest children)
                             nil
                             (+ offset (count cpoints))
                             (conj! ranges {:offset start
                                            :length (- offset start)
                                            :style (encode-style k v)}))
                      (recur (rest children)
                             start
                             (+ offset (count cpoints))
                             ranges))))
                (cond-> ranges
                  (some? start)
                  (conj! {:offset start
                          :length (- offset start)
                          :style (encode-style k v)})))))

          (calc-ranges [{:keys [children] :as blok}]
            (let [xform (comp (map #(dissoc % :key :text))
                              (remove empty?)
                              (mapcat vec)
                              (distinct))
                  f  #(process-attr children %1 %2)]
              (persistent!
               (transduce xform (completing f) (transient []) children))))

          (build-block [{:keys [key children] :as paragraph}]
            {:key key
             :depth 0
             :text (apply str (map :text children))
             :data (dissoc paragraph :key :children :type)
             :type "unstyled"
             :entityRanges []
             :inlineStyleRanges (calc-ranges paragraph)})]

    {:blocks (reduce #(conj %1 (build-block %2)) [] (node-seq #(= (:type %) "paragraph") root))
     :entityMap {}}))

(defn content->text+styles
  "Given a root node of a text content extracts the texts with its associated styles"
  [node]
  (letfn
   [(rec-style-text-map [acc node style]
      (let [node-style (merge style (select-keys node text-all-attrs))
            head (or (-> acc first) [{} ""])
            [head-style head-text] head

            new-acc
            (cond
              (not (is-text-node? node))
              (reduce #(rec-style-text-map %1 %2 node-style) acc (:children node))

              (not= head-style node-style)
              (cons [node-style (:text node "")] acc)

              :else
              (cons [node-style (dm/str head-text "" (:text node))] (rest acc)))

               ;; We add an end-of-line when finish a paragraph
            new-acc
            (if (= (:type node) "paragraph")
              (let [[hs ht] (first new-acc)]
                (cons [hs (dm/str ht "\n")] (rest new-acc)))
              new-acc)]
        new-acc))]

    (-> (rec-style-text-map [] node {})
        reverse)))

(defn content-range->text+styles
  "Given a root node of a text content extracts the texts with its associated styles"
  [node start end]
  (let [sss (content->text+styles node)]
    (loop [styles  (seq sss)
           taking? false
           acc      0
           result   []]
      (if styles
        (let [[node-style text] (first styles)
              from      acc
              to        (+ acc (count text))
              taking?   (or taking? (and (<= from start) (< start to)))
              text      (subs text (max 0 (- start acc)) (- end acc))
              result    (cond-> result
                          (and taking? (d/not-empty? text))
                          (conj (assoc node-style :text text)))
              continue? (or (> from end) (>= end to))]
          (recur (when continue? (rest styles)) taking? to result))
        result))))

(defn content->text
  "Given a root node of a text content extracts the texts with its associated styles"
  [content]
  (letfn [(add-node [acc node]
            (cond
              (is-paragraph-node? node)
              (conj acc [])

              (is-text-node? node)
              (let [i (dec (count acc))]
                (update acc i conj (:text node)))

              :else
              acc))]
    (->> (node-seq content)
         (reduce add-node [])
         (map #(str/join "" %))
         (str/join "\n"))))

(defn change-text
  "Changes the content of the text shape to use the text as argument. Will use the styles of the
   first paragraph and text that is present in the shape (and override the rest)"
  [shape text]
  (let [content (:content shape)

        root-styles (select-keys content root-attrs)

        paragraph-style (merge
                         default-text-attrs
                         (select-keys (->> content (node-seq is-paragraph-node?) first) text-all-attrs))
        text-style (merge
                    default-text-attrs
                    (select-keys (->> content (node-seq is-text-node?) first) text-all-attrs))

        paragraph-texts (str/split text "\n")

        paragraphs
        (->> paragraph-texts
             (mapv
              (fn [pt]
                (merge
                 paragraph-style
                 {:type "paragraph"
                  :children [(merge {:text pt} text-style)]}))))

        new-content
        (d/patch-object
         {:type "root"
          :children
          [{:type "paragraph-set"
            :children paragraphs}]}
         root-styles)]

    (assoc shape :content new-content)))

(defn index-content
  "Adds a property `$id` that identifies the current node inside"
  ([content]
   (index-content content nil 0))
  ([node path index]
   (let [cur-path (if path (dm/str path "-") (dm/str ""))
         cur-path (dm/str cur-path (d/name (:type node :text)) "-" index)]
     (-> node
         (assoc :$id cur-path)
         (update :children
                 (fn [children]
                   (->> children
                        (d/enumerate)
                        (mapv (fn [[idx node]]
                                (index-content node cur-path idx))))))))))
