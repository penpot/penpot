;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.dashboard.grid
  (:require
   [app.common.math :as mth]
   [app.common.uuid :as uuid]
   [app.config :as cfg]
   [app.main.data.dashboard :as dd]
   [app.main.data.modal :as modal]
   [app.main.fonts :as fonts]
   [app.main.store :as st]
   [app.main.ui.dashboard.file-menu :refer [file-menu]]
   [app.main.ui.dashboard.inline-edition :refer [inline-edition]]
   [app.main.ui.icons :as i]
   [app.main.worker :as wrk]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [t tr]]
   [app.util.keyboard :as kbd]
   [app.util.router :as rt]
   [app.util.time :as dt]
   [app.util.timers :as ts]
   [beicon.core :as rx]
   [cuerdas.core :as str]
   [lambdaisland.uri :as uri]
   [rumext.alpha :as mf]))

;; --- Grid Item Thumbnail

(mf/defc grid-item-thumbnail
  {::mf/wrap [mf/memo]}
  [{:keys [file] :as props}]
  (let [container (mf/use-ref)]
    (mf/use-effect
     (mf/deps (:id file))
     (fn []
       (->> (wrk/ask! {:cmd :thumbnails/generate
                       :file-id (:id file)
                       :page-id (get-in file [:data :pages 0])})
            (rx/subs (fn [{:keys [svg fonts]}]
                       (run! fonts/ensure-loaded! fonts)
                       (when-let [node (mf/ref-val container)]
                         (set! (.-innerHTML ^js node) svg)))))))
    [:div.grid-item-th {:style {:background-color (get-in file [:data :options :background])}
                        :ref container}]))

;; --- Grid Item

(mf/defc grid-item-metadata
  [{:keys [modified-at]}]
  (let [locale (mf/deref i18n/locale)
        time   (dt/timeago modified-at {:locale locale})]
    (str (t locale "ds.updated-at" time))))

(mf/defc grid-item
  {:wrap [mf/memo]}
  [{:keys [id file] :as props}]
  (let [local    (mf/use-state {:menu-open false
                                :menu-pos nil
                                :edition false})
        locale   (mf/deref i18n/locale)
        menu-ref (mf/use-ref)

        on-menu-close
        (mf/use-callback
          #(swap! local assoc :menu-open false))

        on-navigate
        (mf/use-callback
         (mf/deps id)
         (fn [event]
           (let [menu-icon (mf/ref-val menu-ref)
                 target    (dom/get-target event)]
             (when-not (dom/child? target menu-icon)
               (let [pparams {:project-id (:project-id file)
                              :file-id (:id file)}
                     qparams {:page-id (first (get-in file [:data :pages]))}]
                 (st/emit! (rt/nav :workspace pparams qparams)))))))

        on-menu-click
        (mf/use-callback
         (mf/deps file)
         (fn [event]
           (dom/prevent-default event)
           (let [position (dom/get-client-position event)]
             (swap! local assoc :menu-open true
                                :menu-pos position))))

        edit
        (mf/use-callback
         (mf/deps file)
         (fn [name]
           (st/emit! (dd/rename-file (assoc file :name name)))
           (swap! local assoc :edition false)))

        on-edit
        (mf/use-callback
         (mf/deps file)
         (fn [event]
           (dom/stop-propagation event)
           (swap! local assoc
                  :edition true
                  :menu-open false)))]

    [:div.grid-item.project-th {:on-click on-navigate
                                :on-context-menu on-menu-click}
     [:div.overlay]
     [:& grid-item-thumbnail {:file file}]
     (when (:is-shared file)
       [:div.item-badge i/library])
     [:div.item-info
      (if (:edition @local)
        [:& inline-edition {:content (:name file)
                            :on-end edit}]
        [:h3 (:name file)])
      [:& grid-item-metadata {:modified-at (:modified-at file)}]]
     [:div.project-th-actions {:class (dom/classnames
                                       :force-display (:menu-open @local))}
      [:div.project-th-icon.menu
       {:ref menu-ref
        :on-click on-menu-click}
       i/actions
       [:& file-menu {:file file
                      :show? (:menu-open @local)
                      :left (:x (:menu-pos @local))
                      :top (:y (:menu-pos @local))
                      :on-edit on-edit
                      :on-menu-close on-menu-close}]]]]))

(mf/defc empty-placeholder
  []
  [:div.grid-empty-placeholder
   [:div.icon i/file-html]
   [:div.text (tr "dashboard.empty-files")]])

(mf/defc loading-placeholder
  []
  [:div.grid-empty-placeholder
   [:div.icon i/loader]
   [:div.text (tr "dashboard.loading-files")]])

(mf/defc grid
  [{:keys [id opts files] :as props}]
  (let [locale (mf/deref i18n/locale)]
    [:section.dashboard-grid
     (cond
       (nil? files)
       [:& loading-placeholder]

       (seq files)
       [:div.grid-row
        (for [item files]
          [:& grid-item
           {:id (:id item)
            :file item
            :key (:id item)}])]

       :else
       [:& empty-placeholder])]))

(mf/defc line-grid-row
  [{:keys [locale files on-load-more] :as props}]
  (let [rowref   (mf/use-ref)

        width    (mf/use-state 900)
        limit    (mf/use-state 1)

        itemsize 290]

    (mf/use-layout-effect
     (mf/deps width)
     (fn []
       (let [node   (mf/ref-val rowref)
             obs    (new js/ResizeObserver
                         (fn [entries x]
                           (ts/raf (fn []
                                     (let [data  (first entries)
                                           rect  (.-contentRect ^js data)]
                                       (reset! width (.-width ^js rect)))))))

             nitems (/ @width itemsize)
             num    (mth/floor nitems)]

         (.observe ^js obs node)

         (cond
           (< (* itemsize (count files)) @width)
           (reset! limit num)

           (< nitems (+ num 0.51))
           (reset! limit (dec num))

           :else
           (reset! limit num))
         (fn []
           (.disconnect ^js obs)))))

    [:div.grid-row.no-wrap {:ref rowref}
     (for [item (take @limit files)]
       [:& grid-item
        {:id (:id item)
         :file item
         :key (:id item)}])
     (when (> (count files) @limit)
       [:div.grid-item.placeholder {:on-click on-load-more}
        [:div.placeholder-icon i/arrow-down]
        [:div.placeholder-label
         (t locale "dashboard.show-all-files")]])]))

(mf/defc line-grid
  [{:keys [project-id opts files on-load-more] :as props}]
  (let [locale (mf/deref i18n/locale)]
    [:section.dashboard-grid
     (cond
       (nil? files)
       [:& loading-placeholder]

       (seq files)
       [:& line-grid-row {:files files
                          :on-load-more on-load-more
                          :locale locale}]

       :else
       [:& empty-placeholder])]))

