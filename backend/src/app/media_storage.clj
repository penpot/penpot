;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.media-storage
  "A media storage impl for app."
  (:require
   [integrant.core :as ig]
   [app.common.spec :as us]
   [clojure.spec.alpha :as s]
   [app.config :refer [config]]
   [app.util.storage :as ust]
   [mount.core :refer [defstate]]))

(s/def ::media-directory ::us/not-empty-string)
(s/def ::media-uri ::us/not-empty-string)

(defmethod ig/pre-init-spec ::storage [_]
  (s/keys :req-un [::media-directory
                   ::media-uri]))

(defmethod ig/init-key ::storage
  [_ cfg]
  (ust/create {:base-path (:media-directory cfg)
               :base-uri (:media-uri cfg)
               :xf (comp ust/random-path
                         ust/slugify-filename)}))
