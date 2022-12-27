;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.dashboard.grid
  (:require
   [app.common.data.macros :as dm]
   [app.common.files.features :as ffeat]
   [app.common.geom.point :as gpt]
   [app.common.logging :as log]
   [app.main.data.dashboard :as dd]
   [app.main.data.messages :as msg]
   [app.main.features :as features]
   [app.main.fonts :as fonts]
   [app.main.refs :as refs]
   [app.main.render :refer [component-svg]]
   [app.main.store :as st]
   [app.main.ui.components.color-bullet :as bc]
   [app.main.ui.dashboard.file-menu :refer [file-menu]]
   [app.main.ui.dashboard.import :refer [use-import-file]]
   [app.main.ui.dashboard.inline-edition :refer [inline-edition]]
   [app.main.ui.dashboard.placeholder :refer [empty-placeholder loading-placeholder]]
   [app.main.ui.hooks :as h]
   [app.main.ui.icons :as i]
   [app.main.worker :as wrk]
   [app.util.dom :as dom]
   [app.util.dom.dnd :as dnd]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.perf :as perf]
   [app.util.time :as dt]
   [app.util.timers :as ts]
   [beicon.core :as rx]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(log/set-level! :debug)

;; --- Grid Item Thumbnail

(defn ask-for-thumbnail
  "Creates some hooks to handle the files thumbnails cache"
  [file]
  (let [features (cond-> ffeat/enabled
                   (features/active-feature? :components-v2)
                   (conj "components/v2"))]
    (wrk/ask! {:cmd :thumbnails/generate
               :revn (:revn file)
               :file-id (:id file)
               :file-name (:name file)
               :features features})))

(mf/defc grid-item-thumbnail
  {::mf/wrap [mf/memo]}
  [{:keys [file] :as props}]
  (let [container (mf/use-ref)
        bgcolor   (dm/get-in file [:data :options :background])
        visible?  (h/use-visible container :once? true)]

    (mf/with-effect [file visible?]
      (when visible?
        (let [tp (perf/tpoint)]
          (->> (ask-for-thumbnail file)
               (rx/subscribe-on :af)
               (rx/subs (fn [{:keys [data fonts] :as params}]
                          (run! fonts/ensure-loaded! fonts)
                          (log/debug :hint "loaded thumbnail"
                                     :file-id (dm/str (:id file))
                                     :file-name (:name file)
                                     :elapsed (str/ffmt "%ms" (tp)))
                          (when-let [node (mf/ref-val container)]
                            (dom/set-html! node data))))))))

    [:div.grid-item-th
     {:style {:background-color bgcolor}
      :ref container}
     i/loader-pencil]))

;; --- Grid Item Library

(mf/defc grid-item-library
  {::mf/wrap [mf/memo]}
  [{:keys [file] :as props}]

  (mf/with-effect [file]
    (when file
      (let [font-ids (map :font-id (get-in file [:library-summary :typographies :sample] []))]
        (run! fonts/ensure-loaded! font-ids))))

  [:div.grid-item-th.library
   (if (nil? file)
     i/loader-pencil
     (let [summary (:library-summary file)
           components (:components summary)
           colors (:colors summary)
           typographies (:typographies summary)]
       [:*

        (when (pos? (:count components))
          [:div.asset-section
           [:div.asset-title
            [:span (tr "workspace.assets.components")]
            [:span.num-assets (str "\u00A0(") (:count components) ")"]] ;; Unicode 00A0 is non-breaking space
           [:div.asset-list
            (for [component (:sample components)]
              [:div.asset-list-item {:key (str "assets-component-" (:id component))}
               [:& component-svg {:group (get-in component [:objects (:id component)])
                                  :objects (:objects component)}]
               [:div.name-block
                [:span.item-name {:title (:name component)}
                 (:name component)]]])
            (when (> (:count components) (count (:sample components)))
              [:div.asset-list-item
               [:div.name-block
                [:span.item-name "(...)"]]])]])

        (when (pos? (:count colors))
          [:div.asset-section
           [:div.asset-title
            [:span (tr "workspace.assets.colors")]
            [:span.num-assets (str "\u00A0(") (:count colors) ")"]] ;; Unicode 00A0 is non-breaking space
           [:div.asset-list
            (for [color (:sample colors)]
              (let [default-name (cond
                                   (:gradient color) (bc/gradient-type->string (get-in color [:gradient :type]))
                                   (:color color) (:color color)
                                   :else (:value color))]
                [:div.asset-list-item {:key (str "assets-color-" (:id color))}
                 [:& bc/color-bullet {:color {:color (:color color)
                                              :opacity (:opacity color)}}]
                 [:div.name-block
                  [:span.color-name (:name color)]
                  (when-not (= (:name color) default-name)
                    [:span.color-value (:color color)])]]))
            (when (> (:count colors) (count (:sample colors)))
              [:div.asset-list-item
               [:div.name-block
                [:span.item-name "(...)"]]])]])

        (when (pos? (:count typographies))
          [:div.asset-section
           [:div.asset-title
            [:span (tr "workspace.assets.typography")]
            [:span.num-assets (str "\u00A0(") (:count typographies) ")"]] ;; Unicode 00A0 is non-breaking space
           [:div.asset-list
            (for [typography (:sample typographies)]
              [:div.asset-list-item {:key (str "assets-typography-" (:id typography))}
               [:div.typography-sample
                {:style {:font-family (:font-family typography)
                         :font-weight (:font-weight typography)
                         :font-style (:font-style typography)}}
                (tr "workspace.assets.typography.sample")]
               [:div.name-block
                [:span.item-name {:title (:name typography)}
                 (:name typography)]]])
            (when (> (:count typographies) (count (:sample typographies)))
              [:div.asset-list-item
               [:div.name-block
                [:span.item-name "(...)"]]])]])]))])

;; --- Grid Item

(mf/defc grid-item-metadata
  [{:keys [modified-at]}]

  (let [locale (mf/deref i18n/locale)
        time   (dt/timeago modified-at {:locale locale})]
    [:span.date
     time]))

(defn create-counter-element
  [_element file-count]
  (let [counter-el (dom/create-element "div")]
    (dom/set-property! counter-el "class" "drag-counter")
    (dom/set-text! counter-el (str file-count))
    counter-el))

(mf/defc grid-item
  {:wrap [mf/memo]}
  [{:keys [file navigate? origin library-view?] :as props}]
  (let [file-id         (:id file)
        local           (mf/use-state {:menu-open false
                                       :menu-pos nil
                                       :edition false})
        selected-files  (mf/deref refs/dashboard-selected-files)
        dashboard-local (mf/deref refs/dashboard-local)
        node-ref        (mf/use-ref)
        menu-ref        (mf/use-ref)

        selected?        (contains? selected-files file-id)

        on-menu-close
        (mf/use-fn
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
        (mf/use-fn
         (mf/deps file)
         (fn [event]
           (let [menu-icon (mf/ref-val menu-ref)
                 target    (dom/get-target event)]
             (when-not (dom/child? target menu-icon)
               (st/emit! (dd/go-to-workspace file))))))

        on-drag-start
        (mf/use-fn
         (mf/deps selected-files)
         (fn [event]
           (let [offset          (dom/get-offset-position (.-nativeEvent event))

                 select-current? (not (contains? selected-files (:id file)))

                 item-el         (mf/ref-val node-ref)
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
        (mf/use-fn
         (mf/deps file selected?)
         (fn [event]
           (dom/prevent-default event)
           (when-not selected?
             (when-not (kbd/shift? event)
               (st/emit! (dd/clear-selected-files)))
             (st/emit! (dd/toggle-file-select file)))

           (let [position (dom/get-client-position event)]
             (.log js/console "event" (clj->js event))
             (prn "position" position)
             (if (and (nil? (:y position)) (nil? (:x position)))
               (let [target-element (dom/get-target event)
                     points (dom/get-bounding-rect target-element)
                     position? (let [x (:top points)
                                     y (:left points)]
                                 (gpt/point x y))]

                 (.log js/console "target" (clj->js target-element))
                 (.log js/console "position?" (clj->js position?))
                 (swap! local assoc
                        :menu-open true
                        :menu-pos position?))
               (swap! local assoc
                      :menu-open true
                      :menu-pos position)))))

        edit
        (mf/use-fn
         (mf/deps file)
         (fn [name]
           (st/emit! (dd/rename-file (assoc file :name name)))
           (swap! local assoc :edition false)))

        on-edit
        (mf/use-fn
         (mf/deps file)
         (fn [event]
           (dom/stop-propagation event)
           (swap! local assoc
                  :edition true
                  :menu-open false)))]

    (mf/with-effect [selected? local]
      (when (and (not selected?) (:menu-open @local))
        (swap! local assoc :menu-open false)))

    [:li.grid-item.project-th
     [:a
      {:tab-index "0"
       :class (dom/classnames :selected selected?
                              :library library-view?)
       :ref node-ref
       :draggable true
       :on-click on-select
       :on-key-down (fn [event]
                      (dom/stop-propagation event)
                      (when (kbd/enter? event)
                        (on-navigate event))
                      (when (kbd/shift? event)
                        (when (or (kbd/down-arrow? event) (kbd/left-arrow? event) (kbd/up-arrow? event) (kbd/right-arrow? event))
                          (on-select event)) ;; TODO Fix this
                        ))
       :on-double-click on-navigate
       :on-drag-start on-drag-start
       :on-context-menu on-menu-click}

      [:div.overlay]
      (if library-view?
        [:& grid-item-library {:file file}]
        [:& grid-item-thumbnail {:file file}])
      (when (and (:is-shared file) (not library-view?))
        [:div.item-badge i/library])
      [:div.info-wrapper
       [:div.item-info
        (if (:edition @local)
          [:& inline-edition {:content (:name file)
                              :on-end edit}]
          [:h3 (:name file)])
        [:& grid-item-metadata {:modified-at (:modified-at file)}]]
       [:div.project-th-actions {:class (dom/classnames
                                         :force-display (:menu-open @local))}
        [:div.project-th-icon.menu
         {:tab-index "0"
          :ref menu-ref
          :on-click on-menu-click
          :on-key-down (fn [event]
                         (when (kbd/enter? event)
                           (on-menu-click event)))}
         i/actions
         (when selected?
           [:& file-menu {:files (vals selected-files)
                          :show? (:menu-open @local)
                          :left (+ 24 (:x (:menu-pos @local)))
                          :top (:y (:menu-pos @local))
                          :navigate? navigate?
                          :on-edit on-edit
                          :on-menu-close on-menu-close
                          :origin origin
                          :dashboard-local dashboard-local}])]]]]]))


(mf/defc grid
  [{:keys [files project origin limit library-view? create-fn] :as props}]
  (let [dragging?  (mf/use-state false)
        project-id (:id project)
        node-ref   (mf/use-var nil)

        on-finish-import
        (mf/use-fn
         (fn []
           (st/emit! (dd/fetch-files {:project-id project-id})
                     (dd/fetch-shared-files)
                     (dd/clear-selected-files))))

        import-files (use-import-file project-id on-finish-import)

        on-drag-enter
        (mf/use-fn
         (fn [e]
           (when (or (dnd/has-type? e "Files")
                     (dnd/has-type? e "application/x-moz-file"))
             (dom/prevent-default e)
             (reset! dragging? true))))

        on-drag-over
        (mf/use-fn
         (fn [e]
           (when (or (dnd/has-type? e "Files")
                     (dnd/has-type? e "application/x-moz-file"))
             (dom/prevent-default e))))

        on-drag-leave
        (mf/use-fn
         (fn [e]
           (when-not (dnd/from-child? e)
             (reset! dragging? false))))

        on-drop
        (mf/use-fn
         (fn [e]
           (when (or (dnd/has-type? e "Files")
                     (dnd/has-type? e "application/x-moz-file"))
             (dom/prevent-default e)
             (reset! dragging? false)
             (import-files (.-files (.-dataTransfer e))))))]

    [:div.dashboard-grid
     {:on-drag-enter on-drag-enter
      :on-drag-over on-drag-over
      :on-drag-leave on-drag-leave
      :on-drop on-drop
      :ref node-ref}
     (cond
       (nil? files)
       [:& loading-placeholder]

       (seq files)
       [:ul.grid-row
        {:style {:grid-template-columns (str "repeat(" limit ", 1fr)")}}

        (when @dragging?
          [:li.grid-item])

        (for [item files]
          [:& grid-item
           {:file item
            :key (:id item)
            :navigate? true
            :origin origin
            :library-view? library-view?}])]

       :else
       [:& empty-placeholder
        {:limit limit
         :create-fn create-fn
         :origin origin}])]))

(mf/defc line-grid-row
  [{:keys [files selected-files dragging? limit] :as props}]
  (let [elements limit
        limit (if dragging? (dec limit) limit)]
    [:ul.grid-row.no-wrap
     {:style {:grid-template-columns (dm/str "repeat(" elements ", 1fr)")}}

     (when dragging?
       [:li.grid-item.dragged])
     (for [item (take limit files)]
       [:& grid-item
        {:id (:id item)
         :file item
         :selected-files selected-files
         :key (:id item)
         :navigate? false}])]))

(mf/defc line-grid
  [{:keys [project team files limit create-fn] :as props}]
  (let [dragging?        (mf/use-state false)
        project-id       (:id project)
        team-id          (:id team)

        selected-files   (mf/deref refs/dashboard-selected-files)
        selected-project (mf/deref refs/dashboard-selected-project)

        on-finish-import
        (mf/use-fn
         (mf/deps team-id)
         (fn []
           (st/emit! (dd/fetch-recent-files (:id team))
                     (dd/clear-selected-files))))

        import-files (use-import-file project-id on-finish-import)

        on-drag-enter
        (mf/use-fn
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
        (mf/use-fn
         (fn [e]
           (when (or (dnd/has-type? e "penpot/files")
                     (dnd/has-type? e "Files")
                     (dnd/has-type? e "application/x-moz-file"))
             (dom/prevent-default e))))

        on-drag-leave
        (mf/use-fn
         (fn [e]
           (when-not (dnd/from-child? e)
             (reset! dragging? false))))

        on-drop-success
        (fn []
          (st/emit! (msg/success (tr "dashboard.success-move-file"))
                    (dd/fetch-recent-files (:id team))
                    (dd/clear-selected-files)))

        on-drop
        (mf/use-fn
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

    [:div.dashboard-grid {:on-drag-enter on-drag-enter
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
                          :dragging? @dragging?
                          :limit limit}]

       :else
       [:& empty-placeholder
        {:dragging? @dragging?
         :limit limit
         :create-fn create-fn}])]))

