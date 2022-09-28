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
   [app.util.webapi :as wapi]
   [beicon.core :as rx]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(mf/defc header
  [{:keys [project on-create-clicked] :as props}]
  (let [local      (mf/use-state {:menu-open false
                                  :edition false})
        on-menu-click
        (mf/use-callback
         (fn [event]
           (let [position (dom/get-client-position event)]
             (dom/prevent-default event)
             (swap! local assoc :menu-open true :menu-pos position))))

        on-menu-close
        (mf/use-callback #(swap! local assoc :menu-open false))

        on-edit
        (mf/use-callback #(swap! local assoc :edition true :menu-open false))

        toggle-pin
        (mf/use-callback
         (mf/deps project)
         #(st/emit! (dd/toggle-project-pin project)))

        on-import
        (mf/use-callback
         (mf/deps (:id project))
         (fn []
           (st/emit! (dd/fetch-files {:project-id (:id project)})
                     (dd/clear-selected-files))))]


    [:header.dashboard-header
     (if (:is-default project)
       [:div.dashboard-title
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
          [:h1 {:on-double-click on-edit :data-test "project-title"}
           (:name project)]]))

     [:& project-menu {:project project
                       :show? (:menu-open @local)
                       :left (- (:x (:menu-pos @local)) 180)
                       :top (:y (:menu-pos @local))
                       :on-edit on-edit
                       :on-menu-close on-menu-close
                       :on-import on-import}]

     [:div.dashboard-header-actions
      [:a.btn-secondary.btn-small {:on-click (partial on-create-clicked project "dashboard:header") :data-test "new-file"}
       (tr "dashboard.new-file")]

      (when-not (:is-default project)
        [:div.icon.pin-icon.tooltip.tooltip-bottom
         {:class (when (:is-pinned project) "active")
         :on-click toggle-pin :alt (tr "dashboard.pin-unpin")}
         (if (:is-pinned project)
           i/pin-fill
           i/pin)])

      [:div.icon.tooltip.tooltip-bottom-left
       {:on-click on-menu-click :alt (tr "dashboard.options")}
       i/actions]]]))

(mf/defc files-section
  [{:keys [project team] :as props}]
  (let [files-map (mf/deref refs/dashboard-files)
        width            (mf/use-state nil)
        rowref           (mf/use-ref)
        itemsize       (if (>= @width 1030)
                         280
                         230)

        ratio          (if (some? @width) (/ @width itemsize) 0)
        nitems         (mth/floor ratio)
        limit          (min 10 nitems)
        limit          (max 1 limit)

        files     (->> (vals files-map)
                       (filter #(= (:id project) (:project-id %)))
                       (sort-by :modified-at)
                       (reverse))

        on-create-clicked
        (mf/use-callback
         (fn [project origin event]
           (dom/prevent-default event)
           (st/emit! (with-meta (dd/create-file {:project-id (:id project)})
                       {::ev/origin origin}))))]

    (mf/use-effect
     (fn []
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
           (rx/dispose! sub)))))


    (mf/use-effect
     (mf/deps project)
     (fn []
       (when project
         (let [pname (if (:is-default project)
                       (tr "labels.drafts")
                       (:name project))]
           (dom/set-html-title (tr "title.dashboard.files" pname))))))

    (mf/use-effect
     (mf/deps project)
     (fn []
       (st/emit! (dd/fetch-files {:project-id (:id project)})
                 (dd/clear-selected-files))))

    [:*
     [:& header {:team team :project project
                 :on-create-clicked on-create-clicked}]
     [:section.dashboard-container.no-bg {:ref rowref}
      [:& grid {:project project
                :files files
                :on-create-clicked on-create-clicked
                :origin :files
                :limit limit}]]]))

