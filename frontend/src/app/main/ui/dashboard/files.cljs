;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.dashboard.files
  (:require
   [app.common.math :as mth]
   [app.main.data.dashboard :as dd]
   [app.main.data.events :as ev]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.dashboard.grid :refer [grid]]
   [app.main.ui.dashboard.inline-edition :refer [inline-edition]]
   [app.main.ui.dashboard.project-menu :refer [project-menu]]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.webapi :as wapi]
   [beicon.core :as rx]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(mf/defc header
  [{:keys [project create-fn] :as props}]
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
             (prn position)
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


    [:header.dashboard-header
     (if (:is-default project)
       [:div.dashboard-title#dashboard-drafts-title 
        [:h1 (tr "labels.drafts")]]

       (if (:edition @local)
         [:& inline-edition {:content (:name project)
                             :on-end (fn [name]
                                       (let [name (str/trim name)]
                                         (when-not (str/empty? name)
                                           (st/emit! (-> (dd/rename-project (assoc project :name name))
                                                         (with-meta {::ev/origin "project"}))))
                                         (swap! local assoc :edition false)))}]
         [:div.dashboard-title
          [:h1 {:on-double-click on-edit
                :data-test "project-title"
                :id (:id project)}
           (:name project)]]))

     [:& project-menu {:project project
                       :show? (:menu-open @local)
                       :left (- (:x (:menu-pos @local)) 180)
                       :top (:y (:menu-pos @local))
                       :on-edit on-edit
                       :on-menu-close on-menu-close
                       :on-import on-import}]

     [:div.dashboard-header-actions
      [:a.btn-secondary.btn-small 
       {:tab-index "0"
        :on-click on-create-click
        :data-test "new-file"
        :on-key-down (fn [event]
                       (when (kbd/enter? event)
                         (on-create-click event)))}
       (tr "dashboard.new-file")]

      (when-not (:is-default project)
        [:button.icon.pin-icon.tooltip.tooltip-bottom
         {:tab-index "0"
          :class (when (:is-pinned project) "active")
          :on-click toggle-pin
          :alt (tr "dashboard.pin-unpin")
          :on-key-down (fn [event]
                         (when (kbd/enter? event)
                           (toggle-pin event)))}
         (if (:is-pinned project)
           i/pin-fill
           i/pin)])

      [:div.icon.tooltip.tooltip-bottom-left
       {:tab-index "0"
        :on-click on-menu-click 
        :alt (tr "dashboard.options")
        :on-key-down (fn [event]
                       (when (kbd/enter? event)
                         (on-menu-click event)))}
       i/actions]]]))

(mf/defc files-section
  [{:keys [project team] :as props}]
  (let [files-map  (mf/deref refs/dashboard-files)
        project-id (:id project)
        width      (mf/use-state nil)
        rowref     (mf/use-ref)
        itemsize   (if (>= @width 1030)
                     280
                     230)

        ratio     (if (some? @width) (/ @width itemsize) 0)
        nitems    (mth/floor ratio)
        limit     (min 10 nitems)
        limit     (max 1 limit)

        files     (mf/with-memo [project-id files-map]
                    (->> (vals files-map)
                         (filter #(= project-id (:project-id %)))
                         (sort-by :modified-at)
                         (reverse)))

        create-file
        (mf/use-fn
         (fn [origin]
           (st/emit! (with-meta (dd/create-file {:project-id (:id project)})
                       {::ev/origin origin}))))]

    (mf/with-effect []
      (let [node (mf/ref-val rowref)
            mnt? (volatile! true)
            sub  (->> (wapi/observe-resize node)
                      (rx/observe-on :af)
                      (rx/subs (fn [entries]
                                 (let [row (first entries)
                                       row-rect (.-contentRect ^js row)
                                       row-width (.-width ^js row-rect)]
                                   (when @mnt?
                                     (reset! width row-width))))))]
        (fn []
          (vreset! mnt? false)
          (rx/dispose! sub))))

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
     [:& header {:team team
                 :project project
                 :create-fn create-file}]
     [:section.dashboard-container.no-bg {:ref rowref}
      [:& grid {:project project
                :files files
                :origin :files
                :create-fn create-file
                :limit limit}]]]))

