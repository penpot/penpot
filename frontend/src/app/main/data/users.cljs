;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.users
  (:require
   [app.config :as cf]
   [app.common.data :as d]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.main.data.media :as di]
   [app.main.data.messages :as dm]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.util.avatars :as avatars]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.router :as rt]
   [app.util.storage :refer [storage]]
   [app.util.theme :as theme]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [potok.core :as ptk]))

;; --- Common Specs

(s/def ::id ::us/uuid)
(s/def ::fullname ::us/string)
(s/def ::email ::us/email)
(s/def ::password ::us/string)
(s/def ::lang (s/nilable ::us/string))
(s/def ::theme (s/nilable ::us/string))
(s/def ::created-at ::us/inst)
(s/def ::password-1 ::us/string)
(s/def ::password-2 ::us/string)
(s/def ::password-old ::us/string)

(s/def ::profile
  (s/keys :req-un [::id]
          :opt-un [::created-at
                   ::fullname
                   ::email
                   ::lang
                   ::theme]))

(defn fetch-teams
  []
  (letfn [(on-fetched [state data]
            (let [teams (d/index-by :id data)]
              (assoc state :teams teams)))]
    (ptk/reify ::fetch-teams
      ptk/WatchEvent
      (watch [_ state s]
        (->> (rp/query! :teams)
             (rx/map (fn [data] #(on-fetched % data))))))))

(defn profile-fetched
  [{:keys [fullname id] :as data}]
  (us/verify ::profile data)
  (ptk/reify ::profile-fetched
    IDeref
    (-deref [_] data)

    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc :profile-id id)
          (assoc :profile data)))

    ptk/EffectEvent
    (effect [_ state stream]
      (let [profile (:profile state)]
        (swap! storage assoc :profile profile)
        (i18n/set-locale! (:lang profile))
        (some-> (:theme profile)
                (theme/set-current-theme!))))))

;; --- Fetch Profile

(defn fetch-profile
  []
  (ptk/reify ::fetch-profile
    ptk/WatchEvent
    (watch [_ state stream]
      (->> (rp/query! :profile)
           (rx/map profile-fetched)))))

(defn fetch-profile-and-teams
  "Event used mainly on application bootstrap; it fetches the profile
  and if and only if the fetched profile corresponds to an
  authenticated user; proceed to fetch teams."
  []
  (ptk/reify ::fetch-profile-and-teams
    ptk/WatchEvent
    (watch [_ state stream]
      (rx/merge
       (rx/of (fetch-profile))
       (->> stream
            (rx/filter (ptk/type? ::profile-fetched))
            (rx/take 1)
            (rx/map deref)
            (rx/mapcat (fn [profile]
                         (if (= uuid/zero (:id profile))
                           (rx/empty)
                           (rx/of (fetch-teams))))))))))

;; --- Update Profile

(defn update-profile
  [data]
  (us/assert ::profile data)
  (ptk/reify ::update-profile
    ptk/WatchEvent
    (watch [_ state stream]
      (let [mdata      (meta data)
            on-success (:on-success mdata identity)
            on-error   (:on-error mdata #(rx/throw %))]
        (->> (rp/mutation :update-profile data)
             (rx/catch on-error)
             (rx/mapcat
              (fn [_]
                (rx/merge
                 (->> stream
                      (rx/filter (ptk/type? ::profile-fetched))
                      (rx/take 1)
                      (rx/tap on-success)
                      (rx/ignore))
                 (rx/of (profile-fetched data))))))))))


;; --- Request Email Change

(defn request-email-change
  [{:keys [email] :as data}]
  (us/assert ::us/email email)
  (ptk/reify ::request-email-change
    ptk/WatchEvent
    (watch [_ state stream]
      (let [{:keys [on-error on-success]
             :or {on-error identity
                  on-success identity}} (meta data)]
        (->> (rp/mutation :request-email-change data)
             (rx/tap on-success)
             (rx/catch on-error))))))

;; --- Cancel Email Change

(def cancel-email-change
  (ptk/reify ::cancel-email-change
    ptk/WatchEvent
    (watch [_ state stream]
      (->> (rp/mutation :cancel-email-change {})
           (rx/map (constantly (fetch-profile)))))))

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


(defn mark-onboarding-as-viewed
  ([] (mark-onboarding-as-viewed nil))
  ([{:keys [version]}]
   (ptk/reify ::mark-oboarding-as-viewed
     ptk/WatchEvent
     (watch [_ state stream]
       (let [version (or version (:main @cf/version))
             props   (-> (get-in state [:profile :props])
                         (assoc :onboarding-viewed true)
                         (assoc :release-notes-viewed version))]
         (->> (rp/mutation :update-profile-props {:props props})
              (rx/map (constantly (fetch-profile)))))))))

;; --- Update Photo

(defn update-photo
  [file]
  (us/verify ::di/blob file)
  (ptk/reify ::update-photo
    ptk/WatchEvent
    (watch [_ state stream]
      (let [on-success di/notify-finished-loading
            on-error #(do (di/notify-finished-loading)
                          (di/process-error %))

            prepare
            (fn [file]
              {:file file})]

        (di/notify-start-loading)
        (->> (rx/of file)
             (rx/map di/validate-file)
             (rx/map prepare)
             (rx/mapcat #(rp/mutation :update-profile-photo %))
             (rx/do on-success)
             (rx/map (constantly (fetch-profile)))
             (rx/catch on-error))))))


(defn fetch-users
  [{:keys [team-id] :as params}]
  (us/assert ::us/uuid team-id)
  (letfn [(fetched [users state]
            (->> users
                 (d/index-by :id)
                 (assoc state :users)))]
    (ptk/reify ::fetch-team-users
      ptk/WatchEvent
      (watch [_ state stream]
        (->> (rp/query :team-users {:team-id team-id})
             (rx/map #(partial fetched %)))))))
