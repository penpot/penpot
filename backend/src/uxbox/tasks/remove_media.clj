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
   [uxbox.media :as media]
   [uxbox.util.storage :as ust]
   [vertx.util :as vu]))

(s/def ::path ::us/not-empty-string)
(s/def ::props
  (s/keys :req-un [::path]))

(defn handler
  [{:keys [props] :as task}]
  (us/verify ::props props)
  (vu/blocking
   (when (ust/exists? media/media-storage (:path props))
     (ust/delete! media/media-storage (:path props))
     (log/debug "Media " (:path props) " removed."))))

