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
   [app.main.data.messages :as dm]
   [app.main.data.modal :as modal]
   [app.main.fonts :as fonts]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.dashboard.file-menu :refer [file-menu]]
   [app.main.ui.dashboard.inline-edition :refer [inline-edition]]
   [app.main.ui.icons :as i]
   [app.main.worker :as wrk]
   [app.util.dom :as dom]
   [app.util.dom.dnd :as dnd]
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
  [{:keys [id file selected-files navigate?] :as props}]
  (let [local     (mf/use-state {:menu-open false
                                 :menu-pos nil
                                 :edition false})
        locale    (mf/deref i18n/locale)
        item-ref  (mf/use-ref)
        menu-ref  (mf/use-ref)
        selected? (contains? selected-files id)

        selected-file-objs
        (deref refs/dashboard-selected-file-objs)
          ;; not needed to subscribe and repaint if changed

        on-menu-close
        (mf/use-callback
          #(swap! local assoc :menu-open false))

        on-select
        (mf/use-callback
          (mf/deps id selected? selected-files @local)
          (fn [event]
            (when (and (or (not selected?) (> (count selected-files) 1))
                       (not (:menu-open @local)))
              (dom/stop-propagation event)
              (let [shift? (kbd/shift? event)]
                (when-not shift?
                  (st/emit! (dd/clear-selected-files)))
                (st/emit! (dd/toggle-file-select {:file file}))))))

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

        create-counter
        (mf/use-callback
          (fn [element file-count]
            (let [counter-el (dom/create-element "div")]
              (dom/set-property! counter-el "class" "drag-counter")
              (dom/set-text! counter-el (str file-count))
              counter-el)))

        on-drag-start
        (mf/use-callback
          (mf/deps selected-files)
          (fn [event]
            (let [offset (dom/get-offset-position (.-nativeEvent event))

                  select-current? (not (contains? selected-files (:id file)))

                  item-el    (mf/ref-val item-ref)
                  counter-el (create-counter item-el
                                             (if select-current?
                                               1
                                               (count selected-files)))]

              (when select-current?
                (st/emit! (dd/clear-selected-files))
                (st/emit! (dd/toggle-file-select {:file file})))

              (dnd/set-data! event "penpot/files" "dummy")
              (dnd/set-allowed-effect! event "move")

              ;; set-drag-image requires that the element is rendered and
              ;; visible to the user at the moment of creating the ghost
              ;; image (to make a snapshot), but you may remove it right
              ;; afterwards, in the next render cycle.
              (dom/append-child! item-el counter-el)
              (dnd/set-drag-image! event item-el (:x offset) (:y offset))
              (ts/raf #(.removeChild item-el counter-el)))))

        on-menu-click
        (mf/use-callback
         (mf/deps file selected?)
         (fn [event]
           (dom/prevent-default event)
           (when-not selected?
             (let [shift? (kbd/shift? event)]
               (when-not shift?
                 (st/emit! (dd/clear-selected-files)))
               (st/emit! (dd/toggle-file-select {:file file}))))
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

    (mf/use-effect
      (mf/deps selected? local)
      (fn []
        (when (and (not selected?) (:menu-open @local))
          (swap! local assoc :menu-open false))))

    [:div.grid-item.project-th {:class (dom/classnames
                                         :selected selected?)
                                :ref item-ref
                                :draggable true
                                :on-click on-select
                                :on-double-click on-navigate
                                :on-drag-start on-drag-start
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
       (when selected?
         [:& file-menu {:files selected-file-objs
                        :show? (:menu-open @local)
                        :left (:x (:menu-pos @local))
                        :top (:y (:menu-pos @local))
                        :navigate? navigate?
                        :on-edit on-edit
                        :on-menu-close on-menu-close}])]]]))

(mf/defc empty-placeholder
  [{:keys [dragging?] :as props}]
  (if-not dragging?
    [:div.grid-empty-placeholder
     [:div.icon i/file-html]
     [:div.text (tr "dashboard.empty-files")]]
    [:div.grid-row.no-wrap
     [:div.grid-item]]))

(mf/defc loading-placeholder
  []
  [:div.grid-empty-placeholder
   [:div.icon i/loader]
   [:div.text (tr "dashboard.loading-files")]])

(mf/defc grid
  [{:keys [id opts files] :as props}]
  (let [locale           (mf/deref i18n/locale)
        selected-files   (mf/deref refs/dashboard-selected-files)]
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
            :selected-files selected-files
            :key (:id item)
            :navigate? true}])]

       :else
       [:& empty-placeholder])]))

(mf/defc line-grid-row
  [{:keys [locale files team-id selected-files on-load-more dragging?] :as props}]
  (let [rowref           (mf/use-ref)

        width            (mf/use-state nil)

        itemsize       290
        ratio          (if (some? @width) (/ @width itemsize) 0)
        nitems         (mth/floor ratio)
        limit          (min 10 ;; Configuration in backend to return recent files
                            (if (and (some? @width)
                                     (> (* itemsize (count files)) @width)
                                     (< (- ratio nitems) 0.51))
                              (dec nitems) ;; Leave space for the "show all" block
                              nitems))

        limit          (if dragging?
                         (dec limit)
                         limit)

        limit          (max 1 limit)]

    (mf/use-effect
      (fn []
        (let [node   (mf/ref-val rowref)
              obs    (new js/ResizeObserver
                          (fn [entries x]
                            (ts/raf #(let [row (first entries)
                                           row-rect (.-contentRect ^js row)
                                           row-width (.-width ^js row-rect)]
                                       (reset! width row-width)))))]

          (.observe ^js obs node)
          (fn []
            (.disconnect ^js obs)))))

    [:div.grid-row.no-wrap {:ref rowref}
     (when dragging?
       [:div.grid-item])
     (for [item (take limit files)]
       [:& grid-item
        {:id (:id item)
         :file item
         :selected-files selected-files
         :key (:id item)
         :navigate? false}])
     (when (and (> limit 0)
                (> (count files) limit))
       [:div.grid-item.placeholder {:on-click on-load-more}
        [:div.placeholder-icon i/arrow-down]
        [:div.placeholder-label
         (t locale "dashboard.show-all-files")]])]))

(mf/defc line-grid
  [{:keys [project-id team-id opts files on-load-more] :as props}]
  (let [locale (mf/deref i18n/locale)
        dragging?        (mf/use-state false)

        selected-files   (mf/deref refs/dashboard-selected-files)
        selected-project (mf/deref refs/dashboard-selected-project)

        on-drag-enter
        (mf/use-callback
          (mf/deps selected-project)
          (fn [e]
            (when (dnd/has-type? e "penpot/files")
              (dom/prevent-default e)
              (when-not (or (dnd/from-child? e)
                            (dnd/broken-event? e))
                (when (not= selected-project project-id)
                  (reset! dragging? true))))))

        on-drag-over
        (mf/use-callback
          (fn [e]
            (when (dnd/has-type? e "penpot/files")
              (dom/prevent-default e))))

        on-drag-leave
        (mf/use-callback
          (fn [e]
            (when-not (dnd/from-child? e)
              (reset! dragging? false))))

        on-drop
        (mf/use-callback
          (mf/deps files selected-files)
          (fn [e]
            (reset! dragging? false)
            (when (not= selected-project project-id)
              (let [data  {:ids selected-files
                           :project-id project-id}

                    mdata {:on-success
                           (st/emitf (dm/success (tr "dashboard.success-move-file"))
                                     (dd/fetch-recent-files {:team-id team-id})
                                     (dd/clear-selected-files))}]
                (st/emit! (dd/move-files (with-meta data mdata)))))))]

    [:section.dashboard-grid {:on-drag-enter on-drag-enter
                              :on-drag-over on-drag-over
                              :on-drag-leave on-drag-leave
                              :on-drop on-drop}
     (cond
       (nil? files)
       [:& loading-placeholder]

       (seq files)
       [:& line-grid-row {:files files
                          :team-id team-id
                          :selected-files selected-files
                          :on-load-more on-load-more
                          :dragging? @dragging?
                          :locale locale}]

       :else
       [:& empty-placeholder {:dragging? @dragging?}])]))

