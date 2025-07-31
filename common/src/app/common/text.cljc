;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.text
  "Legacy editor helpers (draftjs).

  NOTE: this namespace should be not used for new code related to texts"
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.transit :as t]
   [app.common.types.text :as types.text]
   [cuerdas.core :as str]))

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

    {:blocks (reduce #(conj %1 (build-block %2)) [] (types.text/node-seq #(= (:type %) "paragraph") root))
     :entityMap {}}))

(defn content->text+styles
  "Given a root node of a text content extracts the texts with its associated styles"
  [node]
  (letfn
   [(rec-style-text-map [acc node style]
      (let [node-style (merge style (select-keys node types.text/text-all-attrs))
            head (or (-> acc first) [{} ""])
            [head-style head-text] head

            new-acc
            (cond
              (not (types.text/is-text-node? node))
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
