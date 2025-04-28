;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.dashboard.grid
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.point :as gpt]
   [app.common.logging :as log]
   [app.config :as cf]
   [app.main.data.common :as dcm]
   [app.main.data.dashboard :as dd]
   [app.main.data.notifications :as ntf]
   [app.main.data.project :as dpj]
   [app.main.data.team :as dtm]
   [app.main.fonts :as fonts]
   [app.main.rasterizer :as thr]
   [app.main.refs :as refs]
   [app.main.render :as render]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.main.ui.components.color-bullet :as bc]
   [app.main.ui.components.portal :refer [portal-on-document*]]
   [app.main.ui.dashboard.file-menu :refer [file-menu*]]
   [app.main.ui.dashboard.import :refer [use-import-file]]
   [app.main.ui.dashboard.inline-edition :refer [inline-edition]]
   [app.main.ui.dashboard.placeholder :refer [empty-grid-placeholder* loading-placeholder*]]
   [app.main.ui.ds.product.loader :refer [loader*]]
   [app.main.ui.hooks :as h]
   [app.main.ui.icons :as i]
   [app.main.worker :as wrk]
   [app.util.color :as uc]
   [app.util.dom :as dom]
   [app.util.dom.dnd :as dnd]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.time :as dt]
   [app.util.timers :as ts]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(log/set-level! :debug)

;; --- Grid Item Thumbnail

(defn- persist-thumbnail
  [file-id revn blob]
  (let [params {:file-id file-id :revn revn :media blob}]
    (->> (rp/cmd! :create-file-thumbnail params)
         (rx/map :id))))

(defn render-thumbnail
  [file-id revn]
  (->> (wrk/ask! {:cmd :thumbnails/generate-for-file
                  :revn revn
                  :file-id file-id
                  :features (get @st/state :features)})
       (rx/mapcat (fn [{:keys [fonts] :as result}]
                    (->> (fonts/render-font-styles fonts)
                         (rx/map (fn [styles]
                                   (assoc result
                                          :styles styles
                                          :width 252))))))))

(defn- ask-for-thumbnail
  "Creates some hooks to handle the files thumbnails cache"
  [file-id revn]
  (->> (render-thumbnail file-id revn)
       (rx/mapcat thr/render)
       (rx/mapcat (partial persist-thumbnail file-id revn))))

(mf/defc grid-item-thumbnail*
  {::mf/props :obj
   ::mf/private true}
  [{:keys [can-edit file]}]
  (let [file-id      (get file :id)
        revn         (get file :revn)
        thumbnail-id (get file :thumbnail-id)

        bg-color     (dm/get-in file [:data :background])

        container    (mf/use-ref)
        visible?     (h/use-visible container :once? true)]

    (mf/with-effect [file-id revn visible? thumbnail-id]
      (when (and visible? (not thumbnail-id))
        (let [subscription
              (->> (ask-for-thumbnail file-id revn)
                   (rx/subs! (fn [thumbnail-id]
                               (st/emit! (dd/set-file-thumbnail file-id thumbnail-id)))
                             (fn [cause]
                               (log/error :hint "unable to render thumbnail"
                                          :file-if file-id
                                          :revn revn
                                          :message (ex-message cause)))))]
          (partial rx/dispose! subscription))))

    [:div {:class (stl/css :grid-item-th)
           :style {:background-color bg-color}
           :ref container}
     (when visible?
       (if thumbnail-id
         [:img {:class (stl/css :grid-item-thumbnail-image)
                :draggable (dm/str can-edit)
                :src (cf/resolve-media thumbnail-id)
                :loading "lazy"
                :decoding "async"}]
         [:> loader* {:class (stl/css :grid-loader)
                      :draggable (dm/str can-edit)
                      :overlay true
                      :title (tr "labels.loading")}]))]))

;; --- Grid Item Library

(def ^:private menu-icon
  (i/icon-xref :menu (stl/css :menu-icon)))

(mf/defc grid-item-library*
  {::mf/props :obj}
  [{:keys [file]}]
  (mf/with-effect [file]
    (when file
      (let [font-ids (map :font-id (get-in file [:library-summary :typographies :sample] []))]
        (run! fonts/ensure-loaded! font-ids))))

  [:div {:class (stl/css :grid-item-th :library)}
   (if (nil? file)
     [:> loader* {:class (stl/css :grid-loader)
                  :overlay true
                  :title (tr "labels.loading")}]
     (let [summary (:library-summary file)
           components (:components summary)
           colors (:colors summary)
           typographies (:typographies summary)]
       [:*
        (when (and (zero? (:count components)) (zero? (:count colors)) (zero? (:count typographies)))
          [:*
           [:div {:class (stl/css :asset-section)}
            [:div {:class (stl/css :asset-title)}
             [:span (tr "workspace.assets.components")]
             [:span {:class (stl/css :num-assets)} (str "\u00A0(") 0 ")"]]] ;; Unicode 00A0 is non-breaking space
           [:div {:class (stl/css :asset-section)}
            [:div {:class (stl/css :asset-title)}
             [:span (tr "workspace.assets.colors")]
             [:span {:class (stl/css :num-assets)} (str "\u00A0(") 0 ")"]]] ;; Unicode 00A0 is non-breaking space
           [:div {:class (stl/css :asset-section)}
            [:div {:class (stl/css :asset-title)}
             [:span (tr "workspace.assets.typography")]
             [:span {:class (stl/css :num-assets)} (str "\u00A0(") 0 ")"]]]]) ;; Unicode 00A0 is non-breaking space


        (when (pos? (:count components))
          [:div {:class (stl/css :asset-section)}
           [:div {:class (stl/css :asset-title)}
            [:span (tr "workspace.assets.components")]
            [:span {:class (stl/css :num-assets)} (str "\u00A0(") (:count components) ")"]] ;; Unicode 00A0 is non-breaking space
           [:div {:class (stl/css :asset-list)}
            (for [component (:sample components)]
              (let [root-id (or (:main-instance-id component) (:id component))] ;; Check for components-v2 in library
                [:div {:class (stl/css :asset-list-item)
                       :key (str "assets-component-" (:id component))}
                 [:& render/component-svg {:root-shape (get-in component [:objects root-id])
                                           :objects (:objects component)}] ;; Components in the summary come loaded with objects, even in v2
                 [:div {:class (stl/css :name-block)}
                  [:span {:class (stl/css :item-name)
                          :title (:name component)}
                   (:name component)]]]))
            (when (> (:count components) (count (:sample components)))
              [:div {:class (stl/css :asset-list-item)}
               [:div {:class (stl/css :name-block)}
                [:span {:class (stl/css :item-name)} "(...)"]]])]])

        (when (pos? (:count colors))
          [:div {:class (stl/css :asset-section)}
           [:div {:class (stl/css :asset-title)}
            [:span (tr "workspace.assets.colors")]
            [:span {:class (stl/css :num-assets)} (str "\u00A0(") (:count colors) ")"]] ;; Unicode 00A0 is non-breaking space
           [:div {:class (stl/css :asset-list)}
            (for [color (:sample colors)]
              (let [default-name (cond
                                   (:gradient color) (uc/gradient-type->string (get-in color [:gradient :type]))
                                   (:color color) (:color color)
                                   :else (:value color))]
                [:div {:class (stl/css :asset-list-item :color-item)
                       :key (str "assets-color-" (:id color))}
                 [:& bc/color-bullet {:color {:color (:color color)
                                              :id (:id color)
                                              :opacity (:opacity color)}
                                      :mini true}]
                 [:div {:class (stl/css :name-block)}
                  [:span {:class (stl/css :color-name)} (:name color)]
                  (when-not (= (:name color) default-name)
                    [:span {:class (stl/css :color-value)} (:color color)])]]))

            (when (> (:count colors) (count (:sample colors)))
              [:div {:class (stl/css :asset-list-item)}
               [:div {:class (stl/css :name-block)}
                [:span {:class (stl/css :item-name)} "(...)"]]])]])

        (when (pos? (:count typographies))
          [:div {:class (stl/css :asset-section)}
           [:div {:class (stl/css :asset-title)}
            [:span (tr "workspace.assets.typography")]
            [:span {:class (stl/css :num-assets)} (str "\u00A0(") (:count typographies) ")"]] ;; Unicode 00A0 is non-breaking space
           [:div {:class (stl/css :asset-list)}
            (for [typography (:sample typographies)]
              [:div {:class (stl/css :asset-list-item)
                     :key (str "assets-typography-" (:id typography))}
               [:div {:class (stl/css :typography-sample)
                      :style {:font-family (:font-family typography)
                              :font-weight (:font-weight typography)
                              :font-style (:font-style typography)}}
                (tr "workspace.assets.typography.sample")]
               [:div {:class (stl/css :name-block)}
                [:span {:class (stl/css :item-name)
                        :title (:name typography)}
                 (:name typography)]]])

            (when (> (:count typographies) (count (:sample typographies)))
              [:div {:class (stl/css :asset-list-item)}
               [:div {:class (stl/css :name-block)}
                [:span {:class (stl/css :item-name)} "(...)"]]])]])]))])

;; --- Grid Item

(mf/defc grid-item-metadata
  [{:keys [modified-at]}]

  (let [locale (mf/deref i18n/locale)
        time   (dt/timeago modified-at {:locale locale})]
    [:span {:class (stl/css :date)} time]))

(defn create-counter-element
  [_element file-count]
  (let [counter-el (dom/create-element "div")]
    (dom/set-property! counter-el "class" (stl/css :drag-counter))
    (dom/set-text! counter-el (str file-count))
    counter-el))

(mf/defc grid-item*
  [{:keys [file origin can-edit selected-files]}]
  (let [file-id  (get file :id)
        state    (mf/deref refs/dashboard-local)

        menu-pos
        (get state :menu-pos)

        menu-open?
        (and (get state :menu-open)
             (= file-id (:file-id state)))

        selected?
        (contains? selected-files file-id)

        selected-num
        (count selected-files)

        node-ref     (mf/use-ref)
        menu-ref     (mf/use-ref)

        is-library-view?
        (= origin :libraries)

        on-menu-close
        (mf/use-fn #(st/emit! (dd/hide-file-menu)))

        on-select
        (mf/use-fn
         (mf/deps selected? selected-num)
         (fn [event]
           (when (or (not selected?) (> selected-num 1))
             (dom/stop-propagation event)
             (let [shift? (kbd/shift? event)]
               (when-not shift?
                 (st/emit! (dd/clear-selected-files)))
               (st/emit! (dd/toggle-file-select file))))))

        on-navigate
        (mf/use-fn
         (mf/deps file-id)
         (fn [event]
           (let [menu-icon (mf/ref-val menu-ref)
                 target    (dom/get-target event)]
             (when-not (dom/child? target menu-icon)
               (st/emit! (dcm/go-to-workspace :file-id file-id))))))

        on-drag-start
        (mf/use-fn
         (mf/deps selected? selected-num)
         (fn [event]
           (st/emit! (dd/hide-file-menu))
           (when can-edit
             (let [offset     (dom/get-offset-position (dom/event->native-event event))
                   item-el    (mf/ref-val node-ref)
                   counter-el (create-counter-element item-el
                                                      (if (not selected?)
                                                        1
                                                        selected-num))]
               (when (not selected?)
                 (st/emit! (dd/clear-selected-files))
                 (st/emit! (dd/toggle-file-select file)))

               (dnd/set-data! event "penpot/files" "dummy")
               (dnd/set-allowed-effect! event "move")

               ;; set-drag-image requires that the element is rendered
               ;; and visible to the user at the moment of creating the
               ;; ghost image (to make a snapshot), but you may remove
               ;; it right afterwards, in the next render cycle.
               (dom/append-child! item-el counter-el)
               (dnd/set-drag-image! event item-el (:x offset) (:y offset))
               (ts/raf #(dom/remove-child! item-el counter-el))))))

        on-menu-click
        (mf/use-fn
         (mf/deps file selected?)
         (fn [event]
           (dom/stop-propagation event)

           (when-not selected?
             (when-not (kbd/shift? event)
               (st/emit! (dd/clear-selected-files)))
             (do
               (st/emit! (dd/toggle-file-select file))))

           (let [client-position
                 (dom/get-client-position event)

                 position
                 (if (and (nil? (:y client-position)) (nil? (:x client-position)))
                   (let [target-element (dom/get-target event)
                         points         (dom/get-bounding-rect target-element)
                         y              (:top points)
                         x              (:left points)]
                     (gpt/point x y))
                   client-position)]

             (st/emit! (dd/show-file-menu-with-position file-id position)))))

        on-context-menu
        (mf/use-fn
         (mf/deps on-menu-click)
         (fn [event]
           (dom/prevent-default event)
           (on-menu-click event)))

        edit
        (mf/use-fn
         (mf/deps file)
         (fn [name]
           (let [name (str/trim name)]
             (when (not= name "")
               (st/emit! (dd/rename-file (assoc file :name name)))))
           (st/emit! (dd/stop-edit-file-name))))

        on-edit
        (mf/use-fn
         (mf/deps file)
         (fn [event]
           (dom/stop-propagation event)
           (st/emit! (dd/start-edit-file-name file-id))))

        on-key-down
        (mf/use-fn
         (mf/deps on-navigate on-select)
         (fn [event]
           (dom/stop-propagation event)
           (when (kbd/enter? event)
             (on-navigate event))
           (when (kbd/shift? event)
             (when (or (kbd/down-arrow? event) (kbd/left-arrow? event) (kbd/up-arrow? event) (kbd/right-arrow? event))
               ;; TODO Fix this
               (on-select event)))))

        on-menu-key-down
        (mf/use-fn
         (mf/deps on-menu-click)
         (fn [event]
           (when (kbd/enter? event)
             (dom/stop-propagation event)
             (dom/prevent-default event)
             (on-menu-click event))))]

    [:li {:class (stl/css-case :grid-item true
                               :project-th true
                               :library is-library-view?)}
     [:div
      {:class (stl/css-case :selected selected?
                            :library is-library-view?)
       :ref node-ref
       :role "button"
       :title (:name file)
       :draggable (dm/str can-edit)
       :on-click on-select
       :on-key-down on-key-down
       :on-double-click on-navigate
       :on-drag-start on-drag-start
       :on-context-menu on-context-menu}

      [:div {:class (stl/css :overlay)}]

      (if ^boolean is-library-view?
        [:> grid-item-library* {:file file}]
        [:> grid-item-thumbnail* {:file file :can-edit can-edit}])

      (when (and (:is-shared file) (not is-library-view?))
        [:div {:class (stl/css :item-badge)} i/library])

      [:div {:class (stl/css :info-wrapper)}
       [:div {:class (stl/css :item-info)}
        (if (and (= file-id (:file-id state)) (:edition state))
          [:& inline-edition {:content (:name file)
                              :on-end edit}]
          [:h3 (:name file)])
        [:& grid-item-metadata {:modified-at (:modified-at file)}]]

       [:div {:class (stl/css-case :project-th-actions true :force-display menu-open?)}
        [:div
         {:class (stl/css :project-th-icon :menu)
          :tab-index "0"
          :role "button"
          :aria-label (tr "dashboard.options")
          :ref menu-ref
          :id (dm/str file-id "-action-menu")
          :on-click on-menu-click
          :on-key-down on-menu-key-down}

         menu-icon
         (when (and selected? menu-open?)
           ;; When the menu is open we disable events in the dashboard. We need to force pointer events
           ;; so the menu can be handled
           [:> portal-on-document* {}
            [:> file-menu* {:files (vals selected-files)
                            :left (+ 24 (:x menu-pos))
                            :top (:y menu-pos)
                            :can-edit can-edit
                            :navigate true
                            :on-edit on-edit
                            :on-menu-close on-menu-close
                            :origin origin
                            :parent-id (dm/str file-id "-action-menu")}]])]]]]]))

(mf/defc grid*
  {::mf/props :obj}
  [{:keys [files project origin limit create-fn can-edit selected-files]}]
  (let [dragging?  (mf/use-state false)
        project-id (get project :id)
        team-id    (get project :team-id)

        node-ref   (mf/use-var nil)

        on-finish-import
        (mf/use-fn
         (mf/deps project-id team-id)
         (fn []
           (st/emit! (dpj/fetch-files project-id)
                     (dtm/fetch-shared-files team-id)
                     (dd/clear-selected-files))))

        import-files
        (use-import-file project-id on-finish-import)

        on-scroll
        (mf/use-fn #(st/emit! (dd/hide-file-menu)))

        on-drag-enter
        (mf/use-fn
         (fn [e]
           (when can-edit
             (when (and (not (dnd/has-type? e "penpot/files"))
                        (or (dnd/has-type? e "Files")
                            (dnd/has-type? e "application/x-moz-file")))
               (dom/prevent-default e)
               (reset! dragging? true)))))

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
           (if can-edit
             (when (and (not (dnd/has-type? e "penpot/files"))
                        (or (dnd/has-type? e "Files")
                            (dnd/has-type? e "application/x-moz-file")))
               (dom/prevent-default e)
               (reset! dragging? false)
               (import-files (.-files (.-dataTransfer e))))
             (dom/prevent-default e))))]

    [:div {:class (stl/css :dashboard-grid)
           :dragabble (dm/str can-edit)
           :on-drag-enter on-drag-enter
           :on-drag-over on-drag-over
           :on-drag-leave on-drag-leave
           :on-drop on-drop
           :on-scroll on-scroll
           :ref node-ref}
     (cond
       (nil? files)
       [:> loading-placeholder*]

       (seq files)
       (for [[index slice] (d/enumerate (partition-all limit files))]

         [:ul {:class (stl/css :grid-row) :key (dm/str index)}
          (when @dragging?
            [:li {:class (stl/css :grid-item)}])
          (for [item slice]
            [:> grid-item*
             {:file item
              :key (dm/str (:id item))
              :origin origin
              :selected-files selected-files
              :can-edit can-edit}])])

       :else
       [:> empty-grid-placeholder*
        {:limit limit
         :can-edit can-edit
         :create-fn create-fn
         :origin origin
         :project-id project-id
         :team-id team-id
         :on-finish-import on-finish-import}])]))

(mf/defc line-grid-row
  [{:keys [files selected-files dragging? limit can-edit] :as props}]
  (let [elements limit
        limit (if dragging? (dec limit) limit)]
    [:ul {:class (stl/css :grid-row :no-wrap)
          :style {:grid-template-columns (dm/str "repeat(" elements ", 1fr)")}}

     (when dragging?
       [:li {:class (stl/css :grid-item :dragged)}])

     (for [item (take limit files)]
       [:> grid-item*
        {:id (:id item)
         :file item
         :selected-files selected-files
         :can-edit can-edit
         :key (dm/str (:id item))}])]))

(mf/defc line-grid
  [{:keys [project team files limit create-fn can-edit] :as props}]
  (let [dragging?        (mf/use-state false)
        project-id       (:id project)
        team-id          (:id team)

        selected-files   (mf/deref refs/selected-files)
        selected-project (mf/deref refs/selected-project)

        on-finish-import
        (mf/use-fn
         (mf/deps team-id)
         (fn []
           (st/emit! (dd/fetch-recent-files team-id)
                     (dd/clear-selected-files))))

        import-files (use-import-file project-id on-finish-import)

        on-drag-enter
        (mf/use-fn
         (mf/deps selected-project can-edit)
         (fn [e]
           (when can-edit
             (cond
               (dnd/has-type? e "penpot/files")
               (do
                 (dom/prevent-default e)
                 (when-not (or (dnd/from-child? e)
                               (dnd/broken-event? e))
                   (when (not= selected-project project-id)
                     (reset! dragging? true))))

               (or (dnd/has-type? e "Files")
                   (dnd/has-type? e "application/x-moz-file"))
               (do
                 (dom/prevent-default e)
                 (reset! dragging? true))))))

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
        (mf/use-fn
         (mf/deps team-id)
         (fn []
           (st/emit! (ntf/success (tr "dashboard.success-move-file"))
                     (dd/fetch-recent-files team-id)
                     (dd/clear-selected-files))))

        on-drop
        (mf/use-fn
         (mf/deps files selected-files can-edit)
         (fn [e]
           (if can-edit
             (cond
               (dnd/has-type? e "penpot/files")
               (do
                 (reset! dragging? false)
                 (when (not= selected-project project-id)
                   (let [data  {:ids (into #{} (keys selected-files))
                                :project-id project-id}
                         mdata {:on-success on-drop-success}]
                     (st/emit! (dd/move-files (with-meta data mdata))))))

               (or (dnd/has-type? e "Files")
                   (dnd/has-type? e "application/x-moz-file"))
               (do
                 (dom/prevent-default e)
                 (reset! dragging? false)
                 (import-files (.-files (.-dataTransfer e)))))
             (dom/prevent-default e))))]

    [:div {:class (stl/css :dashboard-grid)
           :dragabble (dm/str can-edit)
           :on-drag-enter on-drag-enter
           :on-drag-over on-drag-over
           :on-drag-leave on-drag-leave
           :on-drop on-drop}
     (cond
       (nil? files)
       [:> loading-placeholder*]

       (seq files)
       [:& line-grid-row {:files files
                          :team-id team-id
                          :selected-files selected-files
                          :dragging? @dragging?
                          :can-edit can-edit
                          :limit limit}]

       :else
       [:> empty-grid-placeholder*
        {:is-dragging @dragging?
         :limit limit
         :can-edit can-edit
         :create-fn create-fn
         :project-id project-id
         :team-id team-id
         :on-finish-import on-finish-import}])]))
