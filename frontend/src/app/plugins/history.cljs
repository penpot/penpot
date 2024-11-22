;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.history
  (:require
   [app.main.data.workspace.undo :as dwu]
   [app.main.store :as st]
   [app.plugins.register :as r]
   [app.plugins.utils :as u]
   [app.util.object :as obj]))

(defn history-subcontext? [p]
  (obj/type-of? p "HistorySubcontext"))

(defn history-subcontext
  [plugin-id]
  (obj/reify {:name "HistorySubcontext"}
    :$plugin {:enumerable false :get (fn [] plugin-id)}

    :undoBlockBegin
    (fn []
      (cond
        (not (r/check-permission plugin-id "content:write"))
        (u/display-not-valid :resize "Plugin doesn't have 'content:write' permission")

        :else
        (let [id (js/Symbol)]
          (st/emit! (dwu/start-undo-transaction id))
          id)))

    :undoBlockFinish
    (fn [block-id]
      (cond
        (not (r/check-permission plugin-id "content:write"))
        (u/display-not-valid :resize "Plugin doesn't have 'content:write' permission")

        (not block-id)
        (u/display-not-valid :undoBlockFinish block-id)

        :else
        (st/emit! (dwu/commit-undo-transaction block-id))))))


