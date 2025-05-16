;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.tokens.typography
  (:require
   [potok.v2.core :as ptk]))

(defn set-base-font-size
  [base-font-size]
  (ptk/reify ::set-base-font-size
    ptk/UpdateEvent
    (update [_ state]
      (let [file-id (:current-file-id state)]
        (assoc-in state [:files file-id :data :options :base-font-size] base-font-size)))))