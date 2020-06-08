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
   [uxbox.config :as cfg]
   [uxbox.main.store :as st]
   [uxbox.main.repo :as rp]
   [uxbox.main.data.messages :as dm]
   [uxbox.util.router :as rt]
   [uxbox.util.i18n :as i18n :refer [tr]]
   [uxbox.util.storage :refer [storage]]
   [uxbox.util.avatars :as avatars]
   [uxbox.util.theme :as theme]))

;; --- Common Specs

(s/def ::id ::us/uuid)
(s/def ::fullname ::us/string)
(s/def ::email ::us/email)
(s/def ::password ::us/string)
(s/def ::lang ::us/string)
(s/def ::theme ::us/string)
(s/def ::photo ::us/string)
(s/def ::created-at ::us/inst)
(s/def ::password-1 ::us/string)
(s/def ::password-2 ::us/string)
(s/def ::password-old ::us/string)

(s/def ::profile
  (s/keys :req-un [::id]
          :opt-un [::created-at
                   ::fullname
                   ::photo
                   ::email
                   ::lang
                   ::theme]))

;; --- Profile Fetched

(defn profile-fetched
  [{:keys [fullname] :as data}]
  (us/verify ::profile data)
  (ptk/reify ::profile-fetched
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :profile
             (cond-> data
               (nil? (:photo-uri data))
               (assoc :photo-uri (avatars/generate {:name fullname}))

               (nil? (:lang data))
               (assoc :lang cfg/default-language)

               (nil? (:theme data))
               (assoc :theme cfg/default-theme))))

    ptk/EffectEvent
    (effect [_ state stream]
      (let [profile (:profile state)]
        (swap! storage assoc :profile profile)
        (i18n/set-current-locale! (:lang profile))
        (theme/set-current-theme! (:theme profile))))))

;; --- Fetch Profile

(def fetch-profile
  (reify
    ptk/WatchEvent
    (watch [_ state s]
      (->> (rp/query! :profile)
           (rx/map profile-fetched)
           (rx/catch (fn [error]
                       (if (= (:type error) :not-found)
                         (rx/of (rt/nav :auth-login))
                         (rx/empty))))))))

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
             (rx/map (constantly fetch-profile))
             (rx/catch rp/client-error? handle-error))))))

;; --- Request Email Change

(defn request-email-change
  [{:keys [email] :as data}]
  (ptk/reify ::request-email-change
    ptk/WatchEvent
    (watch [_ state stream]
      (let [{:keys [on-error on-success]
             :or {on-error identity
                  on-success identity}} (meta data)]
        (->> (rp/mutation :request-email-change data)
             (rx/tap on-success)
             (rx/map (constantly fetch-profile))
             (rx/catch (fn [err]
                         (on-error err)
                         (rx/empty))))))))

;; --- Cancel Email Change

(def cancel-email-change
  (ptk/reify ::cancel-email-change
    ptk/WatchEvent
    (watch [_ state stream]
      (->> (rp/mutation :cancel-email-change {})
           (rx/map (constantly fetch-profile))))))

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
      (let [{:keys [on-error on-success]
             :or {on-error identity
                  on-success identity}} (meta data)
            params {:old-password (:password-old data)
                    :password (:password-1 data)}]
        (->> (rp/mutation :update-profile-password params)
             (rx/tap on-success)
             (rx/catch (fn [err]
                         (on-error err)
                         (rx/empty)))
             (rx/ignore))))))


;; --- Update Photo

(s/def ::file #(instance? js/File %))
(def allowed-file-types #{"image/jpeg" "image/png" "image/webp"})
(def max-file-size (* 5 1024 1024))

;; TODO: unify with create-images at main/data/images.cljs
;;       and upload-image at main/data/workspace/persistence.cljs
;; https://tree.taiga.io/project/uxboxproject/us/440

(defn update-photo
  [file]
  (us/verify ::file file)
  (ptk/reify ::update-photo
    ptk/WatchEvent
    (watch [_ state stream]
      (let [check-file
            (fn [file]
              (when (> (.-size file) max-file-size)
                (throw (ex-info (tr "errors.image-too-large") {})))
              (when-not (contains? allowed-file-types (.-type file))
                (throw (ex-info (tr "errors.image-format-unsupported") {})))
              file)

             on-success #(do (st/emit! dm/hide))

             on-error #(do (st/emit! dm/hide)
                           (if (.-message %)
                             (rx/of (dm/error (.-message %)))
                             (rx/of (dm/error (tr "errors.unexpected-error")))))

             prepare
             (fn [file]
               {:file file})]

      (st/emit! (dm/show {:content (tr "image.loading")
                          :type :info
                          :timeout nil}))

      (->> (rx/of file)
           (rx/map check-file)
           (rx/map prepare)
           (rx/mapcat #(rp/mutation :update-profile-photo %))
           (rx/do on-success)
           (rx/map (constantly fetch-profile))
           (rx/catch on-error))))))

