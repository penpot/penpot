;; This Source Code Form is subject to the terms of the Mozilla Public
;; License v. 2.0. If a copy of the MPL was not distributed with this
;; file You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.versions
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cfg]
   [app.main.data.notifications :as ntf]
   [app.main.data.workspace.versions :as dwv]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.components.select :refer [select]]
   [app.main.ui.dashboard.subscription :refer [get-subscription-type]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.main.ui.ds.product.cta :refer [cta*]]
   [app.main.ui.ds.product.milestone :refer [milestone*]]
   [app.main.ui.ds.product.milestone-group :refer [milestone-group*]]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [cuerdas.core :as str]
   [lambdaisland.uri :as u]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def ^:private versions
  (l/derived :workspace-versions st/state))

(defn- get-versions-stored-days
  [team]
  (let [subscription-type (get-subscription-type (:subscription team))]
    (cond
      (= subscription-type "unlimited") 30
      (= subscription-type "enterprise") 90
      :else 7)))

(defn- get-versions-warning-subtext
  [team]
  (let [subscription-type   (get-subscription-type (:subscription team))
        is-owner?           (-> team :permissions :is-owner)
        email-owner         (:email (some #(when (:is-owner %) %) (:members team)))
        support-email       "support@penpot.app"
        go-to-subscription  (dm/str (u/join cfg/public-uri "#/settings/subscriptions"))]

    (if (contains? cfg/flags :subscriptions)
      (if is-owner?
        (if (= "enterprise" subscription-type)
          (tr "subscription.workspace.versions.warning.enterprise.subtext-owner" support-email support-email)
          (tr "subscription.workspace.versions.warning.subtext-owner" go-to-subscription))
        (tr "subscription.workspace.versions.warning.subtext-member" email-owner email-owner))
      (tr "workspace.versions.warning.subtext" support-email))))

(defn- group-snapshots
  [data]
  (->> (concat
        (->> data
             (filter #(= "user" (:created-by %)))
             (map #(assoc % :type :version)))
        (->> data
             (filter #(= "system" (:created-by %)))
             (group-by #(ct/format-inst (:created-at %) :iso-date))
             (map (fn [[day entries]]
                    {:type :snapshot
                     :created-at (ct/inst day)
                     :snapshots entries}))))
       (sort-by :created-at)
       (map-indexed (fn [index item]
                      (assoc item :index index)))
       (reverse)))

(defn- open-restore-version-dialog
  [origin id]
  (st/emit! (ntf/dialog
             :content (tr "workspace.versions.restore-warning")
             :controls :inline-actions
             :cancel {:label (tr "workspace.updates.dismiss")
                      :callback #(st/emit! (ntf/hide))}
             :accept {:label (tr "labels.restore")
                      :callback #(st/emit! (dwv/restore-version id origin))}
             :tag :restore-dialog)))

(mf/defc version-entry*
  {::mf/private true}
  [{:keys [entry current-profile on-restore on-delete on-rename on-lock on-unlock on-edit on-cancel-edit is-editing]}]
  (let [show-menu? (mf/use-state false)
        profiles   (mf/deref refs/profiles)

        created-by (get profiles (:profile-id entry))

        on-open-menu
        (mf/use-fn #(reset! show-menu? true))

        on-close-menu
        (mf/use-fn #(reset! show-menu? false))

        on-edit
        (mf/use-fn
         (mf/deps on-edit entry)
         (fn [event]
           (on-edit (:id entry) event)))

        on-restore
        (mf/use-fn
         (mf/deps entry on-restore)
         (fn []
           (when (fn? on-restore)
             (on-restore (:id entry)))))

        on-delete
        (mf/use-callback
         (mf/deps entry on-delete)
         (fn [event]
           (when (fn? on-delete)
             (on-delete (:id entry) event))))

        on-lock
        (mf/use-callback
         (mf/deps entry on-lock)
         (fn []
           (when on-lock
             (on-lock (:id entry)))))

        on-unlock
        (mf/use-callback
         (mf/deps entry on-unlock)
         (fn []
           (when on-unlock
             (on-unlock (:id entry)))))

        on-name-input-focus
        (mf/use-fn
         (fn [event]
           (dom/select-text! (dom/get-target event))))

        on-name-input-blur
        (mf/use-fn
         (mf/deps entry on-rename on-cancel-edit)
         (fn [event]
           (let [label (str/trim (dom/get-target-val event))]
             (if (and (not (str/empty? label))
                      (fn? on-rename))
               (on-rename (:id entry) label event)
               (on-cancel-edit (:id entry) event)))))

        on-name-input-key-down
        (mf/use-fn
         (mf/deps entry on-cancel-edit on-name-input-blur)
         (fn [event]
           (cond
             (kbd/enter? event)
             (on-name-input-blur event)

             (kbd/esc? event)
             (when (fn? on-cancel-edit)
               (on-cancel-edit (:id entry) event)))))]

    [:li {:class (stl/css :version-entry-wrap)}
     [:> milestone* {:label (:label entry)
                     :profile created-by
                     :editing is-editing
                     :created-at (:created-at entry)
                     :locked (some? (:locked-by entry))
                     :on-open-menu on-open-menu
                     :on-focus-input on-name-input-focus
                     :on-blur-input on-name-input-blur
                     :on-key-down-input on-name-input-key-down}]

     [:& dropdown {:show @show-menu?
                   :on-close on-close-menu}
      (let [current-user-id  (:id current-profile)
            locked-by-id     (:locked-by entry)
            im-the-owner?    (= current-user-id (:id created-by))
            is-locked-by-me? (= current-user-id locked-by-id)
            is-locked?       (some? locked-by-id)
            can-delete?      (or (not is-locked?)
                                 (and is-locked?
                                      is-locked-by-me?))]
        [:ul {:class (stl/css :version-options-dropdown)}
         (when im-the-owner?
           [:li {:class (stl/css :menu-option)
                 :role "button"
                 :on-click on-edit}
            (tr "labels.rename")])

         [:li {:class (stl/css :menu-option)
               :role "button"
               :on-click on-restore}
          (tr "labels.restore")]

         (cond
           is-locked-by-me?
           [:li {:class (stl/css :menu-option)
                 :role "button"
                 :on-click on-unlock}
            (tr "labels.unlock")]

           (and im-the-owner? (not is-locked?))
           [:li {:class (stl/css :menu-option)
                 :role "button"
                 :on-click on-lock}
            (tr "labels.lock")])

         (when can-delete?
           [:li {:class (stl/css :menu-option)
                 :role "button"
                 :on-click on-delete}
            (tr "labels.delete")])])]]))

(mf/defc snapshot-entry*
  [{:keys [entry on-pin-snapshot on-restore-snapshot]}]

  (let [open-menu* (mf/use-state nil)
        entry-ref (mf/use-ref nil)

        on-pin-snapshot
        (mf/use-fn
         (mf/deps on-pin-snapshot)
         (fn [event]
           (let [node  (dom/get-current-target event)
                 id    (-> node
                           (dom/get-data "id")
                           (uuid/parse))]
             (when (fn? on-pin-snapshot)
               (on-pin-snapshot id event)))))

        on-restore-snapshot
        (mf/use-fn
         (mf/deps on-restore-snapshot)
         (fn [event]
           (let [node  (dom/get-current-target event)
                 id    (-> node
                           (dom/get-data "id")
                           (uuid/parse))]
             (when (fn? on-restore-snapshot)
               (on-restore-snapshot id event)))))

        on-open-snapshot-menu
        (mf/use-fn
         (mf/deps entry)
         (fn [index event]
           (let [snapshot   (nth (:snapshots entry) index)
                 current-bb (-> entry-ref mf/ref-val dom/get-bounding-rect :top)
                 target-bb  (-> event dom/get-target dom/get-bounding-rect :top)
                 offset     (+ (- target-bb current-bb) 32)]
             (swap! open-menu* assoc
                    :snapshot (:id snapshot)
                    :offset offset))))]

    [:li {:ref entry-ref :class (stl/css :version-entry-wrap)}
     [:> milestone-group*
      {:label (tr "workspace.versions.autosaved.version"
                  (ct/format-inst (:created-at entry) :localized-date))
       :snapshots (mapv :created-at (:snapshots entry))
       :on-menu-click on-open-snapshot-menu}]

     [:& dropdown {:show (some? @open-menu*)
                   :on-close #(reset! open-menu* nil)}
      [:ul {:class (stl/css :version-options-dropdown)
            :style {"--offset" (dm/str (:offset @open-menu*) "px")}}
       [:li {:class (stl/css :menu-option)
             :role "button"
             :data-id (dm/str (:snapshot @open-menu*))
             :on-click on-restore-snapshot}
        (tr "workspace.versions.button.restore")]
       [:li {:class (stl/css :menu-option)
             :role "button"
             :data-id (dm/str (:snapshot @open-menu*))
             :on-click on-pin-snapshot}
        (tr "workspace.versions.button.pin")]]]]))

(mf/defc versions-toolbox*
  []
  (let [profiles (mf/deref refs/profiles)
        profile  (mf/deref refs/profile)
        team     (mf/deref refs/team)

        {:keys [status data editing] :as state}
        (mf/deref versions)

        users
        (mf/with-memo [data]
          (into #{}
                (keep (fn [{:keys [created-by profile-id]}]
                        (when (= "user" created-by)
                          profile-id)))
                data))

        entries
        (mf/with-memo [state]
          (->> (:data state)
               (filter #(or (not (:filter state))
                            (and (= "user" (:created-by %))
                                 (= (:filter state) (:profile-id %)))))
               (group-snapshots)))

        on-create-version
        (mf/use-fn
         (fn [] (st/emit! (dwv/create-version))))

        on-edit-version
        (mf/use-fn
         (fn [id _event]
           (st/emit! (dwv/update-versions-state {:editing id}))))

        on-cancel-version-edition
        (mf/use-fn
         (fn [_id _event]
           (st/emit! (dwv/update-versions-state {:editing nil}))))

        on-rename-version
        (mf/use-fn
         (fn [id label]
           (st/emit! (dwv/rename-version id label))))

        on-restore-version
        (mf/use-fn
         (fn [id _event]
           (open-restore-version-dialog :version id)))

        on-restore-snapshot
        (mf/use-fn
         (fn [id _event]
           (open-restore-version-dialog :snapshot id)))

        on-delete-version
        (mf/use-fn
         (fn [id]
           (st/emit! (dwv/delete-version id))))

        on-pin-version
        (mf/use-fn
         (fn [id] (st/emit! (dwv/pin-version id))))

        on-lock-version
        (mf/use-fn
         (fn [id]
           (st/emit! (dwv/lock-version id))))

        on-unlock-version
        (mf/use-fn
         (fn [id]
           (st/emit! (dwv/unlock-version id))))

        on-change-filter
        (mf/use-fn
         (fn [filter]
           (cond
             (= :all filter)
             (st/emit! (dwv/update-versions-state {:filter nil}))

             (= :own filter)
             (st/emit! (dwv/update-versions-state {:filter (:id profile)}))

             :else
             (st/emit! (dwv/update-versions-state {:filter filter})))))

        options
        (mf/with-memo [users profile]
          (let [current-profile-id (get profile :id)]
            (into [{:value :all :label (tr "workspace.versions.filter.all")}
                   {:value :own :label (tr "workspace.versions.filter.mine")}]
                  (keep (fn [id]
                          (when (not= id current-profile-id)
                            (when-let [fullname (-> profiles (get id) (get :fullname))]
                              {:value id :label (tr "workspace.versions.filter.user" fullname)}))))
                  users)))]

    (mf/with-effect []
      (st/emit! (dwv/init-versions-state)))

    [:div {:class (stl/css :version-toolbox)}
     [:& select
      {:default-value :all
       :aria-label (tr "workspace.versions.filter.label")
       :options options
       :on-change on-change-filter}]

     (cond
       (= status :loading)
       [:div {:class (stl/css :versions-entry-empty)}
        [:div {:class (stl/css :versions-entry-empty-msg)} (tr "workspace.versions.loading")]]

       (= status :loaded)
       [:*
        [:div {:class (stl/css :version-save-version)}
         (tr "workspace.versions.button.save")
         [:> icon-button* {:variant "ghost"
                           :aria-label (tr "workspace.versions.button.save")
                           :on-click on-create-version
                           :icon i/pin}]]

        (if (empty? data)
          [:div {:class (stl/css :versions-entry-empty)}
           [:div {:class (stl/css :versions-entry-empty-icon)} [:> icon* {:icon-id i/history}]]
           [:div {:class (stl/css :versions-entry-empty-msg)} (tr "workspace.versions.empty")]]

          [:ul {:class (stl/css :versions-entries)}
           (for [entry entries]
             (case (:type entry)
               :version
               [:> version-entry* {:key (:index entry)
                                   :entry entry
                                   :is-editing (= (:id entry) editing)
                                   :current-profile profile
                                   :on-edit on-edit-version
                                   :on-cancel-edit on-cancel-version-edition
                                   :on-rename on-rename-version
                                   :on-restore on-restore-version
                                   :on-delete on-delete-version
                                   :on-lock on-lock-version
                                   :on-unlock on-unlock-version}]

               :snapshot
               [:> snapshot-entry* {:key (:index entry)
                                    :entry entry
                                    :on-restore-snapshot on-restore-snapshot
                                    :on-pin-snapshot on-pin-version}]

               nil))])

        [:> cta* {:title (tr "workspace.versions.warning.text" (get-versions-stored-days team))}
         [:> i18n/tr-html*
          {:tag-name "div"
           :class (stl/css :cta)
           :content (get-versions-warning-subtext team)}]]])]))
