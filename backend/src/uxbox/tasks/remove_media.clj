;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.tasks.remove-media
  "Demo accounts garbage collector."
  (:require
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [uxbox.common.exceptions :as ex]
   [uxbox.common.spec :as us]
   [uxbox.media-storage :as mst]
   [uxbox.metrics :as mtx]
   [uxbox.util.storage :as ust]))

(s/def ::path ::us/not-empty-string)
(s/def ::props
  (s/keys :req-un [::path]))

(defn handler
  [{:keys [props] :as task}]
  (us/verify ::props props)
  (when (ust/exists? mst/media-storage (:path props))
    (ust/delete! mst/media-storage (:path props))
    (log/debug "Media " (:path props) " removed.")))

(mtx/instrument-with-summary!
 {:var #'handler
  :id "tasks__remove_media"
  :help "Timing of remove-media task."})
