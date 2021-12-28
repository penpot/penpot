;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.dashboard.grid
  (:require
   [app.common.math :as mth]
   [app.main.data.dashboard :as dd]
   [app.main.data.messages :as dm]
   [app.main.fonts :as fonts]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.dashboard.file-menu :refer [file-menu]]
   [app.main.ui.dashboard.import :refer [use-import-file]]
   [app.main.ui.dashboard.inline-edition :refer [inline-edition]]
   [app.main.ui.dashboard.placeholder :refer [empty-placeholder loading-placeholder]]
   [app.main.ui.icons :as i]
   [app.main.worker :as wrk]
   [app.util.dom :as dom]
   [app.util.dom.dnd :as dnd]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.storage :as stg]
   [app.util.time :as dt]
   [app.util.timers :as ts]
   [app.util.webapi :as wapi]
   [beicon.core :as rx]
   [rumext.alpha :as mf]))

;; --- Grid Item Thumbnail

(defn use-thumbnail-cache
  "Creates some hooks to handle the files thumbnails cache"
  [file]

  (let [get-thumbnail
        (mf/use-callback
         (mf/deps file)
         (fn []
           (let [[revn thumb-data] (get-in @stg/storage [:thumbnails (:id file)])]
             (when (= revn (:revn file))
               thumb-data))))

        cache-thumbnail
        (mf/use-callback
         (mf/deps file)
         (fn [thumb-data]
           (swap! stg/storage #(assoc-in % [:thumbnails (:id file)] [(:revn file) thumb-data]))))

        generate-thumbnail
        (mf/use-callback
         (mf/deps file)
         (fn []
           (let [thumb-data (get-thumbnail)]
             (if (some? thumb-data)
               (rx/of thumb-data)
               (->> (wrk/ask! {:cmd :thumbnails/generate
                               :file-id (:id file)
                               :page-id (get-in file [:data :pages 0])})
                    (rx/tap cache-thumbnail))))))]

    generate-thumbnail))

(mf/defc grid-item-thumbnail
  {::mf/wrap [mf/memo]}
  [{:keys [file] :as props}]
  (let [container (mf/use-ref)
        generate-thumbnail (use-thumbnail-cache file)]

    (mf/use-effect
     (mf/deps file)
     (fn []
       (->> (generate-thumbnail)
            (rx/subs (fn [{:keys [svg fonts]}]
                       (run! fonts/ensure-loaded! fonts)
                       (when-let [node (mf/ref-val container)]
                         (set! (.-innerHTML ^js node) svg)))))))
    [:div.grid-item-th {:style {:background-color (get-in file [:data :options :background])}
                        :ref container}
     i/loader-pencil]))

;; --- Grid Item

(mf/defc grid-item-metadata
  [{:keys [modified-at]}]
  (let [locale (mf/deref i18n/locale)
        time   (dt/timeago modified-at {:locale locale})]
    (str (tr "ds.updated-at" time))))

(defn create-counter-element
  [_element file-count]
  (let [counter-el (dom/create-element "div")]
    (dom/set-property! counter-el "class" "drag-counter")
    (dom/set-text! counter-el (str file-count))
    counter-el))

(mf/defc grid-item
  {:wrap [mf/memo]}
  [{:keys [file navigate?] :as props}]
  (let [file-id        (:id file)
        local          (mf/use-state {:menu-open false
                                      :menu-pos nil
                                      :edition false})
        selected-files (mf/deref refs/dashboard-selected-files)
        item-ref       (mf/use-ref)
        menu-ref       (mf/use-ref)
        selected?      (contains? selected-files file-id)

        on-menu-close
        (mf/use-callback
          #(swap! local assoc :menu-open false))

        on-select
        (fn [event]
          (when (and (or (not selected?) (> (count selected-files) 1))
                     (not (:menu-open @local)))
            (dom/stop-propagation event)
            (let [shift? (kbd/shift? event)]
              (when-not shift?
                (st/emit! (dd/clear-selected-files)))
              (st/emit! (dd/toggle-file-select file)))))

        on-navigate
        (mf/use-callback
         (mf/deps file)
         (fn [event]
           (let [menu-icon (mf/ref-val menu-ref)
                 target    (dom/get-target event)]
             (when-not (dom/child? target menu-icon)
               (st/emit! (dd/go-to-workspace file))))))

        on-drag-start
        (mf/use-callback
          (mf/deps selected-files)
          (fn [event]
            (let [offset          (dom/get-offset-position (.-nativeEvent event))

                  select-current? (not (contains? selected-files (:id file)))

                  item-el         (mf/ref-val item-ref)
                  counter-el      (create-counter-element item-el
                                                          (if select-current?
                                                            1
                                                            (count selected-files)))]
              (when select-current?
                (st/emit! (dd/clear-selected-files))
                (st/emit! (dd/toggle-file-select file)))

              (dnd/set-data! event "penpot/files" "dummy")
              (dnd/set-allowed-effect! event "move")

              ;; set-drag-image requires that the element is rendered and
              ;; visible to the user at the moment of creating the ghost
              ;; image (to make a snapshot), but you may remove it right
              ;; afterwards, in the next render cycle.
              (dom/append-child! item-el counter-el)
              (dnd/set-drag-image! event item-el (:x offset) (:y offset))
              (ts/raf #(.removeChild ^js item-el counter-el)))))

        on-menu-click
        (mf/use-callback
         (mf/deps file selected?)
         (fn [event]
           (dom/prevent-default event)
           (when-not selected?
             (let [shift? (kbd/shift? event)]
               (when-not shift?
                 (st/emit! (dd/clear-selected-files)))
               (st/emit! (dd/toggle-file-select file))))
           (let [position (dom/get-client-position event)]
             (swap! local assoc
                    :menu-open true
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

    [:div.grid-item.project-th
     {:class (dom/classnames :selected selected?)
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
         [:& file-menu {:files (vals selected-files)
                        :show? (:menu-open @local)
                        :left (+ 24 (:x (:menu-pos @local)))
                        :top (:y (:menu-pos @local))
                        :navigate? navigate?
                        :on-edit on-edit
                        :on-menu-close on-menu-close}])]]]))

(mf/defc grid
  [{:keys [files project] :as props}]
  (let [dragging?  (mf/use-state false)
        project-id (:id project)

        on-finish-import
        (mf/use-callback
         (fn []
           (st/emit! (dd/fetch-files {:project-id project-id})
                     (dd/fetch-shared-files)
                     (dd/clear-selected-files))))

        import-files (use-import-file project-id on-finish-import)

        on-drag-enter
        (mf/use-callback
         (fn [e]
           (when (or (dnd/has-type? e "Files")
                     (dnd/has-type? e "application/x-moz-file"))
             (dom/prevent-default e)
             (reset! dragging? true))))

        on-drag-over
        (mf/use-callback
         (fn [e]
           (when (or (dnd/has-type? e "Files")
                     (dnd/has-type? e "application/x-moz-file"))
             (dom/prevent-default e))))

        on-drag-leave
        (mf/use-callback
         (fn [e]
           (when-not (dnd/from-child? e)
             (reset! dragging? false))))


        on-drop
        (mf/use-callback
         (fn [e]
           (when (or (dnd/has-type? e "Files")
                     (dnd/has-type? e "application/x-moz-file"))
             (dom/prevent-default e)
             (reset! dragging? false)
             (import-files (.-files (.-dataTransfer e))))))]

    [:section.dashboard-grid {:on-drag-enter on-drag-enter
                              :on-drag-over on-drag-over
                              :on-drag-leave on-drag-leave
                              :on-drop on-drop}
     (cond
       (nil? files)
       [:& loading-placeholder]

       (seq files)
       [:div.grid-row
        (when @dragging?
          [:div.grid-item])
        (for [item files]
          [:& grid-item
           {:file item
            :key (:id item)
            :navigate? true}])]

       :else
       [:& empty-placeholder {:default? (:is-default project)}])]))

(mf/defc line-grid-row
  [{:keys [files selected-files on-load-more dragging?] :as props}]
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
         (tr "dashboard.show-all-files")]])]))

(mf/defc line-grid
  [{:keys [project team files on-load-more] :as props}]
  (let [dragging?        (mf/use-state false)
        project-id       (:id project)
        team-id          (:id team)

        selected-files   (mf/deref refs/dashboard-selected-files)
        selected-project (mf/deref refs/dashboard-selected-project)

        on-finish-import
        (mf/use-callback
         (mf/deps (:id team))
         (fn []
           (st/emit! (dd/fetch-recent-files (:id team))
                     (dd/clear-selected-files))))

        import-files (use-import-file project-id on-finish-import)

        on-drag-enter
        (mf/use-callback
          (mf/deps selected-project)
          (fn [e]
            (when (dnd/has-type? e "penpot/files")
              (dom/prevent-default e)
              (when-not (or (dnd/from-child? e)
                            (dnd/broken-event? e))
                (when (not= selected-project project-id)
                  (reset! dragging? true))))

            (when (or (dnd/has-type? e "Files")
                      (dnd/has-type? e "application/x-moz-file"))
              (dom/prevent-default e)
              (reset! dragging? true))))

        on-drag-over
        (mf/use-callback
         (fn [e]
           (when (or (dnd/has-type? e "penpot/files")
                     (dnd/has-type? e "Files")
                     (dnd/has-type? e "application/x-moz-file"))
             (dom/prevent-default e))))

        on-drag-leave
        (mf/use-callback
          (fn [e]
            (when-not (dnd/from-child? e)
              (reset! dragging? false))))

        on-drop-success
        (fn []
          (st/emit! (dm/success (tr "dashboard.success-move-file"))
                    (dd/fetch-recent-files (:id team))
                    (dd/clear-selected-files)))

        on-drop
        (mf/use-callback
          (mf/deps files selected-files)
          (fn [e]
            (when (or (dnd/has-type? e "Files")
                      (dnd/has-type? e "application/x-moz-file"))
              (dom/prevent-default e)
              (reset! dragging? false)
              (import-files (.-files (.-dataTransfer e))))

            (when (dnd/has-type? e "penpot/files")
              (reset! dragging? false)
              (when (not= selected-project project-id)
                (let [data  {:ids (into #{} (keys selected-files))
                             :project-id project-id}
                      mdata {:on-success on-drop-success}]
                  (st/emit! (dd/move-files (with-meta data mdata))))))))]

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
                          :dragging? @dragging?}]

       :else
       [:& empty-placeholder {:dragging? @dragging?
                              :default? (:is-default project)}])]))

