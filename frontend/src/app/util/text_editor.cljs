;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.util.text-editor
  "Draft related abstraction functions."
  (:require
   ["./text_editor_impl.js" :as impl]
   ["draft-js" :as draft]
   [app.common.attrs :as attrs]
   [app.common.data :as d]
   [app.common.text :as txt]
   [app.common.uuid :as uuid]
   [app.util.array :as arr]
   [app.util.object :as obj]
   [app.util.transit :as t]
   [clojure.walk :as walk]
   [cuerdas.core :as str]))

;; --- INLINE STYLES ENCODING

(defn encode-style-value
  [v]
  (cond
    (uuid? v)    (str "u:" v)
    (string? v)  (str "s:" v)
    (number? v)  (str "n:" v)
    (keyword? v) (str "k:" (name v))
    (map? v)     (str "m:" (t/encode v))
    (nil? v)     (str "z:null")
    :else (str "o:" v)))

(defn decode-style-value
  [v]
  (let [prefix (subs v 0 2)]
    (case prefix
      "s:" (subs v 2)
      "n:" (js/Number (subs v 2))
      "k:" (keyword (subs v 2))
      "m:" (t/decode (subs v 2))
      "u:" (uuid/uuid (subs v 2))
      "z:" nil
      "o:" (subs v 2)
      v)))

(defn encode-style
  [key val]
  (let [k (d/name key)
        v (encode-style-value val)]
    (str "PENPOT$$$" k "$$$" v)))

(defn encode-style-prefix
  [key]
  (let [k (d/name key)]
    (str "PENPOT$$$" k "$$$")))

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

;; --- CONVERSION

(defn- parse-draft-styles
  "Parses draft-js style ranges, converting encoded style name into a
  key/val pair of data."
  [styles]
  (->> styles
       (filter #(str/starts-with? (obj/get % "style") "PENPOT$$$"))
       (map (fn [item]
              (let [[_ k v] (-> (obj/get item "style")
                                (str/split "$$$" 3))]
                {:key (keyword k)
                 :val (decode-style-value v)
                 :offset (obj/get item "offset")
                 :length (obj/get item "length")})))))

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
            (let [children (->> (parse-draft-styles styles)
                                (build-style-index text)
                                (d/enumerate)
                                (partition-by second)
                                (mapv #(build-text text %)))]
              (cond-> children
                (empty? children)
                (conj {:text ""}))))

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
   (impl/createEditorState nil nil))
  ([content]
   (impl/createEditorState content nil))
  ([content decorator]
   (impl/createEditorState content decorator)))

(defn create-decorator
  [type component]
  (impl/createDecorator type component))

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
  (impl/selectAll state))

(defn get-editor-block-data
  [block]
  (-> (.getData ^js block)
      (immutable-map->map)))

(defn get-editor-block-type
  [block]
  (.getType ^js block))

(defn get-editor-current-block-data
  [state]
  (let [block (impl/getCurrentBlock state)]
    (get-editor-block-data block)))

(defn get-editor-current-inline-styles
  [state]
  (-> (.getCurrentInlineStyle ^js state)
      (styles-to-attrs)))

(defn update-editor-current-block-data
  [state attrs]
  (impl/updateCurrentBlockData state (clj->js attrs)))

(defn update-editor-current-inline-styles
  [state attrs]
  (impl/applyInlineStyle state (attrs-to-styles attrs)))

(defn editor-split-block
  [state]
  (impl/splitBlockPreservingData state))

(defn add-editor-blur-selection
  [state]
  (impl/addBlurSelectionEntity state))

(defn remove-editor-blur-selection
  [state]
  (impl/removeBlurSelectionEntity state))
