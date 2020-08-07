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

(s/def ::name string?)
(s/def ::width number?)
(s/def ::height number?)
(s/def ::modified-at inst?)
(s/def ::created-at inst?)
(s/def ::mtype string?)
;; (s/def ::thumbnail string?)
(s/def ::id uuid?)
(s/def ::uri string?)
(s/def ::user-id uuid?)

(s/def ::media-object
  (s/keys :req-un [::id
                   ::name
                   ::width
                   ::height
                   ::mtype
                   ::created-at
                   ::modified-at
                   ::uri
                   ;; ::thumb-uri
                   ::user-id]))

;; --- Create library Media Objects

(declare create-media-objects-result)
(def allowed-file-types #{"image/jpeg" "image/png" "image/webp" "image/svg+xml"})
(def max-file-size (* 5 1024 1024))

;; TODO: unify with upload-media-object at main/data/workspace/persistence.cljs
;;       and update-photo at main/data/users.cljs
;; https://tree.taiga.io/project/uxboxproject/us/440

(defn create-media-objects
  ([file-id files] (create-media-objects file-id files identity))
  ([file-id files on-uploaded]
   (us/verify (s/nilable ::us/uuid) file-id)
   (us/verify fn? on-uploaded)
   (ptk/reify ::create-media-objects
     ptk/WatchEvent
     (watch [_ state stream]
       (let [check-file
             (fn [file]
               (when (> (.-size file) max-file-size)
                 (throw (ex-info (tr "errors.media-too-large") {})))
               (when-not (contains? allowed-file-types (.-type file))
                 (throw (ex-info (tr "errors.media-format-unsupported") {})))
               file)

             on-success #(do (st/emit! dm/hide)
                             (on-uploaded %))

             on-error #(do (st/emit! dm/hide)
                           (let [msg (cond
                                       (.-message %)
                                       (.-message %)

                                       (= (:code %) :media-type-not-allowed)
                                       (tr "errors.media-type-not-allowed")

                                       (= (:code %) :media-type-mismatch)
                                       (tr "errors.media-type-mismatch")

                                       :else
                                       (tr "errors.unexpected-error"))]
                             (rx/of (dm/error msg))))

             prepare
             (fn [file]
               {:name (.-name file)
                :file-id file-id
                :content file
                :is-local false})]

         (st/emit! (dm/show {:content (tr "media.loading")
                             :type :info
                             :timeout nil}))

         (->> (rx/from files)
              (rx/map check-file)
              (rx/map prepare)
              (rx/mapcat #(rp/mutation! :upload-media-object %))
              (rx/reduce conj [])
              (rx/do on-success)
              (rx/mapcat identity)
              (rx/map (partial create-media-objects-result file-id))
              (rx/catch on-error)))))))

;; --- Media object Created

(defn create-media-objects-result
  [file-id media-object]
  #_(us/verify ::media-object media-object)
  (ptk/reify ::create-media-objects-result
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc-in [:workspace-media (:id media-object)] media-object)))))

