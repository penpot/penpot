;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.common
  "A general purpose events."
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.schema :as sm]
   [app.common.types.components-list :as ctkl]
   [app.common.types.team :as ctt]
   [app.config :as cf]
   [app.main.data.modal :as modal]
   [app.main.data.notifications :as ntf]
   [app.main.features :as features]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.util.i18n :refer [tr]]
   [app.util.router :as rt]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SHARE LINK
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn share-link-created
  [link]
  (ptk/reify ::share-link-created
    ptk/UpdateEvent
    (update [_ state]
      (update state :share-links (fnil conj []) link))))

(defn create-share-link
  [params]
  (ptk/reify ::create-share-link
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! :create-share-link params)
           (rx/map share-link-created)))))

(defn delete-share-link
  [{:keys [id] :as link}]
  (ptk/reify ::delete-share-link
    ptk/UpdateEvent
    (update [_ state]
      (update state :share-links
              (fn [links]
                (filterv #(not= id (:id %)) links))))

    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! :delete-share-link {:id id})
           (rx/ignore)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; NOTIFICATIONS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn force-reload!
  []
  (.reload js/location))

(defn hide-notifications!
  []
  (st/emit! (ntf/hide)))

(defn handle-notification
  [{:keys [message code level] :as params}]
  (ptk/reify ::show-notification
    ptk/WatchEvent
    (watch [_ _ _]
      (case code
        :upgrade-version
        (when (or (not= (:version params) (:full cf/version))
                  (true? (:force params)))
          (rx/of (ntf/dialog
                  :content (tr "notifications.by-code.upgrade-version")
                  :controls :inline-actions
                  :type :inline
                  :level level
                  :actions [{:label "Refresh" :callback force-reload!}]
                  :tag :notification)))

        :maintenance
        (rx/of (ntf/dialog
                :content (tr "notifications.by-code.maintenance")
                :controls :inline-actions
                :type level
                :actions [{:label (tr "labels.accept")
                           :callback hide-notifications!}]
                :tag :notification))

        (rx/of (ntf/dialog
                :content message
                :controls :close
                :type level
                :tag :notification))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SHARED LIBRARY
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn show-shared-dialog
  [file-id add-shared]
  (ptk/reify ::show-shared-dialog
    ptk/WatchEvent
    (watch [_ state _]
      (let [features (features/get-team-enabled-features state)
            data     (:workspace-data state)
            file     (:workspace-file state)]
        (->> (if (and data file)
               (rx/of {:name             (:name file)
                       :components-count (count (ctkl/components-seq data))
                       :graphics-count   (count (:media data))
                       :colors-count     (count (:colors data))
                       :typography-count (count (:typographies data))})
               (rp/cmd! :get-file-summary {:id file-id :features features}))
             (rx/map (fn [summary]
                       (let [count (+ (:components-count summary)
                                      (:graphics-count summary)
                                      (:colors-count summary)
                                      (:typography-count summary))]
                         (modal/show
                          {:type :confirm
                           :title (tr "modals.add-shared-confirm.message" (:name summary))
                           :message (if (zero? count) (tr "modals.add-shared-confirm-empty.hint") (tr "modals.add-shared-confirm.hint"))
                           :cancel-label (if (zero? count) (tr "labels.cancel") :omit)
                           :accept-label (tr "modals.add-shared-confirm.accept")
                           :accept-style :primary
                           :on-accept add-shared})))))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Exportations
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private schema:export-files
  [:sequential {:title "Files"}
   [:map {:title "FileParam"}
    [:id ::sm/uuid]
    [:name :string]
    [:project-id ::sm/uuid]
    [:is-shared ::sm/boolean]]])

(def check-export-files!
  (sm/check-fn schema:export-files))

(def valid-export-formats
  #{:binfile-v1 :binfile-v3 :legacy-zip})

(defn export-files
  [files format]
  (dm/assert!
   "expected valid files param"
   (check-export-files! files))

  (dm/assert!
   "expected valid format"
   (contains? valid-export-formats format))

  (ptk/reify ::export-files
    ptk/WatchEvent
    (watch [_ state _]
      (let [features (features/get-team-enabled-features state)
            team-id  (:current-team-id state)]
        (->> (rx/from files)
             (rx/mapcat
              (fn [file]
                (->> (rp/cmd! :has-file-libraries {:file-id (:id file)})
                     (rx/map #(assoc file :has-libraries %)))))
             (rx/reduce conj [])
             (rx/map (fn [files]
                       (modal/show
                        {:type :export
                         :features features
                         :team-id team-id
                         :files files
                         :format format}))))))))

;;;;;;;;;;;;;;;;;;;;;;
;; Team Request
;;;;;;;;;;;;;;;;;;;;;;

(defn create-team-access-request
  [params]
  (ptk/reify ::create-team-access-request
    ptk/WatchEvent
    (watch [_ _ _]
      (let [{:keys [on-success on-error]
             :or {on-success identity
                  on-error rx/throw}} (meta params)]
        (->> (rp/cmd! :create-team-access-request params)
             (rx/tap on-success)
             (rx/catch on-error))))))

(defn- get-change-role-msg
  [role]
  (case role
    :viewer (tr "dashboard.permissions-change.viewer")
    :editor (tr "dashboard.permissions-change.editor")
    :admin  (tr "dashboard.permissions-change.admin")
    :owner  (tr "dashboard.permissions-change.owner")))

(defn change-team-role
  [{:keys [team-id role]}]
  (dm/assert! (uuid? team-id))
  (dm/assert! (contains? ctt/valid-roles role))

  (ptk/reify ::change-team-role
    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (ntf/info (get-change-role-msg role))))

    ptk/UpdateEvent
    (update [_ state]
      (let [permissions (get ctt/permissions-for-role role)]
        (-> state
            (update :permissions merge permissions)
            (update-in [:team :permissions] merge permissions)
            (d/update-in-when [:teams team-id :permissions] merge permissions))))))

(defn team-membership-change
  [{:keys [team-id team-name change]}]
  (dm/assert! (uuid? team-id))
  (ptk/reify ::team-membership-change
    ptk/WatchEvent
    (watch [_ state _]
      (when (= :removed change)
        (let [message (tr "dashboard.removed-from-team" team-name)
              profile (:profile state)]
          (rx/concat
           (rx/of (rt/nav :dashboard-projects {:team-id (:default-team-id profile)}))
           (->> (rx/of (ntf/info message))
                ;; Delay so the navigation can finish
                (rx/delay 250))))))))



