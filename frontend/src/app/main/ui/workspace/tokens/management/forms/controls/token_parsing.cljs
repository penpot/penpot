;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.management.forms.controls.token-parsing
  (:require
   [app.main.ui.ds.controls.select :refer [get-option]]
   [app.util.dom :as dom]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(defn extract-partial-token
  [value cursor]
  (let [text-before (subs value 0 cursor)
        last-open  (str/last-index-of text-before "{")
        last-close (str/last-index-of text-before "}")]
    (when (and last-open (or (nil? last-close) (> last-open last-close)))
      {:start last-open
       :end (or (str/index-of value "}" last-open) cursor)
       :partial (subs text-before (inc last-open))})))

(defn find-active-token-range
  "Returns {:start :end} for the token surrounding the cursor.
   A token starts with '{', contains no spaces, and may be incomplete.
   Returns nil if no valid token is active."
  [value cursor]
  (let [start (.lastIndexOf value "{" cursor)]
    (when (>= start 0)
      (let [between (subs value (inc start) cursor)]
        (when-not (re-find #"\s" between)
          (let [after        (subs value (inc start))
                close-index  (.indexOf after "}")
                close-pos    (when (>= close-index 0)
                               (+ (inc start) close-index))
                space-index  (.indexOf after " ")
                space-pos    (when (>= space-index 0)
                               (+ (inc start) space-index))

                end (cond
                      (and close-pos
                           (or (nil? space-pos)
                               (< close-pos space-pos)))
                      (inc close-pos)

                      space-pos
                      space-pos

                      :else
                      cursor)]

            {:start start
             :end   end}))))))

(defn replace-active-token
  "Replaces the token at the cursor with `{new-name}`.
   Returns {:value :cursor} with the updated value and new cursor position."
  [value cursor new-name]
  (let [new-token (str "{" new-name "}")]
    (if-let [{:keys [start end]}
             (find-active-token-range value cursor)]

      (let [new-value (str (subs value 0 start)
                           new-token
                           (subs value end))
            new-cursor (+ start (count new-token))]
        {:value new-value
         :cursor new-cursor})

      (let [new-value (str (subs value 0 cursor)
                           new-token
                           (subs value cursor))
            new-cursor (+ cursor (count new-token))]
        {:value new-value
         :cursor new-cursor}))))

(defn active-token [value input-node]
  (let [cursor (dom/selection-start input-node)]
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
  (let [cursor     (dom/selection-start input-node)
        options    (mf/ref-val options-ref)
        options    (if (delay? options) @options options)

        option     (get-option options id)
        name       (:name option)]
    (replace-active-token value cursor name)))