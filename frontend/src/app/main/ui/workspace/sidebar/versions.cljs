;; This Source Code Form is subject to the terms of the Mozilla Public
;; License v. 2.0. If a copy of the MPL was not distributed with this
;; file You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.versions
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
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
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.time :as dt]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def versions
  (l/derived :workspace-versions st/state))

(def versions-stored-days 7)

(defn group-snapshots
  [data]
  (->> (concat
        (->> data
             (filterv #(= "user" (:created-by %)))
             (map #(assoc % :type :version)))
        (->> data
             (filterv #(= "system" (:created-by %)))
             (group-by #(.toISODate ^js (:created-at %)))
             (map (fn [[day entries]]
                    {:type :snapshot
                     :created-at (ct/parse-instant day)
                     :snapshots entries}))))
       (sort-by :created-at)
       (reverse)))

(mf/defc version-entry
  [{:keys [entry profile on-restore-version on-delete-version on-rename-version editing?]}]
  (let [input-ref (mf/use-ref nil)

        show-menu? (mf/use-state false)

        handle-open-menu
        (mf/use-fn
         (fn []
           (reset! show-menu? true)))

        handle-close-menu
        (mf/use-fn
         (fn []
           (reset! show-menu? false)))

        handle-rename-version
        (mf/use-fn
         (mf/deps entry)
         (fn []
           (st/emit! (dwv/update-version-state {:editing (:id entry)}))))

        handle-restore-version
        (mf/use-fn
         (mf/deps entry on-restore-version)
         (fn []
           (when on-restore-version
             (on-restore-version (:id entry)))))

        handle-delete-version
        (mf/use-callback
         (mf/deps entry on-delete-version)
         (fn []
           (when on-delete-version
             (on-delete-version (:id entry)))))

        handle-name-input-focus
        (mf/use-fn
         (fn [event]
           (dom/select-text! (dom/get-target event))))

        handle-name-input-blur
        (mf/use-fn
         (mf/deps entry on-rename-version)
         (fn [event]
           (let [label   (str/trim (dom/get-target-val event))]
             (when (and (not (str/empty? label))
                        (some? on-rename-version))
               (on-rename-version (:id entry) label))
             (st/emit! (dwv/update-version-state {:editing nil})))))

        handle-name-input-key-down
        (mf/use-fn
         (mf/deps handle-name-input-blur)
         (fn [event]
           (cond
             (kbd/enter? event)
             (handle-name-input-blur event)

             (kbd/esc? event)
             (st/emit! (dwv/update-version-state {:editing nil})))))]

    [:li {:class (stl/css :version-entry-wrap)}
     [:div {:class (stl/css :version-entry :is-snapshot)}
      [:img {:class (stl/css :version-entry-avatar)
             :alt (:fullname profile)
             :src (cfg/resolve-profile-photo-url profile)}]

      [:div {:class (stl/css :version-entry-data)}
       (if editing?
         [:input {:class  (stl/css :version-entry-name-edit)
                  :type "text"
                  :ref input-ref
                  :on-focus handle-name-input-focus
                  :on-blur handle-name-input-blur
                  :on-key-down handle-name-input-key-down
                  :auto-focus true
                  :default-value (:label entry)}]

         [:p {:class (stl/css :version-entry-name)}
          (:label entry)])

       [:p {:class (stl/css :version-entry-time)}
        (let [locale (mf/deref i18n/locale)
              time   (dt/timeago (:created-at entry) {:locale locale})]
          [:span {:class (stl/css :date)} time])]]

      [:> icon-button* {:class (stl/css :version-entry-options)
                        :variant "ghost"
                        :aria-label (tr "workspace.versions.version-menu")
                        :on-click handle-open-menu
                        :icon "menu"}]]

     [:& dropdown {:show @show-menu? :on-close handle-close-menu}
      [:ul {:class (stl/css :version-options-dropdown)}
       [:li {:class (stl/css :menu-option)
             :role "button"
             :on-click handle-rename-version} (tr "labels.rename")]
       [:li {:class (stl/css :menu-option)
             :role "button"
             :on-click handle-restore-version} (tr "labels.restore")]
       [:li {:class (stl/css :menu-option)
             :role "button"
             :on-click handle-delete-version} (tr "labels.delete")]]]]))

(mf/defc snapshot-entry
  [{:keys [index is-expanded entry on-toggle-expand on-pin-snapshot on-restore-snapshot]}]

  (let [open-menu (mf/use-state nil)

        handle-toggle-expand
        (mf/use-fn
         (mf/deps index on-toggle-expand)
         (fn []
           (when on-toggle-expand
             (on-toggle-expand index))))

        handle-pin-snapshot
        (mf/use-fn
         (mf/deps on-pin-snapshot)
         (fn [event]
           (let [node  (dom/get-current-target event)
                 id    (-> (dom/get-data node "id") uuid/uuid)]
             (when on-pin-snapshot (on-pin-snapshot id)))))

        handle-restore-snapshot
        (mf/use-fn
         (mf/deps on-restore-snapshot)
         (fn [event]
           (let [node  (dom/get-current-target event)
                 id    (-> (dom/get-data node "id") uuid/uuid)]
             (when on-restore-snapshot (on-restore-snapshot id)))))]

    [:li {:class (stl/css :version-entry-wrap)}
     [:div {:class (stl/css-case :version-entry true
                                 :is-autosave true
                                 :is-expanded is-expanded)}
      [:p {:class (stl/css :version-entry-name)}
       (tr "workspace.versions.autosaved.version" (dt/format (:created-at entry) :date-full))]

      [:button {:class (stl/css :version-entry-snapshots)
                :aria-label (tr "workspace.versions.expand-snapshot")
                :on-click handle-toggle-expand}
       [:> i/icon* {:id i/clock :class (stl/css :icon-clock)}]
       (tr "workspace.versions.autosaved.entry" (count (:snapshots entry)))
       [:> i/icon* {:id i/arrow :class (stl/css :icon-arrow)}]]

      [:ul {:class (stl/css :version-snapshot-list)}
       (for [[idx snapshot] (d/enumerate (:snapshots entry))]
         [:li {:class (stl/css :version-snapshot-entry-wrapper)
               :key (dm/str "snp-" idx)}
          [:div {:class (stl/css :version-snapshot-entry)}
           (str
            (dt/format (:created-at snapshot) :date-full)
            " . "
            (dt/format (:created-at snapshot) :time-24-simple))]

          [:> icon-button* {:variant "ghost"
                            :aria-label (tr "workspace.versions.snapshot-menu")
                            :on-click #(reset! open-menu snapshot)
                            :icon "menu"
                            :class (stl/css :version-snapshot-menu-btn)}]

          [:& dropdown {:show (= @open-menu snapshot)
                        :on-close #(reset! open-menu nil)}
           [:ul {:class (stl/css :version-options-dropdown)}
            [:li {:class (stl/css :menu-option)
                  :role "button"
                  :data-id (dm/str (:id snapshot))
                  :on-click handle-restore-snapshot}
             (tr "workspace.versions.button.restore")]
            [:li {:class (stl/css :menu-option)
                  :role "button"
                  :data-id (dm/str (:id snapshot))
                  :on-click handle-pin-snapshot}
             (tr "workspace.versions.button.pin")]]]])]]]))

(mf/defc versions-toolbox
  []
  (let [profiles   (mf/deref refs/profiles)
        profile    (mf/deref refs/profile)

        expanded   (mf/use-state #{})


        {:keys [status data editing]}
        (mf/deref versions)

        ;; Store users that have a version
        data-users
        (mf/use-memo
         (mf/deps data)
         (fn []
           (into #{} (keep (fn [{:keys [created-by profile-id]}]
                             (when (= "user" created-by) profile-id))) data)))
        data
        (mf/use-memo
         (mf/deps @versions)
         (fn []
           (->> data
                (filter #(or (not (:filter @versions))
                             (and
                              (= "user" (:created-by %))
                              (= (:filter @versions) (:profile-id %)))))
                (group-snapshots))))

        handle-create-version
        (mf/use-fn
         (fn []
           (st/emit! (dwv/create-version))))

        handle-toggle-expand
        (mf/use-fn
         (fn [id]
           (swap! expanded
                  (fn [expanded]
                    (let [has-element? (contains? expanded id)]
                      (cond-> expanded
                        has-element?       (disj id)
                        (not has-element?) (conj id)))))))

        handle-rename-version
        (mf/use-fn
         (fn [id label]
           (st/emit! (dwv/rename-version id label))))


        handle-restore-version
        (mf/use-fn
         (fn [origin id]
           (st/emit!
            (ntf/dialog
             :content (tr "workspace.versions.restore-warning")
             :controls :inline-actions
             :actions [{:label (tr "workspace.updates.dismiss")
                        :type :secondary
                        :callback #(st/emit! (ntf/hide))}
                       {:label (tr "labels.restore")
                        :type :primary
                        :callback #(st/emit! (dwv/restore-version id origin))}]
             :tag :restore-dialog))))

        handle-restore-version-pinned
        (mf/use-fn
         (mf/deps handle-restore-version)
         (fn [id]
           (handle-restore-version :version id)))

        handle-restore-version-snapshot
        (mf/use-fn
         (mf/deps handle-restore-version)
         (fn [id]
           (handle-restore-version :snapshot id)))

        handle-delete-version
        (mf/use-fn
         (fn [id]
           (st/emit! (dwv/delete-version id))))

        handle-pin-version
        (mf/use-fn
         (fn [id]
           (st/emit! (dwv/pin-version id))))

        handle-change-filter
        (mf/use-fn
         (fn [filter]
           (cond
             (= :all filter)
             (st/emit! (dwv/update-version-state {:filter nil}))

             (= :own filter)
             (st/emit! (dwv/update-version-state {:filter (:id profile)}))

             :else
             (st/emit! (dwv/update-version-state {:filter filter})))))]

    (mf/with-effect []
      (st/emit! (dwv/init-version-state)))

    [:div {:class (stl/css :version-toolbox)}
     [:& select
      {:default-value :all
       :aria-label (tr "workspace.versions.filter.label")
       :options (into [{:value :all :label (tr "workspace.versions.filter.all")}
                       {:value :own :label (tr "workspace.versions.filter.mine")}]
                      (->> data-users
                           (keep
                            (fn [id]
                              (let [{:keys [fullname]} (get profiles id)]
                                (when (not= id (:id profile))
                                  {:value id :label (tr "workspace.versions.filter.user" fullname)}))))))
       :on-change handle-change-filter}]

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
                           :on-click handle-create-version
                           :icon "pin"}]]

        (if (empty? data)
          [:div {:class (stl/css :versions-entry-empty)}
           [:div {:class (stl/css :versions-entry-empty-icon)} [:> i/icon* {:id i/history}]]
           [:div {:class (stl/css :versions-entry-empty-msg)} (tr "workspace.versions.empty")]]

          [:ul {:class (stl/css :versions-entries)}
           (for [[idx-entry entry] (->> data (map-indexed vector))]
             (case (:type entry)
               :version
               [:& version-entry {:key idx-entry
                                  :entry entry
                                  :editing? (= (:id entry) editing)
                                  :profile (get profiles (:profile-id entry))
                                  :on-rename-version handle-rename-version
                                  :on-restore-version handle-restore-version-pinned
                                  :on-delete-version handle-delete-version}]

               :snapshot
               [:& snapshot-entry {:key idx-entry
                                   :index idx-entry
                                   :entry entry
                                   :is-expanded (contains? @expanded idx-entry)
                                   :on-toggle-expand handle-toggle-expand
                                   :on-restore-snapshot handle-restore-version-snapshot
                                   :on-pin-snapshot handle-pin-version}]

               nil))])

        [:div {:class (stl/css :autosave-warning)}
         [:div {:class (stl/css :autosave-warning-text)}
          (tr "workspace.versions.warning.text" versions-stored-days)]

         [:div {:class (stl/css :autosave-warning-subtext)}
          [:> i18n/tr-html*
           {:tag-name "div"
            :content (tr "workspace.versions.warning.subtext"
                         "mailto:support@penpot.app")}]]]])]))
