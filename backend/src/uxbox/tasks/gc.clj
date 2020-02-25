;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2016-2020 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.tasks.gc
  (:require
   [clojure.tools.logging :as log]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]
   [postal.core :as postal]
   [promesa.core :as p]
   [uxbox.common.exceptions :as ex]
   [uxbox.common.spec :as us]
   [uxbox.config :as cfg]
   [uxbox.db :as db]
   [uxbox.util.blob :as blob]))

;; TODO: delete media referenced in pendint_to_delete table

;; (def ^:private sql:delete-item
;;   "with items_part as (
;;      select i.id
;;        from pending_to_delete as i
;;       order by i.created_at
;;       limit 1
;;       for update skip locked
;;    )
;;    delete from pending_to_delete
;;     where id in (select id from items_part)
;;    returning *")

;; (defn- remove-items
;;   []
;;   (vu/loop []
;;     (db/with-atomic [conn db/pool]
;;       (-> (db/query-one conn sql:delete-item)
;;           (p/then decode-row)
;;           (p/then (vu/wrap-blocking remove-media))
;;           (p/then (fn [item]
;;                     (when (not (empty? items))
;;                       (p/recur))))))))

;; (defn- remove-media
;;   [{:keys
;;   (doseq [item files]
;;     (ust/delete! media/media-storage (:path item))
;;     (ust/delete! media/media-storage (:thumb-path item)))
;;   files)
