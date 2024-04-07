;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.media
  (:require
   [app.common.exceptions :as ex]
   [app.common.media :as cm]
   [app.main.data.messages :as msg]
   [app.main.store :as st]
   [app.util.i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]))

;; --- Predicates

(defn file?
  [o]
  (instance? js/File o))

(defn blob?
  [o]
  (instance? js/Blob o))

;; --- Specs

(s/def ::blob blob?)
(s/def ::blobs (s/coll-of ::blob))

(s/def ::file file?)
(s/def ::files (s/coll-of ::file))

;; --- Utility functions

(defn validate-file
  "Check that a file obtained with the file javascript API is valid."
  [file]
  (when-not (contains? cm/valid-image-types (.-type file))
    (ex/raise :type :validation
              :code :media-type-not-allowed
              :hint (str/ffmt "media type % is not supported" (.-type file))))
  file)

(defn notify-start-loading
  []
  (st/emit! (msg/show {:content (tr "media.loading")
                       :notification-type :toast
                       :type :info
                       :timeout nil})))

(defn notify-finished-loading
  []
  (st/emit! msg/hide))

(defn process-error
  [error]
  (let [msg (cond
              (.-message error)
              (.-message error)

              (= (:code error) :media-type-not-allowed)
              (tr "errors.media-type-not-allowed")

              (= (:code error) :media-type-mismatch)
              (tr "errors.media-type-mismatch")

              :else
              (tr "errors.unexpected-error"))]
    (rx/of (msg/error msg))))
