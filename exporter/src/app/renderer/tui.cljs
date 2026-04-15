;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.renderer.tui
  "Renderer for terminal-oriented text exports (ANSI + JSON grid)."
  (:require
   [app.common.types.text :as txt]
   [app.util.mime :as mime]
   [app.util.shell :as sh]
   [cuerdas.core :as str]
   [promesa.core :as p]))

(def ^:private default-cols 80)
(def ^:private default-rows 24)

(def ^:private ansi-reset "\u001b[0m")

(defn- parse-int
  [v]
  (js/parseInt v 10))

(defn- parse-hex
  [v]
  (js/parseInt v 16))

(defn- parse-css-color
  [value]
  (let [value (or value "")]
    (cond
      (or (= value "transparent")
          (str/blank? value))
      nil

      (str/starts-with? value "#")
      (let [hex (subs value 1)
            hex (if (= (count hex) 3)
                  (apply str (mapcat (fn [c] [c c]) hex))
                  hex)]
        (when (= (count hex) 6)
          [(parse-hex (subs hex 0 2))
           (parse-hex (subs hex 2 4))
           (parse-hex (subs hex 4 6))]))

      :else
      (when-let [[_ r g b] (re-matches #"rgba?\((\d+),\s*(\d+),\s*(\d+).*\)" value)]
        [(parse-int r) (parse-int g) (parse-int b)]))))

(defn- sgr-seq
  [{:keys [fg bg bold underline dim]}]
  (let [codes (cond-> []
                bold (conj "1")
                dim (conj "2")
                underline (conj "4")
                (vector? fg) (conj (str "38;2;" (nth fg 0) ";" (nth fg 1) ";" (nth fg 2)))
                (vector? bg) (conj (str "48;2;" (nth bg 0) ";" (nth bg 1) ";" (nth bg 2))))]
    (when (seq codes)
      (str "\u001b[" (str/join ";" codes) "m"))))

(defn- cell-style
  [shape-style node-style]
  (let [font-weight (or (:font-weight node-style) "400")
        bold?       (>= (parse-int font-weight) 600)
        dim?        (<= (parse-int font-weight) 300)
        underline?  (str/includes? (or (:text-decoration node-style) "") "underline")
        fg          (or (-> node-style :fills first :fill-color parse-css-color)
                        (:fg shape-style))
        bg          (:bg shape-style)]
    {:fg fg
     :bg bg
     :bold bold?
     :dim dim?
     :underline underline?}))

(defn- shape-style
  [shape]
  {:fg (parse-css-color (or (-> shape :fills first :fill-color) "#D0D0D0"))
   :bg (parse-css-color (or (-> shape :plugin-data :terminal :bg-color) "#121212"))})

(defn- strip-ansi
  [text]
  (str/replace text #"\u001b\[[0-9;]*m" ""))

(defn- build-cells
  [shape cols rows]
  (let [segments (txt/content->text+styles (:content shape))
        base-style (shape-style shape)
        empty-line (mapv (fn [_]
                           {:char " "
                            :fg (:fg base-style)
                            :bg (:bg base-style)
                            :bold false
                            :underline false
                           :dim false})
                         (range cols))
        initial-state {:cells (vec (repeat rows empty-line))
                       :x 0
                       :y 0}
        write-char
        (fn [{:keys [cells x y] :as state} ch style]
          (cond
            (>= y rows)
            state

            (= ch \newline)
            (assoc state :x 0 :y (inc y))

            :else
            (let [next-x (inc x)
                  next-y (if (>= next-x cols) (inc y) y)
                  next-x (if (>= next-x cols) 0 next-x)]
              {:cells (assoc-in cells [y x]
                                {:char (str ch)
                                 :fg (:fg style)
                                 :bg (:bg style)
                                 :bold (:bold style)
                                 :underline (:underline style)
                                 :dim (:dim style)})
               :x next-x
               :y next-y})))
        state
        (reduce
         (fn [state [node-style text]]
           (let [style (cell-style base-style node-style)]
             (reduce #(write-char %1 %2 style)
                     state
                     (seq (strip-ansi text)))))
         initial-state
         segments)]
    (:cells state)))

(defn- style-key
  [{:keys [fg bg bold underline dim]}]
  [fg bg bold underline dim])

(defn- cells->ansi
  [cells]
  (let [render-line
        (fn [line]
          (loop [result ""
                 prev-style nil
                 chars (seq line)]
            (if-let [cell (first chars)]
              (let [style (style-key cell)
                    sgr (when (not= prev-style style) (or (sgr-seq cell) ansi-reset))]
                (recur (str result (or sgr "") (:char cell)) style (next chars)))
              (str result ansi-reset))))]
    (->> cells
         (map render-line)
         (str/join "\n"))))

(defn- text->json
  [cells cols rows]
  (let [payload {:version 1
                 :format "terminal-grid"
                 :width cols
                 :height rows
                 :cells (->> (for [y (range rows)
                                   x (range cols)]
                               (assoc (get-in cells [y x]) :x x :y y))
                             (into []))}]
    (.stringify js/JSON (clj->js payload) nil 2)))

(defn- render-object
  [type object]
  (let [shape (:shape object)
        cols  (or (some-> (get-in shape [:plugin-data :terminal :cols]) parse-int) default-cols)
        rows  (or (some-> (get-in shape [:plugin-data :terminal :rows]) parse-int) default-rows)
        cells (build-cells shape cols rows)
        output (case type
                 :ansi (cells->ansi cells)
                 :json (text->json cells cols rows))]
    (p/let [path (sh/tempfile :prefix "penpot.tmp.render.tui." :suffix (mime/get-extension type))]
    (sh/write-file! path output)
      (assoc object :path path))))

(defn render
  [{:keys [type objects]} on-object]
  (p/loop [objects (seq objects)]
    (when-let [object (first objects)]
      (p/let [result (render-object type object)]
        (on-object result)
        (p/recur (rest objects))))))
