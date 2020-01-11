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
   [uxbox.main.repo.core :as rp]
   [uxbox.util.i18n :as i18n :refer [tr]]
   [uxbox.util.messages :as uum]
   [uxbox.util.storage :refer [storage]]))

;; --- Common Specs

(s/def ::id uuid?)
(s/def ::username string?)
(s/def ::fullname string?)
(s/def ::email ::us/email)
(s/def ::password string?)
(s/def ::language string?)
(s/def ::photo string?)
(s/def ::created-at inst?)
(s/def ::password-1 string?)
(s/def ::password-2 string?)
(s/def ::password-old string?)

;; --- Profile Fetched

(s/def ::profile-fetched
  (s/keys :req-un [::id
                   ::username
                   ::fullname
                   ::email
                   ::created-at
                   ::photo]))

(defn profile-fetched
  [data]
  (us/assert ::profile-fetched data)
  (ptk/reify ::profile-fetched
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :profile data))

    ptk/EffectEvent
    (effect [_ state stream]
      (swap! storage assoc :profile data)
      (when-let [lang (get-in data [:metadata :language])]
        (i18n/set-current-locale! lang)))))

;; --- Fetch Profile

(def fetch-profile
  (reify
    ptk/WatchEvent
    (watch [_ state s]
      (->> (rp/query! :profile)
           (rx/map profile-fetched)))))

;; --- Update Profile

(s/def ::update-profile-params
  (s/keys :req-un [::fullname
                   ::email
                   ::username
                   ::language]))

(defn form->update-profile
  [data on-success on-error]
  (us/assert ::update-profile-params data)
  (us/assert fn? on-error)
  (us/assert fn? on-success)
  (reify
    ptk/WatchEvent
    (watch [_ state s]
      (letfn [(handle-error [{payload :payload}]
                (on-error payload)
                (rx/empty))]
        (let [data (-> (:profile state)
                       (assoc :fullname (:fullname data))
                       (assoc :email (:email data))
                       (assoc :username (:username data))
                       (assoc-in [:metadata :language] (:language data)))]
          (->> (rp/req :update/profile data)
               (rx/map :payload)
               (rx/do on-success)
               (rx/map profile-fetched)
               (rx/catch rp/client-error? handle-error)))))))

;; --- Update Password (Form)

(s/def ::update-password-params
  (s/keys :req-un [::password-1
                   ::password-2
                   ::password-old]))

(defn update-password
  [data {:keys [on-success on-error]}]
  (us/assert ::update-password-params data)
  (us/assert fn? on-success)
  (us/assert fn? on-error)
  (reify
    ptk/WatchEvent
    (watch [_ state s]
      (let [params {:old-password (:password-old data)
                    :password (:password-1 data)}]
        (->> (rp/req :update/profile-password params)
             (rx/catch rp/client-error? (fn [e]
                                          (on-error (:payload e))
                                          (rx/empty)))
             (rx/do on-success)
             (rx/ignore))))))


;; --- Update Photo

(deftype UpdatePhoto [file done]
  ptk/WatchEvent
  (watch [_ state stream]
    (->> (rp/req :update/profile-photo {:file file})
         (rx/do done)
         (rx/map (constantly fetch-profile)))))

(s/def ::file #(instance? js/File %))

(defn update-photo
  ([file] (update-photo file (constantly nil)))
  ([file done]
   (us/assert ::file file)
   (us/assert fn? done)
   (UpdatePhoto. file done)))
