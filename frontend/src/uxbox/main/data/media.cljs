;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.data.media
  (:require
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [beicon.core :as rx]
   [potok.core :as ptk]
   [uxbox.common.spec :as us]
   [uxbox.common.data :as d]
   [uxbox.main.data.messages :as dm]
   [uxbox.main.store :as st]
   [uxbox.main.repo :as rp]
   [uxbox.util.i18n :refer [tr]]
   [uxbox.util.router :as rt]
   [uxbox.common.uuid :as uuid]
   [uxbox.util.time :as ts]
   [uxbox.util.router :as r]
   [uxbox.util.files :as files]))

;; --- Specs

(s/def ::id uuid?)
(s/def ::name string?)
(s/def ::width number?)
(s/def ::height number?)
(s/def ::created-at inst?)
(s/def ::modified-at inst?)
(s/def ::mtype string?)
(s/def ::uri string?)

(s/def ::media-object
  (s/keys :req-un [::id
                   ::name
                   ::width
                   ::height
                   ::mtype
                   ::created-at
                   ::modified-at
                   ::uri]))

(s/def ::js-file #(instance? js/Blob %))
(s/def ::js-files (s/coll-of ::js-file))

(def allowed-media-types #{"image/jpeg" "image/png" "image/webp" "image/svg+xml"})
(def str-media-types (str/join "," allowed-media-types))
(def max-file-size (* 5 1024 1024))

;; --- Utility functions

(defn validate-file
  ;; Check that a file obtained with the file javascript API is valid.
  [file]
  (when (> (.-size file) max-file-size)
    (throw (ex-info (tr "errors.media-too-large") {})))
  (when-not (contains? allowed-media-types (.-type file))
    (throw (ex-info (tr "errors.media-format-unsupported") {})))
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

