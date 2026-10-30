;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.tasks.compact-file-snapshot
  "Rewrites a file snapshot with plain maps in place of its objects-maps."
  (:require
   [app.common.logging :as l]
   [app.db :as db]
   [app.features.file-snapshots :as fsnap]
   [app.storage :as sto]
   [integrant.core :as ig]))

(defmethod ig/assert-key ::handler
  [_ params]
  (assert (db/pool? (::db/pool params)) "expected a valid database pool")
  (assert (sto/valid-storage? (::sto/storage params)) "expected valid storage to be provided"))

(defmethod ig/init-key ::handler
  [_ cfg]
  (fn [{:keys [props] :as task}]
    (let [{:keys [file-id id]} props
          compacted? (db/tx-run! cfg fsnap/compact! file-id id)]
      (l/trc :hint "compact file snapshot"
             :file-id (str file-id)
             :id (str id)
             :compacted (boolean compacted?)))))
