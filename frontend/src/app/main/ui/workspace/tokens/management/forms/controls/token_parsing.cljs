;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.management.forms.controls.token-parsing
  (:require
   [app.common.types.token :as cto]
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
    (cto/insert-ref value cursor name)))