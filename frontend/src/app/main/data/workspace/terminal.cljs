;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.terminal
  (:require
   [app.common.data :as d]
   [app.common.types.text :as txt]
   [clojure.string :as cstr]))

(def default-cols 80)
(def default-rows 24)
(def default-cell-width 9)
(def default-cell-height 18)

(defn- split-lines-preserve-empty
  [text]
  (cstr/split (or text "") #"\r?\n" -1))

(defn normalize-grid-text
  [text cols rows]
  (let [rows  (or rows default-rows)
        cols  (or cols default-cols)
        lines (split-lines-preserve-empty text)]
    (->> (for [idx (range rows)]
           (let [line (or (nth lines idx nil) "")
                 line (subs (str line (apply str (repeat cols " "))) 0 cols)]
             line))
         (cstr/join "\n"))))

(defn as-terminal-content
  [text]
  (let [default-style (merge (txt/get-default-text-attrs)
                             {:font-family "monospace"
                              :font-size "14"
                              :line-height "1.2"
                              :font-weight "400"
                              :font-style "normal"
                              :text-decoration "none"})
        paragraphs    (->> (split-lines-preserve-empty text)
                           (mapv (fn [line]
                                   {:type "paragraph"
                                    :children [(merge default-style {:text line})]})))]
    {:type "root"
     :children [{:type "paragraph-set"
                 :children paragraphs}]}))

(defn make-terminal-shape
  [{:keys [x y cols rows text]
    :or {cols default-cols
         rows default-rows}}]
  (let [cell-width default-cell-width
        cell-height default-cell-height
        text       (normalize-grid-text text cols rows)]
    {:name "Terminal"
     :x x
     :y y
     :width (* cols cell-width)
     :height (* rows cell-height)
     :grow-type :fixed
     :content (as-terminal-content text)
     :plugin-data
     {:terminal
      {:cols cols
       :rows rows
       :cell-width cell-width
       :cell-height cell-height}}}))

(defn terminal-shape?
  [shape]
  (and (= :text (:type shape))
       (d/not-empty? (get-in shape [:plugin-data :terminal]))))
