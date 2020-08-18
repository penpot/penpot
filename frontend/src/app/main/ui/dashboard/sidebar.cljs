;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2020 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns app.main.ui.dashboard.sidebar
  (:require
   [cuerdas.core :as str]
   [goog.functions :as f]
   [okulary.core :as l]
   [rumext.alpha :as mf]
   [app.main.constants :as c]
   [app.main.data.dashboard :as dsh]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.confirm :refer [confirm-dialog]]
   [app.main.ui.dashboard.common :as common]
   [app.main.ui.icons :as i]
   [app.main.ui.keyboard :as kbd]
   [app.main.ui.modal :as modal]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [t tr]]
   [app.util.router :as rt]
   [app.util.time :as dt]))

;; --- Component: Sidebar

(mf/defc sidebar-project
  [{:keys [id name selected? team-id] :as props}]
  (let [dashboard-local @refs/dashboard-local
        project-for-edit (:project-for-edit dashboard-local)
        local (mf/use-state {:name name
                             :editing (= id project-for-edit)})
        editable? (not (nil? id))
        edit-input-ref (mf/use-ref)

        on-click #(st/emit! (rt/nav :dashboard-project {:team-id team-id :project-id id}))
        on-dbl-click #(when editable? (swap! local assoc :editing true))
        on-input #(as-> % $
                    (dom/get-target $)
                    (dom/get-value $)
                    (swap! local assoc :name $))
        on-cancel #(do
                     (st/emit! dsh/clear-project-for-edit)
                     (swap! local assoc :editing false :name name))
        on-keyup #(cond
                    (kbd/esc? %)
                    (on-cancel)

                    (kbd/enter? %)
                    (let [name (-> % dom/get-target dom/get-value)]
                      (st/emit! dsh/clear-project-for-edit)
                      (st/emit! (dsh/rename-project id name))
                      (swap! local assoc :editing false)))]

    (mf/use-effect
      (mf/deps (:editing @local))
      #(when (:editing @local)
         (let [edit-input (mf/ref-val edit-input-ref)]
           (dom/focus! edit-input)
           (dom/select-text! edit-input))
         nil))

    [:li {:on-click on-click
          :on-double-click on-dbl-click
          :class-name (when selected? "current")}
     (if (:editing @local)
       [:div.edit-wrapper
        [:input.element-title {:value (:name @local)
                               :ref edit-input-ref
                               :on-change on-input
                               :on-key-down on-keyup}]
        [:span.close {:on-click on-cancel} i/close]]
       [:*
        i/folder
        [:span.element-title name]])]))

(def projects-iref
  (l/derived :projects st/state))

(mf/defc sidebar-projects
  [{:keys [team-id selected-project-id] :as props}]
  (let [projects (->> (mf/deref projects-iref)
                      (vals)
                      (remove #(:is-default %))
                      (sort-by :created-at))]
    (for [item projects]
      [:& sidebar-project
       {:id (:id item)
        :key (:id item)
        :name (:name item)
        :selected? (= (:id item) selected-project-id)
        :team-id team-id
        }])))

(mf/defc sidebar-team
  [{:keys [profile
           team-id
           selected-section
           selected-project-id
           selected-team-id] :as props}]
  (let [home?      (and (= selected-section :dashboard-team)
                        (= selected-team-id (:default-team-id profile)))
        drafts?    (and (= selected-section :dashboard-project)
                        (= selected-team-id (:default-team-id profile))
                        (= selected-project-id (:default-project-id profile)))
        libraries? (= selected-section :dashboard-libraries)
        ;; library? (and (str/starts-with? (name selected-section) "dashboard-library")
        ;;               (= selected-team-id (:default-team-id profile)))
        locale (i18n/use-locale)]
    [:div.sidebar-team
     [:ul.dashboard-elements.dashboard-common
      [:li.recent-projects
       {:on-click #(st/emit! (rt/nav :dashboard-team {:team-id team-id}))
        :class-name (when home? "current")}
       i/recent
       [:span.element-title (t locale "dashboard.sidebar.recent")]]

      [:li
       {:on-click #(st/emit! (rt/nav :dashboard-project {:team-id team-id
                                                         :project-id "drafts"}))
        :class-name (when drafts? "current")}
       i/file-html
       [:span.element-title (t locale "dashboard.sidebar.drafts")]]

      [:li
       {:on-click #(st/emit! (rt/nav :dashboard-libraries {:team-id team-id}))
        :class-name (when libraries? "current")}
       i/icon-set
       [:span.element-title (t locale "dashboard.sidebar.libraries")]]]

     [:div.projects-row
      [:span "PROJECTS"]
      [:a.btn-icon-light.btn-small {:on-click #(st/emit! dsh/create-project)}
       i/close]]

     [:ul.dashboard-elements
      [:& sidebar-projects
       {:selected-team-id selected-team-id
        :selected-project-id selected-project-id
        :team-id team-id}]]]

    ))


(def debounced-emit! (f/debounce st/emit! 500))

(mf/defc sidebar
  [{:keys [section team-id project-id search-term] :as props}]
  (let [locale (i18n/use-locale)
        profile (mf/deref refs/profile)
        search-term-not-nil (or search-term "")

        on-search-focus
        (fn [event]
          (let [target (dom/get-target event)
                value (dom/get-value target)]
            (dom/select-text! target)
            (if (empty? value)
              (debounced-emit! (rt/nav :dashboard-search {:team-id team-id} {}))
              (debounced-emit! (rt/nav :dashboard-search {:team-id team-id} {:search-term value})))))

        on-search-change
        (fn [event]
          (let [value (-> (dom/get-target event)
                          (dom/get-value))]
            (debounced-emit! (rt/nav :dashboard-search {:team-id team-id} {:search-term value}))))

        on-clear-click
        (fn [event]
          (let [search-input (dom/get-element "search-input")]
            (dom/clean-value! search-input)
            (dom/focus! search-input)
            (debounced-emit! (rt/nav :dashboard-search {:team-id team-id} {}))))]

    [:div.dashboard-sidebar
     [:div.dashboard-sidebar-inside
      [:form.dashboard-search
       [:input.input-text
        {:key :images-search-box
         :id "search-input"
         :type "text"
         :placeholder (t locale "ds.search.placeholder")
         :default-value search-term-not-nil
         :auto-complete "off"
         :on-focus on-search-focus
         :on-change on-search-change
         :ref #(when % (set! (.-value %) search-term-not-nil))}]
       [:div.clear-search
        {:on-click on-clear-click}
        i/close]]
      [:& sidebar-team {:selected-team-id team-id
                        :selected-project-id project-id
                        :selected-section section
                        :profile profile
                        :team-id (:default-team-id profile)}]]]))
