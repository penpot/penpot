;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.media
  (:require
   [app.common.data :as d]
   [app.common.media :as cm]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.common.exceptions :as ex]
   [app.main.data.messages :as dm]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.util.i18n :refer [tr]]
   [app.util.router :as r]
   [app.util.router :as rt]
   [app.util.time :as ts]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [potok.core :as ptk]))

;; --- Predicates

(defn ^boolean file?
  [o]
  (instance? js/File o))

(defn ^boolean blob?
  [o]
  (instance? js/Blob o))


;; --- Specs

(s/def ::blob blob?)
(s/def ::blobs (s/coll-of ::blob))

(s/def ::file file?)
(s/def ::files (s/coll-of ::file))

;; --- Utility functions

(defn validate-file
  ;; Check that a file obtained with the file javascript API is valid.
  [file]
  (when (> (.-size file) cm/max-file-size)
    (ex/raise :type :validation
              :code :media-too-large
              :hint (str/fmt "media size is large than 5mb (size: %s)" (.-size file))))
  (when-not (contains? cm/valid-image-types (.-type file))
    (ex/raise :type :validation
              :code :media-type-not-allowed
              :hint (str/fmt "media type %s is not supported" (.-type file))))
  file)

(defn notify-start-loading
  []
  (st/emit! (dm/show {:content (tr "media.loading")
                      :type :info
                      :timeout nil})))

(defn notify-finished-loading
  []
  (st/emit! dm/hide))

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
    (rx/of (dm/error msg))))

