;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.util.text.clipboard
  "Reads clipboard data into paste fragments: `[{:attrs {} :children [{:text :attrs}]}]`,
  whose run attrs override the caret style; Penpot text also carries a hidden payload."
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.fonts :as cfnt]
   [app.common.transit :as t]
   [app.common.types.text :as txt]
   [app.common.types.typography :as ctt]
   [cuerdas.core :as str]))

(def ^:private block-tags
  #{"ADDRESS" "ARTICLE" "ASIDE" "BLOCKQUOTE" "CAPTION" "DD" "DETAILS" "DIV" "DL"
    "DT" "FIELDSET" "FIGCAPTION" "FIGURE" "FOOTER" "FORM" "H1" "H2" "H3" "H4"
    "H5" "H6" "HEADER" "HR" "LI" "MAIN" "NAV" "OL" "P" "PRE" "SECTION"
    "SUMMARY" "TABLE" "TBODY" "TFOOT" "THEAD" "TR" "UL"})

;; Elements whose text is read; any other element is skipped with its children.
;; `O:P` is Word's paragraph filler, which marks its empty lines.
(def ^:private allowed-tags
  (into block-tags
        #{"A" "ABBR" "B" "BDI" "BDO" "BIG" "BR" "CENTER" "CITE" "CODE" "DATA"
          "DEL" "DFN" "EM" "FONT" "I" "INS" "KBD" "LABEL" "MARK" "O:P" "Q" "S"
          "SAMP" "SMALL" "SPAN" "STRIKE" "STRONG" "SUB" "SUP" "TD" "TH" "TIME"
          "TT" "U" "VAR" "WBR"}))

(def ^:private bold-tags #{"B" "STRONG" "TH" "H1" "H2" "H3" "H4" "H5" "H6"})
(def ^:private italic-tags #{"I" "EM" "CITE" "VAR" "DFN"})
(def ^:private underline-tags #{"U" "INS"})
(def ^:private strike-tags #{"S" "STRIKE" "DEL"})
(def ^:private cell-tags #{"TD" "TH"})

(defn- style-value
  [^js element property]
  (some-> (.-style element) (.getPropertyValue property) str/lower))

(defn- parse-bold
  "True/false for a CSS font-weight, nil when it says nothing."
  [weight]
  (cond
    (empty? weight)                nil
    (#{"bold" "bolder"} weight)    true
    (#{"normal" "lighter"} weight) false
    :else (let [n (js/parseInt weight 10)]
            (when-not (js/isNaN n) (>= n 600)))))

(defn- parse-italic
  [font-style]
  (cond
    (empty? font-style)                                                  nil
    (or (= font-style "italic") (str/starts-with? font-style "oblique")) true
    (= font-style "normal")                                              false
    :else                                                                nil))

(defn- display-block?
  [display]
  (some #(str/starts-with? display %) ["block" "list-item" "table" "flex" "grid"]))

(defn- element-state
  "The emphasis state inside `element`, or nil when hidden. Inline styles win over
  tags, so Google Docs' `<b style=\"font-weight:normal\">` wrapper is not bold."
  [state ^js element tag]
  (let [display    (or (style-value element "display") "")
        weight     (parse-bold (style-value element "font-weight"))
        italic     (parse-italic (style-value element "font-style"))
        decoration (str (style-value element "text-decoration-line") " "
                        (style-value element "text-decoration"))
        transform  (style-value element "text-transform")
        space      (style-value element "white-space")]
    (when-not (= display "none")
      (cond-> state
        (contains? bold-tags tag)          (assoc :bold? true)
        (some? weight)                     (assoc :bold? weight)
        (contains? italic-tags tag)        (assoc :italic? true)
        (some? italic)                     (assoc :italic? italic)
        (contains? underline-tags tag)     (assoc :underline? true)
        (str/includes? decoration "underline")    (assoc :underline? true)
        (contains? strike-tags tag)        (assoc :strike? true)
        (str/includes? decoration "line-through") (assoc :strike? true)
        (= tag "A")                        (assoc :link? true)
        (= tag "PRE")                      (assoc :pre? true)
        (seq space)                        (assoc :pre? (str/starts-with? space "pre"))
        (= transform "none")               (dissoc :transform)
        (#{"uppercase" "lowercase" "capitalize"} transform) (assoc :transform transform)))))

(defn- block-element?
  [^js element tag]
  (let [display (or (style-value element "display") "")]
    (if (seq display)
      (display-block? display)
      (contains? block-tags tag))))

(defn- state->attrs
  "Penpot text attrs for an emphasis state, with every attr set so the source
  replaces the caret's emphasis. Link underlines go with the link."
  [{:keys [bold? italic? underline? strike? link? transform]}]
  (let [underline? (and underline? (not link?))]
    {:font-weight     (if bold? "700" "400")
     :font-style      (if italic? "italic" "normal")
     :text-decoration (cond underline? "underline" strike? "line-through" :else "none")
     :text-transform  (or transform "none")}))

;; --- Fragment builder
;;
;; `:runs` is the paragraph being built; `:space?` is true when the next
;; collapsible space would be dropped (paragraph start or after a space).

(def ^:private empty-builder
  {:paragraphs [] :runs [] :space? true})

(defn- trim-trailing-space
  [runs]
  (let [idx (dec (count runs))
        run (get runs idx)]
    (if (and run (not (:pre? run)))
      (update runs idx update :text str/rtrim " ")
      runs)))

(defn- close-paragraph
  [{:keys [runs] :as builder}]
  (-> builder
      (update :paragraphs conj (trim-trailing-space runs))
      (assoc :runs [] :space? true)))

(defn- soft-break
  "Ends the paragraph at a block boundary, unless nothing was written yet."
  [{:keys [runs] :as builder}]
  (if (some #(seq (:text %)) runs)
    (close-paragraph builder)
    (assoc builder :runs [])))

(defn- add-run
  [builder text attrs pre?]
  (update builder :runs conj {:text text :attrs attrs :pre? pre?}))

(defn- add-collapsible-text
  [{:keys [space?] :as builder} text attrs]
  (let [text (cond-> (str/replace text #"[ \t\n\r\f]+" " ")
               space? (str/ltrim " "))]
    (if (empty? text)
      builder
      (-> builder
          (add-run text attrs false)
          (assoc :space? (str/ends-with? text " "))))))

(defn- add-pre-text
  [builder text attrs]
  (let [lines (.split (str/replace text "\r" "") "\n")]
    (reduce (fn [builder [idx line]]
              (cond-> builder
                (pos? idx)   (close-paragraph)
                (seq line)   (add-run line attrs true)
                :always      (assoc :space? false)))
            builder
            (map-indexed vector lines))))

(defn- add-text
  [builder text state]
  (let [attrs (state->attrs state)]
    (if (:pre? state)
      (add-pre-text builder text attrs)
      (add-collapsible-text builder text attrs))))

(defn- walk-node
  [builder ^js node state]
  (case (.-nodeType node)
    3 (add-text builder (.-nodeValue node) state)
    1 (let [tag (str/upper (.-tagName node))]
        (if-not (contains? allowed-tags tag)
          builder
          (if-let [state (element-state state node tag)]
            (if (= tag "BR")
              (close-paragraph builder)
              (let [block?  (block-element? node tag)
                    ;; Cells of a row are joined by a tab.
                    cell?   (and (contains? cell-tags tag)
                                 (some? (.-previousElementSibling node)))
                    builder (cond-> builder
                              block? (soft-break)
                              cell?  (-> (add-run "\t" (state->attrs state) true)
                                         (assoc :space? true)))
                    builder (reduce #(walk-node %1 %2 state)
                                    builder
                                    (array-seq (.-childNodes node)))]
                (cond-> builder block? (soft-break))))
            builder)))
    builder))

;; --- Normalizing

(defn- merge-runs
  "Joins neighbouring runs that share attrs and drops empty ones."
  [runs]
  (reduce (fn [acc {:keys [text attrs]}]
            (let [prev (peek acc)]
              (cond
                (empty? text)              acc
                (= (:attrs prev) attrs)    (conj (pop acc) (update prev :text str text))
                :else                      (conj acc {:text text :attrs attrs}))))
          []
          runs))

(defn- runs->paragraph
  [runs]
  (let [runs (->> runs
                  (map #(update % :text str/replace " " " "))
                  (merge-runs))]
    {:attrs {}
     :children (if (every? #(str/blank? (:text %)) runs) [] runs)}))

(defn- empty-paragraph?
  [paragraph]
  (empty? (:children paragraph)))

(defn- normalize-paragraphs
  "Drops empty paragraphs at both ends and keeps at most one in a row."
  [paragraphs]
  (let [paragraphs (->> paragraphs
                        (drop-while empty-paragraph?)
                        (reverse)
                        (drop-while empty-paragraph?)
                        (reverse))]
    (->> paragraphs
         (partition-by empty-paragraph?)
         (mapcat #(if (empty-paragraph? (first %)) [(first %)] %))
         (vec))))

(defn document->fragment
  "The paste fragment for a parsed HTML document, or nil when it has no text."
  [^js document]
  (let [paragraphs (->> (array-seq (.-childNodes (.-body document)))
                        (reduce #(walk-node %1 %2 {}) empty-builder)
                        (soft-break)
                        :paragraphs
                        (map runs->paragraph)
                        (normalize-paragraphs))]
    (when (seq paragraphs)
      paragraphs)))

(defn html->fragment
  [html]
  (-> (js/DOMParser.)
      (.parseFromString html "text/html")
      (document->fragment)))

(defn without-overrides
  "`fragment` as one plain run per paragraph, so it takes the style it is pasted
  into. External sources keep only their text and paragraphs."
  [fragment]
  (mapv (fn [{:keys [children] :as paragraph}]
          (assoc paragraph :children
                 (if (seq children)
                   [{:text (str/join (map :text children)) :attrs {}}]
                   [])))
        fragment))

(defn text->fragment
  "The paste fragment for plain text, one paragraph per line, or nil when empty."
  [text]
  (when (seq text)
    (mapv (fn [line]
            {:attrs {}
             :children (if (empty? line) [] [{:text line :attrs {}}])})
          (.split (str/replace text "\r" "") "\n"))))

;; --- Penpot payload
;;
;; Text copied in Penpot keeps its content in the `data-penpot-text` attr of an
;; empty span in the HTML: the one hidden marker every browser keeps.

(def ^:private payload-re #"data-penpot-text=\"([A-Za-z0-9+/=]*)\"")

(defn- encode-base64
  [s]
  (->> (.encode (js/TextEncoder.) s)
       (.from js/Array)
       (map #(.fromCharCode js/String %))
       (str/join)
       (js/btoa)))

(defn- decode-base64
  [s]
  (->> (.from js/Uint8Array (js/atob s) #(.charCodeAt % 0))
       (.decode (js/TextDecoder.))))

(defn payload->html
  "Hidden markup carrying `content`, copied from `file-id` in `team-id`."
  [content file-id team-id]
  (let [payload {:type :copied-text
                 :version 1
                 :file-id file-id
                 :team-id team-id
                 :content content}]
    (str "<span data-penpot-text=\"" (encode-base64 (t/encode-str payload)) "\"></span>")))

(defn html->payload
  "The Penpot payload in `html`, or nil when it has none."
  [html]
  (when-let [[_ encoded] (some->> html (re-find payload-re))]
    (let [payload (ex/ignoring (t/decode-str (decode-base64 encoded)))]
      (when (and (map? payload)
                 (= :copied-text (:type payload))
                 (map? (:content payload)))
        payload))))

;; --- Pasting into another file or team

(def ^:private default-font
  (d/seek #(= (:id %) (:font-id txt/default-text-attrs)) cfnt/local-fonts))

(defn- replace-custom-font
  "`node` in the default font, at the closest weight and style it has."
  [{:keys [font-weight font-style] :as node}]
  (let [variant (or (cfnt/closest-variant (:variants default-font) (str font-weight) font-style)
                    (d/seek #(= (:id %) (:font-variant-id txt/default-text-attrs)) (:variants default-font)))]
    (-> node
        (assoc :font-id (:id default-font)
               :font-family (:family default-font)
               :font-variant-id (:id variant)
               :font-weight (:weight variant)
               :font-style (:style variant))
        (ctt/remove-typography-from-node))))

(defn replace-custom-fonts
  "`content` with every custom font replaced by the default one, for pasting it
  into another team, where custom fonts have other ids."
  [content]
  (txt/transform-nodes #(= :custom (cfnt/font-id->backend (:font-id %)))
                       replace-custom-font
                       content))

(defn- drop-image-fills
  "`node` without image fills, or with the default fill when it had only those."
  [{:keys [fills] :as node}]
  (if (some :fill-image fills)
    (let [fills (into [] (remove :fill-image) fills)]
      (assoc node :fills (if (seq fills) fills (txt/get-default-text-fills))))
    node))

(defn clean-content
  "`content` without what the paste target cannot reach: typographies outside
  `valid-file-ids`, custom fonts from another team, images from another file."
  [content {:keys [valid-file-ids same-team? same-file?]}]
  (txt/transform-nodes
   (fn [node]
     (cond-> node
       (and (some? (:typography-ref-file node))
            (not (contains? valid-file-ids (:typography-ref-file node))))
       (ctt/remove-typography-from-node)

       (and (not same-team?)
            (= :custom (cfnt/font-id->backend (:font-id node))))
       (replace-custom-font)

       (not same-file?)
       (drop-image-fills)))
   content))
