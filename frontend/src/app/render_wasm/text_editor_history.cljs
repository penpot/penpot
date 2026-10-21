;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.render-wasm.text-editor-history)

(def empty-history {:past [] :future []})

(defn record
  [history before after]
  (if (= (:content before) (:content after))
    history
    (-> history
        (update :past #(conj (vec (take-last 99 %)) before))
        (assoc :future []))))

(defn step
  [history direction current]
  (let [[from to] (case direction :undo [:past :future] :redo [:future :past])]
    (when-let [snapshot (peek (get history from))]
      {:snapshot snapshot
       :history (-> history
                    (update from pop)
                    (update to conj current))})))
