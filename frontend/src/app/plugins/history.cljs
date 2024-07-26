;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.history
  (:require
   [app.common.record :as crc]
   [app.main.data.workspace.undo :as dwu]
   [app.main.store :as st]
   [app.plugins.register :as r]
   [app.plugins.utils :as u]))

(deftype HistorySubcontext [$plugin]
  Object
  (undoBlockBegin
    [_]
    (cond
      (not (r/check-permission $plugin "content:write"))
      (u/display-not-valid :resize "Plugin doesn't have 'content:write' permission")

      :else
      (let [id (js/Symbol)]
        (st/emit! (dwu/start-undo-transaction id))
        id)))

  (undoBlockFinish
    [_ block-id]
    (cond
      (not (r/check-permission $plugin "content:write"))
      (u/display-not-valid :resize "Plugin doesn't have 'content:write' permission")

      (not block-id)
      (u/display-not-valid :undoBlockFinish block-id)

      :else
      (st/emit! (dwu/commit-undo-transaction block-id)))))

(crc/define-properties!
  HistorySubcontext
  {:name js/Symbol.toStringTag
   :get (fn [] (str "HistorySubcontext"))})

(defn history-subcontext? [p]
  (instance? HistorySubcontext p))

(defn history-subcontext
  [plugin-id]
  (HistorySubcontext. plugin-id))


