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
   [app.config :as cfg]
   [app.main.refs :as refs]
   [app.main.repo :as rp]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.components.select :refer [select]]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n]
   [app.util.keyboard :as kbd]
   [app.util.time :as dt]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(def versions
  (atom {:status :loading :data nil}))

(defn group-snapshots
  [data]
  (let [result
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
             (reverse))]

    (.log js/console (clj->js data) (clj->js result))
    result))

(defn fetch-versions
  [file-id]
  (->> (rp/cmd! :get-file-snapshots {:file-id file-id})
       (rx/subs! (fn [data]
                   (reset! versions {:status :loaded :data (group-snapshots data)})))))

(defn create-version
  [file-id]
  (->> (rp/cmd! :take-file-snapshot {:file-id file-id})
       (rx/mapcat #(rp/cmd! :get-file-snapshots {:file-id file-id}))
       (rx/subs! (fn [data]
                   (reset! versions {:status :loaded :data (group-snapshots data)})))))

(defn rename-version
  [file-id id label]
  (->> (rp/cmd! :update-file-snapshot {:id id :label label})
       (rx/mapcat #(rp/cmd! :get-file-snapshots {:file-id file-id}))
       (rx/subs! (fn [data]
                   (reset! versions {:status :loaded :data (group-snapshots data)})))))

(defn restore-version
  [file-id id]
  (->> (rp/cmd! :restore-file-snapshot {:file-id file-id :id id})
       (rx/subs! #(dom/reload-current-window))))

(defn delete-version
  [file-id id]
  (->> (rp/cmd! :remove-file-snapshot {:id id})
       (rx/mapcat #(rp/cmd! :get-file-snapshots {:file-id file-id}))
       (rx/subs! (fn [data]
                   (reset! versions {:status :loaded :data (group-snapshots data)})))))

(defn pin-version
  [file-id id]
  (->> (rp/cmd! :update-file-snapshot {:id id :created-by "user"})
       (rx/mapcat #(rp/cmd! :get-file-snapshots {:file-id file-id}))
       (rx/subs! (fn [data]
                   (reset! versions {:status :loaded :data (group-snapshots data)})))))

(mf/defc version-entry
  [{:keys [entry profile on-restore-version on-delete-version on-rename-version]}]
  (let [input-ref (mf/use-ref nil)

        show-menu? (mf/use-state false)
        editing?   (mf/use-state false)

        handle-open-menu
        (mf/use-callback
         (fn []
           (reset! show-menu? true)))

        handle-close-menu
        (mf/use-callback
         (fn []
           (reset! show-menu? false)))

        handle-rename-version
        (mf/use-callback
         (fn []
           (reset! editing? true)))

        handle-restore-version
        (mf/use-callback
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
        (mf/use-callback
         (fn [event]
           (dom/select-text! (dom/get-target event))))

        handle-name-input-blur
        (mf/use-callback
         (mf/deps entry on-rename-version)
         (fn [event]
           (let [label   (str/trim (dom/get-target-val event))]
             (when (and (not (str/empty? label))
                        (some? on-rename-version))
               (on-rename-version (:id entry) label))
             (reset! editing? false))))

        handle-name-input-key-down
        (mf/use-callback
         (mf/deps handle-name-input-blur)
         (fn [event]
           (cond
             (kbd/enter? event)
             (handle-name-input-blur event)

             (kbd/esc? event)
             (reset! editing? false))))]

    [:li {:class (stl/css :version-entry-wrap)}
     [:div {:class (stl/css :version-entry :is-snapshot)}
      [:img {:class (stl/css :version-entry-avatar)
             :alt (:fullname profile)
             :src (cfg/resolve-profile-photo-url profile)}]

      [:div {:class (stl/css :version-entry-data)}
       (if @editing?
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

      [:button {:class (stl/css :version-entry-options)
                :on-click handle-open-menu}
       i/menu]]

     [:& dropdown {:show @show-menu? :on-close handle-close-menu}
      [:ul {:class (stl/css :version-options-dropdown)}
       [:li {:class (stl/css :menu-option) :on-click handle-rename-version} "Rename"]
       [:li {:class (stl/css :menu-option) :on-click handle-restore-version} "Restore"]
       [:li {:class (stl/css :menu-option) :on-click handle-delete-version} "Delete"]]]]))

(mf/defc snapshot-entry
  [{:keys [index is-expanded entry on-toggle-expand on-pin-snapshot]}]

  (let [handle-toggle-expand
        (mf/use-callback
         (mf/deps index on-toggle-expand)
         (fn []
           (when on-toggle-expand
             (on-toggle-expand index))))

        handle-pin-snapshot
        (fn [id]
          (when on-pin-snapshot (on-pin-snapshot id)))]

    [:li {:class (stl/css :version-entry-wrap)}
     [:div {:class (stl/css-case :version-entry true
                                 :is-autosave true
                                 :is-expanded is-expanded)}
      [:p {:class (stl/css :version-entry-name)}
       (str "Autosaved " (dt/format (:created-at entry) :date-full))]

      [:button {:class (stl/css :version-entry-snapshots)
                :on-click handle-toggle-expand}
       i/clock (str (count (:snapshots entry)) " autosave versions") i/arrow]

      [:ul {:class (stl/css :version-snapshot-list)}
       (for [[idx snapshot] (d/enumerate (:snapshots entry))]
         [:li {:key (dm/str "snp-" idx)}
          [:button {:class (stl/css :version-snapshot-entry)
                    :on-click #(handle-pin-snapshot (:id snapshot))}
           (str
            (dt/format (:created-at snapshot) :date-full)
            " . "
            (dt/format (:created-at snapshot) :time-24-simple))
           i/pin]])]]]))


(mf/defc versions-toolbox
  []
  (let [users                 (mf/deref refs/users)
        file-id               (mf/deref refs/current-file-id)
        {:keys [status data]} (mf/deref versions)
        expanded              (mf/use-state #{})

        handle-create-version
        (mf/use-fn
         (fn []
           (create-version file-id)))

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
        (mf/use-callback
         (mf/deps file-id)
         (fn [id label]
           (rename-version file-id id label)))

        handle-restore-version
        (mf/use-callback
         (mf/deps file-id)
         (fn [id]
           (restore-version file-id id)))

        handle-delete-version
        (mf/use-callback
         (mf/deps file-id)
         (fn [id]
           (delete-version file-id id)))

        handle-pin-version
        (mf/use-callback
         (mf/deps file-id)
         (fn [id]
           (pin-version file-id id)))]

    (mf/with-effect
      [file-id]
      (when file-id
        (fetch-versions file-id)))

    [:div {:class (stl/css :version-toolbox)}
     [:& select
      {:default-value :all
       :options [{:value :all :label "All versions"}
                 {:value #uuid "2c6952ee-d00e-8160-8004-d2250b7210cb" :label "User 1"}
                 {:value #uuid "2c6952ee-d00e-8160-8004-d2250b7210cb" :label "User 2"}]
       ;;:on-change handle-change-blend-mode
       ;;:is-open? option-highlighted?
       ;;:class (stl/css-case :hidden-select hidden?)
       ;;:on-pointer-enter-option handle-blend-mode-enter
       ;;:on-pointer-leave-option handle-blend-mode-leave
       }]

     (cond
       (= status :loading)
       [:div {:class (stl/css :versions-entry-empty)}
        [:div {:class (stl/css :versions-entry-empty-msg)} "Loading..."]]

       (= status :loaded)
       [:*
        [:div {:class (stl/css :version-save-version)}
         "Save version" [:button {:class (stl/css :version-save-button)
                                  :on-click handle-create-version} i/pin]]

        (if (empty? data)
          [:div {:class (stl/css :versions-entry-empty)}
           [:div {:class (stl/css :versions-entry-empty-icon)} i/history]
           [:div {:class (stl/css :versions-entry-empty-msg)} "There are no versions yet"]]

          [:ul {:class (stl/css :versions-entries)}
           (for [[idx-entry entry] (->> data (map-indexed vector))]
             (case (:type entry)
               :version
               [:& version-entry {:key idx-entry
                                  :entry entry
                                  :profile (get users (:profile-id entry))
                                  :on-rename-version handle-rename-version
                                  :on-restore-version handle-restore-version
                                  :on-delete-version handle-delete-version}]

               :snapshot
               [:& snapshot-entry {:key idx-entry
                                   :index idx-entry
                                   :entry entry
                                   :is-expanded (contains? @expanded idx-entry)
                                   :on-toggle-expand handle-toggle-expand
                                   :on-pin-snapshot handle-pin-version}]

               nil))])])]))
