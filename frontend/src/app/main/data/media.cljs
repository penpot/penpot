;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.data.media
  (:require
   [app.common.data :as d]
   [app.common.media :as cm]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
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

