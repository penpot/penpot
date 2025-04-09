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
   [app.main.data.helpers :as dsh]
   [app.main.data.modal :as modal]
   [app.main.data.notifications :as ntf]
   [app.main.data.persistence :as-alias dps]
   [app.main.repo :as rp]
   [app.main.router :as rt]
   [app.main.store :as st]
   [app.util.dom :as-alias dom]
   [app.util.i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(declare go-to-dashboard-recent)

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
  [{:keys [message code] :as params}]
  (ptk/reify ::show-notification
    ptk/WatchEvent
    (watch [_ _ _]
      (case code
        :upgrade-version
        (rx/of (ntf/dialog
                :content (tr "notifications.by-code.upgrade-version")
                :accept {:label (tr "labels.refresh")
                         :callback force-reload!}
                :tag :notification))

        :maintenance
        (rx/of (ntf/dialog
                :content (tr "notifications.by-code.maintenance")
                :accept {:label (tr "labels.accept")
                         :callback hide-notifications!}
                :tag :notification))

        (rx/of (ntf/dialog
                :content message
                :accept {:label (tr "labels.close")
                         :callback hide-notifications!}
                :tag :notification))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SHARED LIBRARY
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn show-shared-dialog
  [file-id add-shared]
  (ptk/reify ::show-shared-dialog
    ptk/WatchEvent
    (watch [_ state _]
      (let [features (get state :features)
            file     (dsh/lookup-file state)
            data     (get file :data)]

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
      (let [features (get state :features)
            team-id  (get state :current-team-id)]
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
    ptk/UpdateEvent
    (update [_ state]
      ;; FIXME: Remove on 2.5
      (assoc state :current-team-id (dm/get-in state [:profile :default-team-id])))

    ptk/WatchEvent
    (watch [_ state _]
      (when (= :removed change)
        (let [message (tr "dashboard.removed-from-team" team-name)
              team-id (-> state :profile :default-team-id)]
          (rx/concat
           (rx/of (go-to-dashboard-recent :team-id team-id))
           (->> (rx/of (ntf/info message))
                ;; Delay so the navigation can finish
                (rx/delay 250))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; NAVEGATION EVENTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn go-to-feedback
  []
  (ptk/reify ::go-to-feedback
    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (rt/nav :settings-feedback {}
                     ::rt/new-window true
                     ::rt/window-name "penpot-feedback")))))

(defn go-to-dashboard-files
  [& {:keys [project-id team-id] :as options}]
  (ptk/reify ::go-to-dashboard-files
    ptk/WatchEvent
    (watch [_ state _]
      (let [profile    (get state :profile)
            team-id    (or team-id (:current-team-id state))
            project-id (if (= project-id :default)
                         (:default-project-id profile)
                         project-id)

            params     {:team-id team-id
                        :project-id project-id}]
        (rx/of (rt/nav :dashboard-files params options))))))

(defn go-to-dashboard-search
  [& {:keys [term] :as options}]
  (ptk/reify ::go-to-dashboard-search
    ptk/WatchEvent
    (watch [_ state stream]
      (let [team-id (:current-team-id state)]
        (rx/merge
         (->> (rx/of (rt/nav :dashboard-search
                             {:team-id team-id
                              :search-term term})
                     (modal/hide))
              (rx/observe-on :async))

         (->> stream
              (rx/filter (ptk/type? ::rt/navigated))
              (rx/take 1)
              (rx/map (fn [_]
                        (ptk/event ::dom/focus-element
                                   {:name "search-input"})))))))))

(defn go-to-dashboard-libraries
  [& {:keys [team-id] :as options}]
  (ptk/reify ::go-to-dashboard-libraries
    ptk/WatchEvent
    (watch [_ state _]
      (let [team-id (or team-id (:current-team-id state))]
        (rx/of (rt/nav :dashboard-libraries {:team-id team-id}))))))


(defn go-to-dashboard-fonts
  [& {:keys [team-id] :as options}]
  (ptk/reify ::go-to-dashboard-fonts
    ptk/WatchEvent
    (watch [_ state _]
      (let [team-id (or team-id (:current-team-id state))]
        (rx/of (rt/nav :dashboard-fonts {:team-id team-id}))))))

(defn go-to-dashboard-recent
  [& {:keys [team-id] :as options}]
  (ptk/reify ::go-to-dashboard-recent
    ptk/WatchEvent
    (watch [_ state _]
      (let [profile (get state :profile)
            team-id (cond
                      (= :default team-id)
                      (:default-team-id profile)

                      (uuid? team-id)
                      team-id

                      :else
                      (:current-team-id state))
            params  {:team-id team-id}]
        (rx/of (modal/hide)
               (rt/nav :dashboard-recent params options))))))

(defn go-to-dashboard-members
  [& {:as options}]
  (ptk/reify ::go-to-dashboard-members
    ptk/WatchEvent
    (watch [_ state _]
      (let [team-id (:current-team-id state)]
        (rx/of (rt/nav :dashboard-members {:team-id team-id}))))))

(defn go-to-dashboard-invitations
  [& {:as options}]
  (ptk/reify ::go-to-dashboard-invitations
    ptk/WatchEvent
    (watch [_ state _]
      (let [team-id (:current-team-id state)]
        (rx/of (rt/nav :dashboard-invitations {:team-id team-id}))))))

(defn go-to-dashboard-webhooks
  [& {:as options}]
  (ptk/reify ::go-to-dashboard-webhooks
    ptk/WatchEvent
    (watch [_ state _]
      (let [team-id (:current-team-id state)]
        (rx/of (rt/nav :dashboard-webhooks {:team-id team-id}))))))

(defn go-to-dashboard-settings
  [& {:as options}]
  (ptk/reify ::go-to-dashboard-settings
    ptk/WatchEvent
    (watch [_ state _]
      (let [team-id (:current-team-id state)]
        (rx/of (rt/nav :dashboard-settings {:team-id team-id}))))))

(defn go-to-workspace
  [& {:keys [team-id file-id page-id layout] :as options}]
  (ptk/reify ::go-to-workspace
    ptk/WatchEvent
    (watch [_ state _]
      (let [team-id (or team-id (:current-team-id state))
            file-id (or file-id (:current-file-id state))
            page-id (or page-id (:current-page-id state)
                        (-> (dsh/lookup-file-data state file-id)
                            (get :pages)
                            (first)))

            params  (-> (rt/get-params state)
                        (assoc :team-id team-id)
                        (assoc :file-id file-id)
                        (assoc :page-id page-id)
                        (update :layout  #(or layout %))
                        (d/without-nils))]
        (rx/of (rt/nav :workspace params options))))))

(defn go-to-viewer
  [& {:keys [file-id page-id section frame-id index] :as options}]
  (ptk/reify ::go-to-viewer
    ptk/WatchEvent
    (watch [_ state _]
      (let [page-id (or page-id (:current-page-id state))
            file-id (or file-id (:current-file-id state))
            section (or section :interactions)
            params  {:file-id file-id
                     :page-id page-id
                     :section section
                     :frame-id frame-id
                     :index index}
            params  (d/without-nils params)
            name    (dm/str "viewer-" file-id)
            options (merge {::rt/new-window true
                            ::rt/window-name name}
                           options)]
        (rx/of ::dps/force-persist
               (rt/nav :viewer params options))))))

