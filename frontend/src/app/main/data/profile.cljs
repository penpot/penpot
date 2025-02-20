;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.profile
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.schema :as sm]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.event :as ev]
   [app.main.data.media :as di]
   [app.main.data.notifications :as ntf]
   [app.main.data.team :as-alias dtm]
   [app.main.repo :as rp]
   [app.main.router :as rt]
   [app.plugins.register :as plugins.register]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.storage :as storage]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(declare update-profile-props)

;; --- SCHEMAS

(def ^:private
  schema:profile
  [:map {:title "Profile"}
   [:id ::sm/uuid]
   [:created-at {:optional true} :any]
   [:fullname {:optional true} :string]
   [:email {:optional true} :string]
   [:lang {:optional true} :string]
   [:theme {:optional true} :string]])

(def check-profile
  (sm/check-fn schema:profile))

;; --- HELPERS

(defn is-authenticated?
  [{:keys [id]}]
  (and (uuid? id) (not= id uuid/zero)))

;; --- EVENT: fetch-profile

(defn set-profile
  "Initialize profile state, only logged-in profile data should be
  passed to this event"
  [{:keys [id] :as profile}]
  (ptk/reify ::set-profile
    IDeref
    (-deref [_] profile)

    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc :profile-id id)
          (assoc :profile profile)))

    ptk/EffectEvent
    (effect [_ state _]
      (let [profile (:profile state)]
        (swap! storage/user assoc :profile profile)
        (i18n/set-locale! (:lang profile))
        (plugins.register/init)))))

(def profile-fetched?
  (ptk/type? ::profile-fetched))

;; FIXME: make it as general purpose handler, not only on profile
(defn- on-fetch-profile-exception
  [cause]
  (let [data (ex-data cause)]
    (if (and (= :authorization (:type data))
             (= :challenge-required (:code data)))
      (let [path (rt/get-current-path)
            href (->> path
                      (js/encodeURIComponent)
                      (str "/challenge.html?redirect="))]
        (rx/of (rt/nav-raw :href href)))
      (rx/throw cause))))

(defn fetch-profile
  []
  (ptk/reify ::fetch-profile
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! :get-profile)
           (rx/map (partial ptk/data-event ::profile-fetched))
           (rx/catch on-fetch-profile-exception)))))

(defn refresh-profile
  []
  (ptk/reify ::refresh-profile
    ptk/WatchEvent
    (watch [_ _ stream]
      (rx/merge
       (rx/of (fetch-profile))
       (->> stream
            (rx/filter profile-fetched?)
            (rx/map deref)
            (rx/filter is-authenticated?)
            (rx/take 1)
            (rx/map set-profile))))))

;; --- Update Profile

(defn persist-profile
  [& {:as opts}]
  (ptk/reify ::persist-profile
    ptk/WatchEvent
    (watch [_ state _]
      (let [on-success (:on-success opts identity)
            on-error   (:on-error opts rx/throw)
            profile    (:profile state)
            params     (select-keys profile [:fullname :lang :theme])]
        (->> (rp/cmd! :update-profile params)
             (rx/tap on-success)
             (rx/map set-profile)
             (rx/catch on-error))))))

(defn update-profile
  "Optimistic update of the current profile.

  Props are ignored because there is a specific event for updating
  props"
  [profile]
  (dm/assert!
   "expected valid profile data"
   (check-profile profile))

  (ptk/reify ::update-profile
    ptk/WatchEvent
    (watch [_ state _]
      (let [profile' (get state :profile)
            profile  (d/deep-merge profile' (dissoc profile :props))]

        (rx/merge
         (rx/of (set-profile profile))

         (when (not= (:theme profile)
                     (:theme profile'))
           (rx/of (ptk/data-event ::ev/event
                                  {::ev/name "activate-theme"
                                   ::ev/origin "settings"
                                   :theme (:theme profile)}))))))))

;; --- Toggle Theme

(defn toggle-theme
  []
  (ptk/reify ::toggle-theme
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:profile :theme]
                 (fn [current]
                   (if (= current "default")
                     "light"
                     "default"))))

    ptk/WatchEvent
    (watch [it state _]
      (let [profile (get state :profile)
            origin  (::ev/origin (meta it))]
        (rx/of (ptk/data-event ::ev/event {:theme (:theme profile)
                                           ::ev/name "activate-theme"
                                           ::ev/origin origin})
               (persist-profile))))))

;; --- Request Email Change

(defn request-email-change
  [{:keys [email] :as data}]
  (dm/assert! ::us/email email)
  (ptk/reify ::request-email-change
    ev/Event
    (-data [_]
      {:email email})

    ptk/WatchEvent
    (watch [_ _ _]
      (let [{:keys [on-error on-success]
             :or {on-error identity
                  on-success identity}} (meta data)]
        (->> (rp/cmd! :request-email-change data)
             (rx/tap on-success)
             (rx/catch on-error))))))

;; --- Cancel Email Change

(def cancel-email-change
  (ptk/reify ::cancel-email-change
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! :cancel-email-change {})
           (rx/map (constantly (refresh-profile)))))))

;; --- Update Password (Form)

(def schema:update-password
  [:map {:closed true}
   [:password-1 :string]
   [:password-2 :string]
   ;; Social registered users don't have old-password
   [:password-old {:optional true} [:maybe :string]]])

(defn update-password
  [data]
  (dm/assert!
   "expected valid parameters"
   (sm/check schema:update-password data))

  (ptk/reify ::update-password
    ev/Event
    (-data [_] {})

    ptk/WatchEvent
    (watch [_ _ _]
      (let [{:keys [on-error on-success]
             :or {on-error identity
                  on-success identity}} (meta data)
            params {:old-password (:password-old data)
                    :password (:password-1 data)}]
        (->> (rp/cmd! :update-profile-password params)
             (rx/tap on-success)
             (rx/catch (fn [err]
                         (on-error err)
                         (rx/empty)))
             (rx/ignore))))))

(def ^:private schema:update-notifications
  [:map {:title "NotificationsForm"}
   [:dashboard-comments [::sm/one-of #{:all :partial :none}]]
   [:email-comments [::sm/one-of #{:all :partial :none}]]
   [:email-invites [::sm/one-of #{:all :none}]]])

(def ^:private check-update-notifications-params
  (sm/check-fn schema:update-notifications))

(defn update-notifications
  [data]
  (assert (check-update-notifications-params data))
  (ptk/reify ::update-notifications
    ev/Event
    (-data [_] {})

    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:profile :props] assoc :notifications data))

    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! :update-profile-notifications data)
           (rx/map #(ntf/success (tr "dashboard.notifications.notifications-saved")))))))

(defn update-profile-props
  [props]
  (ptk/reify ::update-profile-props
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:profile :props] merge props))

    ;; TODO: for the release 1.13 we should skip fetching profile and just use
    ;; the response value of update-profile-props RPC call
    ;; FIXME
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! :update-profile-props {:props props})
           (rx/map (constantly (refresh-profile)))))))

(defn mark-onboarding-as-viewed
  ([] (mark-onboarding-as-viewed nil))
  ([{:keys [version]}]
   (ptk/reify ::mark-onboarding-as-viewed
     ptk/WatchEvent
     (watch [_ _ _]
       (let [version (or version (:main cf/version))
             props   {:onboarding-viewed true
                      :release-notes-viewed version}]
         (->> (rp/cmd! :update-profile-props {:props props})
              (rx/map (constantly (refresh-profile)))))))))

(defn mark-questions-as-answered
  [onboarding-questions]
  (ptk/reify ::mark-questions-as-answered
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:profile :props] assoc :onboarding-questions-answered true))

    ptk/WatchEvent
    (watch [_ _ _]
      (let [props {:onboarding-questions-answered true
                   :onboarding-questions onboarding-questions}]
        (->> (rp/cmd! :update-profile-props {:props props})
             (rx/map (constantly (refresh-profile))))))))

;; --- Update Photo

(defn update-photo
  [file]
  (dm/assert!
   "expected a valid blob for `file` param"
   (di/blob? file))

  (ptk/reify ::update-photo
    ev/Event
    (-data [_] {})

    ptk/WatchEvent
    (watch [_ _ _]
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
             (rx/mapcat #(rp/cmd! :update-profile-photo %))
             (rx/tap on-success)
             (rx/map (constantly (refresh-profile)))
             (rx/catch on-error))))))

(defn fetch-file-comments-users
  [{:keys [team-id]}]
  (dm/assert! (uuid? team-id))
  (letfn [(fetched [users state]
            (->> users
                 (d/index-by :id)
                 (assoc state :file-comments-users)))]
    (ptk/reify ::fetch-file-comments-users
      ptk/WatchEvent
      (watch [_ state _]
        (let [share-id (-> state :viewer-local :share-id)]
          (->> (rp/cmd! :get-profiles-for-file-comments {:team-id team-id :share-id share-id})
               (rx/map #(partial fetched %))))))))

;; --- EVENT: request-account-deletion

(def profile-deleted?
  (ptk/type? ::profile-deleted))

(defn request-account-deletion
  [params]
  (ptk/reify ::request-account-deletion
    ptk/WatchEvent
    (watch [_ _ _]
      (let [{:keys [on-error on-success]
             :or {on-error rx/throw
                  on-success identity}} (meta params)]
        (->> (rp/cmd! :delete-profile {})
             (rx/tap on-success)
             (rx/map (fn [_]
                       (ptk/data-event ::profile-deleted params)))
             (rx/catch on-error)
             (rx/delay-at-least 300))))))

;; --- EVENT: request-profile-recovery

(def ^:private
  schema:request-profile-recovery
  [:map {:title "request-profile-recovery" :closed true}
   [:email ::sm/email]])

(defn request-profile-recovery
  [data]

  (dm/assert!
   "expected valid parameters"
   (sm/check schema:request-profile-recovery data))

  (ptk/reify ::request-profile-recovery
    ptk/WatchEvent
    (watch [_ _ _]
      (let [{:keys [on-error on-success]
             :or {on-error rx/throw
                  on-success identity}} (meta data)]

        (->> (rp/cmd! :request-profile-recovery data)
             (rx/tap on-success)
             (rx/catch on-error))))))

;; --- EVENT: recover-profile (Password)

(def ^:private
  schema:recover-profile
  [:map {:title "recover-profile" :closed true}
   [:password :string]
   [:token :string]])

(defn recover-profile
  [data]
  (dm/assert!
   "expected valid arguments"
   (sm/check schema:recover-profile data))

  (ptk/reify ::recover-profile
    ptk/WatchEvent
    (watch [_ _ _]
      (let [{:keys [on-error on-success]
             :or {on-error rx/throw
                  on-success identity}} (meta data)]
        (->> (rp/cmd! :recover-profile data)
             (rx/tap on-success)
             (rx/catch on-error))))))

;; --- EVENT: fetch-team-webhooks

(defn access-tokens-fetched
  [access-tokens]
  (ptk/reify ::access-tokens-fetched
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :access-tokens access-tokens))))

(defn fetch-access-tokens
  []
  (ptk/reify ::fetch-access-tokens
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! :get-access-tokens)
           (rx/map access-tokens-fetched)))))

;; --- EVENT: create-access-token

(defn access-token-created
  [access-token]
  (ptk/reify ::access-token-created
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :access-token-created access-token))))

(defn create-access-token
  [{:keys [] :as params}]
  (ptk/reify ::create-access-token
    ptk/WatchEvent
    (watch [_ _ _]
      (let [{:keys [on-success on-error]
             :or {on-success identity
                  on-error rx/throw}} (meta params)]
        (->> (rp/cmd! :create-access-token params)
             (rx/map access-token-created)
             (rx/tap on-success)
             (rx/catch on-error))))))

;; --- EVENT: delete-access-token

(defn delete-access-token
  [{:keys [id] :as params}]
  (us/assert! ::us/uuid id)
  (ptk/reify ::delete-access-token
    ptk/WatchEvent
    (watch [_ _ _]
      (let [{:keys [on-success on-error]
             :or {on-success identity
                  on-error rx/throw}} (meta params)]
        (->> (rp/cmd! :delete-access-token params)
             (rx/tap on-success)
             (rx/catch on-error))))))

