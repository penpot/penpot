;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.data.users
  (:require
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [potok.core :as ptk]
   [uxbox.common.spec :as us]
   [uxbox.main.repo :as rp]
   [uxbox.util.i18n :as i18n :refer [tr]]
   [uxbox.util.messages :as uum]
   [uxbox.util.storage :refer [storage]]))

;; --- Common Specs

(s/def ::id ::us/uuid)
(s/def ::fullname ::us/string)
(s/def ::email ::us/email)
(s/def ::password ::us/string)
(s/def ::language ::us/string)
(s/def ::photo ::us/string)
(s/def ::created-at ::us/inst)
(s/def ::password-1 ::us/string)
(s/def ::password-2 ::us/string)
(s/def ::password-old ::us/string)
(s/def ::lang (s/nilable ::us/string))

(s/def ::profile
  (s/keys :req-un [::id]
          :opt-un [::created-at
                   ::fullname
                   ::photo
                   ::email
                   ::lang]))

;; --- Profile Fetched

(defn profile-fetched
  [data]
  (us/verify ::profile data)
  (ptk/reify ::profile-fetched
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :profile data))

    ptk/EffectEvent
    (effect [_ state stream]
      (swap! storage assoc :profile data)
      (when-let [lang (:lang data)]
        (i18n/set-current-locale! lang)))))

;; --- Fetch Profile

(def fetch-profile
  (reify
    ptk/WatchEvent
    (watch [_ state s]
      (->> (rp/query! :profile)
           (rx/map profile-fetched)))))

;; --- Update Profile

(defn update-profile
  [data]
  (us/assert ::profile data)
  (ptk/reify ::update-profile
    ptk/WatchEvent
    (watch [_ state s]
      (let [mdata (meta data)
            on-success (:on-success mdata identity)
            on-error (:on-error mdata identity)
            handle-error #(do (on-error (:payload %))
                              (rx/empty))]
        (->> (rp/mutation :update-profile data)
             (rx/do on-success)
             (rx/map profile-fetched)
             (rx/catch rp/client-error? handle-error))))))

;; --- Update Password (Form)

(s/def ::update-password
  (s/keys :req-un [::password-1
                   ::password-2
                   ::password-old]))

(defn update-password
  [data]
  (us/verify ::update-password data)
  (ptk/reify ::update-password
    ptk/WatchEvent
    (watch [_ state s]
      (let [mdata (meta data)
            on-success (:on-success mdata identity)
            on-error (:on-error mdata identity)
            params {:old-password (:password-old data)
                    :password (:password-1 data)}]
        (->> (rp/mutation :update-profile-password params)
             (rx/catch rp/client-error? #(do (on-error (:payload %))
                                             (rx/empty)))
             (rx/do on-success)
             (rx/ignore))))))


;; --- Update Photoo

(s/def ::file #(instance? js/File %))

(defn update-photo
  [{:keys [file] :as params}]
  (us/verify ::file file)
  (ptk/reify ::update-photo
    ptk/WatchEvent
    (watch [_ state stream]
      (->> (rp/mutation :update-profile-photo {:file file})
           (rx/map (constantly fetch-profile))))))
