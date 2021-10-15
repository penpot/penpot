;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.rpc.queries.share-link
  (:require
   [app.db :as db]))

(defn decode-share-link-row
  [row]
  (-> row
      (update :flags db/decode-pgarray #{})
      (update :pages db/decode-pgarray #{})))

(defn retrieve-share-link
  [conn file-id share-id]
  (some-> (db/get-by-params conn :share-link
                            {:id share-id :file-id file-id}
                            {:check-not-found false})
          (decode-share-link-row)))

