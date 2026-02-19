;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.management.forms.controls.token-parsing
  (:require
   [app.main.ui.ds.controls.select :refer [get-option]]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(defn extract-partial-token
  [value cursor]
  (let [text-before (subs value 0 cursor)
        last-open  (str/last-index-of text-before "{")
        last-close (str/last-index-of text-before "}")]
    (when (and last-open (or (nil? last-close) (> last-open last-close)))
      {:start last-open
       :partial (subs text-before (inc last-open))})))

(defn replace-active-token
  [value cursor new-name]

  (let [before     (subs value 0 cursor)
        last-open  (str/last-index-of before "{")
        last-close (str/last-index-of before "}")]

    (if (and last-open
             (or (nil? last-close)
                 (> last-open last-close)))

      (let [after-start (subs value last-open)
            close-pos   (str/index-of after-start "}")
            end         (if close-pos
                          (+ last-open close-pos 1)
                          cursor)]
        (str (subs value 0 last-open)
             "{" new-name "}"
             (subs value end)))
      (str (subs value 0 cursor)
           "{" new-name "}"
           (subs value cursor)))))

(defn active-token [value input-node]
  (let [cursor (.-selectionStart input-node)]
    (extract-partial-token value cursor)))

(defn remove-self-token [filtered-options current-token]
  (let [group (:type current-token)
        current-id (:id current-token)
        filtered-options (deref filtered-options)]
    (update filtered-options group
            (fn [options]
              (remove #(= (:id %) current-id) options)))))

(defn select-option-by-id
  [id options-ref input-node value]
  (let [cursor     (.-selectionStart input-node)
        options    (mf/ref-val options-ref)
        options    (if (delay? options) @options options)

        option     (get-option options id)
        name       (:name option)
        final-val  (replace-active-token value cursor name)]
    final-val))