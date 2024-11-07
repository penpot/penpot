;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.dashboard.files
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.dashboard :as dd]
   [app.main.data.events :as ev]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.dashboard.grid :refer [grid]]
   [app.main.ui.dashboard.inline-edition :refer [inline-edition]]
   [app.main.ui.dashboard.pin-button :refer [pin-button*]]
   [app.main.ui.dashboard.project-menu :refer [project-menu*]]
   [app.main.ui.ds.product.empty-placeholder :refer [empty-placeholder*]]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.router :as rt]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(def ^:private menu-icon
  (i/icon-xref :menu (stl/css :menu-icon)))

(mf/defc header*
  {::mf/props :obj
   ::mf/private true}
  [{:keys [project create-fn can-edit]}]
  (let [local (mf/use-state
               {:menu-open false
                :edition false})

        on-create-click
        (mf/use-fn
         (mf/deps create-fn)
         (fn [event]
           (dom/prevent-default event)
           (create-fn "dashboard:header")))

        on-menu-click
        (mf/use-fn
         (fn [event]
           (let [position (dom/get-client-position event)]
             (dom/prevent-default event)
             (swap! local assoc :menu-open true :menu-pos position))))

        on-menu-close
        (mf/use-fn #(swap! local assoc :menu-open false))

        on-edit
        (mf/use-fn #(swap! local assoc :edition true :menu-open false))

        toggle-pin
        (mf/use-fn
         (mf/deps project)
         #(st/emit! (dd/toggle-project-pin project)))

        on-import
        (mf/use-fn
         (mf/deps (:id project))
         (fn []
           (st/emit! (dd/fetch-files {:project-id (:id project)})
                     (dd/clear-selected-files))))]


    [:header {:class (stl/css :dashboard-header) :data-testid "dashboard-header"}
     (if (:is-default project)
       [:div#dashboard-drafts-title {:class (stl/css :dashboard-title)}
        [:h1 (tr "labels.drafts")]]

       (if (and (:edition @local) can-edit)
         [:& inline-edition
          {:content (:name project)
           :on-end (fn [name]
                     (let [name (str/trim name)]
                       (when-not (str/empty? name)
                         (st/emit! (-> (dd/rename-project (assoc project :name name))
                                       (with-meta {::ev/origin "project"}))))
                       (swap! local assoc :edition false)))}]
         [:div {:class (stl/css :dashboard-title)}
          [:h1 {:on-double-click on-edit
                :data-testid "project-title"
                :id (:id project)}
           (:name project)]]))

     [:div {:class (stl/css :dashboard-header-actions)}
      (when ^boolean can-edit
        [:a {:class (stl/css :btn-secondary :btn-small :new-file)
             :tab-index "0"
             :on-click on-create-click
             :data-testid "new-file"
             :on-key-down (fn [event]
                            (when (kbd/enter? event)
                              (on-create-click event)))}
         (tr "dashboard.new-file")])

      (when-not (:is-default project)
        [:> pin-button*
         {:tab-index 0
          :is-pinned (:is-pinned project)
          :on-click toggle-pin
          :on-key-down (fn [event] (when (kbd/enter? event) (toggle-pin event)))}])

      (when ^boolean can-edit
        [:div {:class (stl/css :icon)
               :tab-index "0"
               :on-click on-menu-click
               :title (tr "dashboard.options")
               :on-key-down (fn [event]
                              (when (kbd/enter? event)
                                (on-menu-click event)))}
         menu-icon])

      (when ^boolean can-edit
        [:> project-menu* {:project project
                           :show (:menu-open @local)
                           :left (- (:x (:menu-pos @local)) 180)
                           :top (:y (:menu-pos @local))
                           :on-edit on-edit
                           :on-menu-close on-menu-close
                           :on-import on-import}])]]))

(mf/defc files-section
  {::mf/props :obj}
  [{:keys [project team]}]
  (let [files-map        (mf/deref refs/dashboard-files)
        can-edit?        (-> team :permissions :can-edit)
        project-id       (:id project)
        is-draft-proyect (:is-default project)

        [rowref limit] (hooks/use-dynamic-grid-item-width)

        files     (mf/with-memo [project-id files-map]
                    (->> (vals files-map)
                         (filter #(= project-id (:project-id %)))
                         (sort-by :modified-at)
                         (reverse)))
        file-count (or (count files) 0)
        empty-state-viewer (and (not can-edit?)
                                (= 0 file-count))

        on-file-created
        (mf/use-fn
         (fn [data]
           (let [pparams {:project-id (:project-id data)
                          :file-id (:id data)}
                 qparams {:page-id (get-in data [:data :pages 0])}]
             (st/emit! (rt/nav :workspace pparams qparams)))))

        create-file
        (mf/use-fn
         (mf/deps project)
         (fn [origin]
           (let [mdata  {:on-success on-file-created}
                 params {:project-id (:id project)}]
             (st/emit! (-> (dd/create-file (with-meta params mdata))
                           (with-meta {::ev/origin origin}))))))]

    (mf/with-effect [project]
      (when project
        (let [pname (if (:is-default project)
                      (tr "labels.drafts")
                      (:name project))]
          (dom/set-html-title (tr "title.dashboard.files" pname)))))

    (mf/with-effect [project-id]
      (st/emit! (dd/fetch-files {:project-id project-id})
                (dd/clear-selected-files)))

    [:*
     [:> header* {:team team
                  :can-edit can-edit?
                  :project project
                  :create-fn create-file}]
     [:section {:class (stl/css :dashboard-container :no-bg)
                :ref rowref}
      (if empty-state-viewer
        [:> empty-placeholder* {:title (if is-draft-proyect
                                         (tr "dashboard.empty-placeholder-drafts-title")
                                         (tr "dashboard.empty-placeholder-files-title"))
                                :class (stl/css :placeholder-placement)
                                :type 1
                                :subtitle (if is-draft-proyect
                                            (tr "dashboard.empty-placeholder-drafts-subtitle")
                                            (tr "dashboard.empty-placeholder-files-subtitle"))}]
        [:& grid {:project project
                  :files files
                  :can-edit can-edit?
                  :origin :files
                  :create-fn create-file
                  :limit limit}])]]))

