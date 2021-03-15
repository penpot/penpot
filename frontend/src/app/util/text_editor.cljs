;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020-2021 UXBOX Labs SL

(ns app.util.text-editor
  "Draft related abstraction functions."
  (:require
   ["draft-js" :as draft]
   [app.common.attrs :as attrs]
   [app.common.text :as txt]
   [app.common.data :as d]
   [app.util.transit :as t]
   [app.util.array :as arr]
   [app.util.object :as obj]
   [clojure.walk :as walk]
   [cuerdas.core :as str]))

;; --- INLINE STYLES ENCODING

(defn encode-style-value
  [v]
  (cond
    (string? v)  (str "s:" v)
    (number? v)  (str "n:" v)
    (keyword? v) (str "k:" (name v))
    (map? v)     (str "m:" (t/encode v))

    :else (str "o:" v)))

(defn decode-style-value
  [v]
  (let [prefix (subs v 0 2)]
    (case prefix
      "s:" (subs v 2)
      "n:" (js/Number (subs v 2))
      "k:" (keyword (subs v 2))
      "m:" (t/decode (subs v 2))
      "o:" (subs v 2)
      v)))

(defn encode-style
  [key val]
  (let [k (d/name key)
        v (encode-style-value val)]
    (str "PENPOT$$$" k "$$$" v)))

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
             (let [[_ k v] (str/split style "$$$" 3)]
               (assoc! result (keyword k) (decode-style-value v))))
           (transient {})
           (seq styles))))

;; --- CONVERSION

(defn- parse-draft-styles
  "Parses draft-js style ranges, converting encoded style name into a
  key/val pair of data."
  [styles]
  (map (fn [item]
         (let [[_ k v] (-> (obj/get item "style")
                           (str/split "$$$" 3))]
           {:key (keyword k)
            :val (decode-style-value v)
            :offset (obj/get item "offset")
            :length (obj/get item "length")}))
       styles))

(defn- build-style-index
  "Generates a character based index with associated styles map."
  [text ranges]
  (loop [result (->> (range (count text))
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

(defn- convert-from-draft
  [content]
  (letfn [(build-text [text part]
            (let [start (ffirst part)
                  end   (inc (first (last part)))]
              (-> (second (first part))
                  (assoc :text (subs text start end)))))

          (split-texts [text styles]
            (->> (parse-draft-styles styles)
                 (build-style-index text)
                 (d/enumerate)
                 (partition-by second)
                 (mapv #(build-text text %))))

          (build-paragraph [block]
            (let [key    (obj/get block "key")
                  text   (obj/get block "text")
                  styles (obj/get block "inlineStyleRanges")
                  data   (obj/get block "data")]
              (-> (js->clj data :keywordize-keys true)
                  (assoc :key key)
                  (assoc :type "paragraph")
                  (assoc :children (split-texts text styles)))))]

    {:type "root"
     :children
     [{:type "paragraph-set"
       :children (->> (obj/get content "blocks")
                      (mapv build-paragraph))}]}))

(defn- convert-to-draft
  [root]
  (letfn [(process-attr [children ranges [k v]]
            (loop [children (seq children)
                   start    nil
                   offset   0
                   ranges   ranges]
              (if-let [{:keys [text] :as item} (first children)]
                (if (= v (get item k ::novalue))
                  (recur (rest children)
                         (if (nil? start) offset start)
                         (+ offset (alength text))
                         ranges)
                  (if (some? start)
                    (recur (rest children)
                           nil
                           (+ offset (alength text))
                           (arr/conj! ranges #js {:offset start
                                                  :length (- offset start)
                                                  :style (encode-style k v)}))
                    (recur (rest children)
                           start
                           (+ offset (alength text))
                           ranges)))
                (cond-> ranges
                  (some? start)
                  (arr/conj! #js {:offset start
                                  :length (- offset start)
                                  :style (encode-style k v)})))))

          (calc-ranges [{:keys [children] :as blok}]
            (let [xform (comp (map #(dissoc % :key :text))
                              (remove empty?)
                              (mapcat vec)
                              (distinct))
                  proc  #(process-attr children %1 %2)]
              (transduce xform proc #js [] children)))

          (build-block [result {:keys [key children] :as paragraph}]
            (->> #js {:key key
                      :depth 0
                      :text (apply str (map :text children))
                      :data (-> (dissoc paragraph :key :children :type)
                                (clj->js))
                      :type "unstyled"
                      :entityRanges #js []
                      :inlineStyleRanges (calc-ranges paragraph)}
                 (arr/conj! result)))]

    #js {:blocks (reduce build-block #js [] (txt/node-seq #(= (:type %) "paragraph") root))
         :entityMap #js {}}))

(defn immutable-map->map
  [obj]
  (into {} (map (fn [[k v]] [(keyword k) v])) (seq obj)))


;; --- DRAFT-JS HELPERS

(defn create-editor-state
  ([]
   (.createEmpty ^js draft/EditorState))
  ([content]
   (if (some? content)
     (.createWithContent ^js draft/EditorState content)
     (.createEmpty ^js draft/EditorState))))

(defn import-content
  [content]
  (-> content convert-to-draft draft/convertFromRaw))

(defn export-content
  [content]
  (-> content
      (draft/convertToRaw)
      (convert-from-draft)))

(defn get-editor-current-content
  [state]
  (.getCurrentContent ^js state))

(defn ^boolean content-has-text?
  [content]
  (.hasText ^js content))

(defn editor-select-all
  [state]
  (let [content   (get-editor-current-content state)
        fblock    (.. ^js content getBlockMap first)
        lblock    (.. ^js content getBlockMap last)
        fbk       (.getKey ^js fblock)
        lbk       (.getKey ^js lblock)
        lbl       (.getLength ^js lblock)
        params    #js {:anchorKey fbk
                       :anchorOffset 0
                       :focusKey lbk
                       :focusOffset lbl}
        selection (draft/SelectionState. params)]
    (.forceSelection ^js draft/EditorState state selection)))

(defn get-editor-block-data
  [block]
  (-> (.getData ^js block)
      (immutable-map->map)))

(defn get-editor-block-type
  [block]
  (.getType ^js block))

(defn get-editor-current-block-data
  [state]
  (let [content (.getCurrentContent ^js state)
        key     (.. ^js state getSelection getStartKey)
        block   (.getBlockForKey ^js content key)]
    (get-editor-block-data block)))

(defn get-editor-current-inline-styles
  [state]
  (-> (.getCurrentInlineStyle ^js state)
      (styles-to-attrs)))

(defn update-editor-current-block-data
  [state attrs]
  (loop [selection (.getSelection ^js state)
         start-key (.getStartKey ^js selection)
         end-key   (.getEndKey ^js selection)
         content   (.getCurrentContent ^js state)
         target    selection]
    (if (and (not= start-key end-key)
             (zero? (.getEndOffset ^js selection)))
      (let [before-block (.getBlockBefore ^js content end-key)]
        (recur selection
               start-key
               (.getKey ^js before-block)
               content
               (.merge ^js target
                       #js {:anchorKey start-key
                            :anchorOffset (.getStartOffset ^js selection)
                            :focusKey end-key
                            :focusOffset (.getLength ^js before-block)
                            :isBackward false})))
      (.push ^js draft/EditorState
             state
             (.mergeBlockData ^js draft/Modifier content target (clj->js attrs))
             "change-block-data"))))

(defn update-editor-current-inline-styles
  [state attrs]
  (let [selection (.getSelection ^js state)
        content   (.getCurrentContent ^js state)
        styles    (attrs-to-styles attrs)]
    (reduce (fn [state style]
              (let [modifier (.applyInlineStyle draft/Modifier
                                                (.getCurrentContent ^js state)
                                                selection
                                                style)]
                (.push draft/EditorState state modifier "change-inline-style")))
            state
            styles)))

(defn editor-split-block
  [state]
  (let [content    (.getCurrentContent ^js state)
        selection  (.getSelection ^js state)
        content    (.splitBlock ^js draft/Modifier content selection)
        block-data (.. ^js content -blockMap (get (.. content -selectionBefore getStartKey)) getData)
        block-key  (.. ^js content -selectionAfter getStartKey)
        block-map  (.. ^js content -blockMap (update block-key (fn [block] (.set ^js block "data" block-data))))]
    (.push ^js draft/EditorState state (.set ^js content "blockMap" block-map) "split-block")))
