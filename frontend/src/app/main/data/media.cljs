;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns app.main.data.media
  (:require
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [beicon.core :as rx]
   [potok.core :as ptk]
   [app.common.spec :as us]
   [app.common.data :as d]
   [app.common.media :as cm]
   [app.main.data.messages :as dm]
   [app.main.store :as st]
   [app.main.repo :as rp]
   [app.util.i18n :refer [tr]]
   [app.util.router :as rt]
   [app.common.uuid :as uuid]
   [app.util.time :as ts]
   [app.util.router :as r]))

;; --- Specs

(s/def ::js-file #(instance? js/Blob %))
(s/def ::js-files (s/coll-of ::js-file))

;; --- Utility functions

(defn validate-file
  ;; Check that a file obtained with the file javascript API is valid.
  [file]
  (when (> (.-size file) cm/max-file-size)
    (throw (ex-info (tr "errors.media-too-large") {})))
  (when-not (contains? cm/valid-media-types (.-type file))
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

