;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.workspace.sidebar.assets
  (:require
   [app.common.data :as d]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as geom]
   [app.common.media :as cm]
   [app.common.pages :as cp]
   [app.common.pages-helpers :as cph]
   [app.common.uuid :as uuid]
   [app.config :as cfg]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.texts :as dwt]
   [app.main.data.colors :as dc]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.exports :as exports]
   [app.main.ui.components.context-menu :refer [context-menu]]
   [app.main.ui.components.file-uploader :refer [file-uploader]]
   [app.main.ui.components.tab-container :refer [tab-container tab-element]]
   [app.main.ui.workspace.sidebar.options.typography :refer [typography-entry]]
   [app.main.ui.icons :as i]
   [app.main.ui.keyboard :as kbd]
   [app.main.ui.modal :as modal]
   [app.main.ui.shapes.icon :as icon]
   [app.util.data :refer [matches-search]]
   [app.util.dom :as dom]
   [app.util.dom.dnd :as dnd]
   [app.util.i18n :as i18n :refer [tr t]]
   [app.util.router :as rt]
   [app.util.timers :as timers]
   [app.util.text :as ut]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [rumext.alpha :as mf]))

(mf/defc components-box
  [{:keys [file-id local? components open? on-open on-close] :as props}]
  (let [state (mf/use-state {:menu-open false
                             :top nil
                             :left nil
                             :component-id nil})
        on-delete
        (mf/use-callback
         (mf/deps state)
         (fn []
           (st/emit! (dwl/delete-component {:id (:component-id @state)}))
           (st/emit! (dwl/sync-file nil))))

        on-context-menu
        (mf/use-callback
         (fn [component-id]
           (fn [event]
             (when local?
               (let [pos (dom/get-client-position event)
                     top (:y pos)
                     left (- (:x pos) 20)]
                 (dom/prevent-default event)
                 (swap! state assoc :menu-open true
                        :top top
                        :left left
                        :component-id component-id))))))

        on-drag-start
        (mf/use-callback
         (fn [component event]
           (dnd/set-data! event "app/component" {:file-id (if local? nil file-id)
                                                 :component component})
           (dnd/set-allowed-effect! event "move")))]

    [:div.asset-group
     [:div.group-title {:class (when (not open?) "closed")}
      [:span {:on-click #(if open? (on-close) (on-open))} i/arrow-slide (tr "workspace.assets.components")]
      [:span (str "\u00A0(") (count components) ")"]] ;; Unicode 00A0 is non-breaking space
     (when open?
       [:div.group-grid.big
        (for [component components]
          [:div.grid-cell {:key (:id component)
                           :draggable true
                           :on-context-menu (on-context-menu (:id component))
                           :on-drag-start (partial on-drag-start component)}
           [:& exports/component-svg {:group (get-in component [:objects (:id component)])
                                      :objects (:objects component)}]
           [:div.cell-name (:name component)]])])

     (when local?
       [:& context-menu
        {:selectable false
         :show (:menu-open @state)
         :on-close #(swap! state assoc :menu-open false)
         :top (:top @state)
         :left (:left @state)
         :options [[(tr "workspace.assets.delete") on-delete]]}])]))

(mf/defc graphics-box
  [{:keys [file-id local? objects open? on-open on-close] :as props}]
  (let [input-ref  (mf/use-ref nil)
        state      (mf/use-state {:menu-open false
                                  :top nil
                                  :left nil
                                  :object-id nil})

        add-graphic
        (mf/use-callback
         (fn []
           (on-open)
           (dom/click (mf/ref-val input-ref))))

        on-media-uploaded
        (mf/use-callback
         (mf/deps file-id)
         (fn [data]
           (st/emit! (dwl/add-media data))))

        on-selected
        (mf/use-callback
         (mf/deps file-id)
         (fn [js-files]
           (let [params (with-meta {:file-id file-id
                                    :local? false
                                    :js-files js-files}
                          {:on-success on-media-uploaded})]
             (st/emit! (dw/upload-media-objects params)))))

        on-delete
        (mf/use-callback
         (mf/deps state)
         (fn []
           (let [params {:id (:object-id @state)}]
             (st/emit! (dwl/delete-media params)))))

        on-context-menu
        (mf/use-callback
         (fn [object-id]
           (fn [event]
             (when local?
               (let [pos (dom/get-client-position event)
                     top (:y pos)
                     left (- (:x pos) 20)]
                 (dom/prevent-default event)
                 (swap! state assoc :menu-open true
                        :top top
                        :left left
                        :object-id object-id))))))

        on-drag-start
        (mf/use-callback
         (fn [path event]
           (dnd/set-data! event "text/uri-list" (cfg/resolve-media-path path))
           (dnd/set-allowed-effect! event "move")))]

    [:div.asset-group
     [:div.group-title {:class (when (not open?) "closed")}
      [:span {:on-click #(if open? (on-close) (on-open))} i/arrow-slide (tr "workspace.assets.graphics")]
      [:span.num-assets (str "\u00A0(") (count objects) ")"] ;; Unicode 00A0 is non-breaking space
      (when local?
        [:div.group-button {:on-click add-graphic}
         i/plus
         [:& file-uploader {:accept cm/str-media-types
                            :multi true
                            :input-ref input-ref
                            :on-selected on-selected}]])]
     (when open?
       [:div.group-grid
        (for [object objects]
          [:div.grid-cell {:key (:id object)
                           :draggable true
                           :on-context-menu (on-context-menu (:id object))
                           :on-drag-start (partial on-drag-start (:path object))}
           [:img {:src (cfg/resolve-media-path (:thumb-path object))
                  :draggable false}] ;; Also need to add css pointer-events: none
           [:div.cell-name (:name object)]])

        (when local?
          [:& context-menu
           {:selectable false
            :show (:menu-open @state)
            :on-close #(swap! state assoc :menu-open false)
            :top (:top @state)
            :left (:left @state)
            :options [[(tr "workspace.assets.delete") on-delete]]}])])]))

(mf/defc color-item
  [{:keys [color local? locale file-id] :as props}]
  (let [rename?   (= (:color-for-rename @refs/workspace-local) (:id color))
        id        (:id color)
        input-ref (mf/use-ref)
        state     (mf/use-state {:menu-open false
                                 :top nil
                                 :left nil
                                 :editing rename?})
        click-color
        (fn [event]
          (let [ids (get-in @st/state [:workspace-local :selected])]
            (if (kbd/shift? event)
              (st/emit! (dc/change-stroke ids (:value color) id (if local? nil file-id)))
              (st/emit! (dc/change-fill ids (:value color) id (if local? nil file-id))))))

        rename-color
        (fn [name]
          (st/emit! (dwl/update-color (assoc color :name name))))

        edit-color
        (fn [value]
          (st/emit! (dwl/update-color (assoc color :value value))))

        delete-color
        (fn []
          (st/emit! (dwl/delete-color color)))

        rename-color-clicked
        (fn [event]
          (when local?
            (dom/prevent-default event)
            (swap! state assoc :editing true)))

        input-blur
        (fn [event]
          (let [target (dom/event->target event)
                name (dom/get-value target)]
            (rename-color name)
            (st/emit! dwl/clear-color-for-rename)
            (swap! state assoc :editing false)))

        input-key-down
        (fn [event]
          (when (kbd/esc? event)
            (st/emit! dwl/clear-color-for-rename)
            (swap! state assoc :editing false))
          (when (kbd/enter? event)
            (input-blur event)))

        edit-color-clicked
        (fn [event]
          (modal/show! :colorpicker
                       {:x (.-clientX event)
                        :y (.-clientY event)
                        :on-accept edit-color
                        :value (:value color)
                        :disable-opacity true
                        :position :right}))

        on-context-menu
        (fn [event]
          (when local?
            (let [pos (dom/get-client-position event)
                  top (:y pos)
                  left (+ 10 (:x pos))]
              (dom/prevent-default event)
              (swap! state assoc
                     :menu-open true
                     :top top
                     :left left))))]

    (mf/use-effect
      (mf/deps (:editing @state))
      #(when (:editing @state)
         (let [input (mf/ref-val input-ref)]
           (dom/select-text! input))
         nil))

    [:div.group-list-item {:on-context-menu on-context-menu}
     [:div.color-block {:style {:background-color (:value color)}}]
     (if (:editing @state)
       [:input.element-name
        {:type "text"
         :ref input-ref
         :on-blur input-blur
         :on-key-down input-key-down
         :auto-focus true
         :default-value (:name color "")}]
       [:div.name-block
        {:on-double-click rename-color-clicked
         :on-click click-color}
        (:name color)
        (when-not (= (:name color) (:value color))
          [:span (:value color)])])
     (when local?
       [:& context-menu
         {:selectable false
          :show (:menu-open @state)
          :on-close #(swap! state assoc :menu-open false)
          :top (:top @state)
          :left (:left @state)
          :options [[(t locale "workspace.assets.rename") rename-color-clicked]
                    [(t locale "workspace.assets.edit") edit-color-clicked]
                    [(t locale "workspace.assets.delete") delete-color]]}])]))

(mf/defc colors-box
  [{:keys [file-id local? colors locale open? on-open on-close] :as props}]
  (let [add-color
        (mf/use-callback
         (mf/deps file-id)
         (fn [value opacity]
           (st/emit! (dwl/add-color value))))

        add-color-clicked
        (mf/use-callback
         (mf/deps file-id)
         (fn [event]
           (on-open)
           (modal/show! :colorpicker
                        {:x (.-clientX event)
                         :y (.-clientY event)
                         :on-accept add-color
                         :value "#406280"
                         :disable-opacity true
                         :position :right})))]
    [:div.asset-group
     [:div.group-title {:class (when (not open?) "closed")}
      [:span {:on-click #(if open? (on-close) (on-open))} i/arrow-slide (t locale "workspace.assets.colors")]
      [:span.num-assets (str "\u00A0(") (count colors) ")"] ;; Unicode 00A0 is non-breaking space
      (when local?
        [:div.group-button {:on-click add-color-clicked} i/plus])]
     (when open?
       [:div.group-list
        (for [color colors]
          [:& color-item {:key (:id color)
                          :color color
                          :file-id file-id
                          :local? local?
                          :locale locale}])])]))

(mf/defc typography-box
  [{:keys [file file-id local? typographies locale open? on-open on-close] :as props}]

  (let [state (mf/use-state {:detail-open? false
                             :menu-open? false
                             :top nil
                             :left nil})

        selected (mf/deref refs/selected-shapes)
        local (deref refs/workspace-local)

        add-typography
        (mf/use-callback
         (mf/deps file-id)
         (fn [value opacity]
           (st/emit! (dwl/add-typography ut/default-typography))))

        handle-change
        (mf/use-callback
         (mf/deps file-id)
         (fn [typography changes]
           (st/emit! (dwl/update-typography (merge typography changes)))))

        handle-typography-selection
        (fn [typography]
          (let [attrs (merge
                       {:typography-ref-file (when-not local? file-id)
                        :typography-ref-id (:id typography)}
                       (d/without-keys typography [:id :name]))]
            (run! #(st/emit! (dwt/update-text-attrs {:id % :editor (get-in local [:editors %]) :attrs attrs}))
                  selected)))

        on-context-menu
        (fn [id event]

          (when local?
            (let [pos (dom/get-client-position event)
                  top (:y pos)
                  left (- (:x pos) 20)]
              (dom/prevent-default event)
              (swap! state assoc
                     :menu-open? true
                     :top top
                     :left left
                     :id id))))

        closed-typography-edit
        (mf/use-callback
         (mf/deps file-id)
         (fn [event] ))

        handle-rename-typography-clicked
        (fn []
          (st/emit! #(assoc-in % [:workspace-local :rename-typography] (:id @state))))

        handle-edit-typography-clicked
        (fn []
          (st/emit! #(assoc-in % [:workspace-local :edit-typography] (:id @state))))

        handle-delete-typography
        (fn []
          (st/emit! (dwl/delete-typography (:id @state))))]

    (mf/use-effect
     (mf/deps local)
     (fn []
       (when (:rename-typography local)
         (st/emit! #(update % :workspace-local dissoc :rename-typography)))
       (when (:edit-typography local)
         (st/emit! #(update % :workspace-local dissoc :edit-typography)))))

    [:div.asset-group
     [:div.group-title {:class (when (not open?) "closed")}
      [:span {:on-click #(if open? (on-close) (on-open))} i/arrow-slide (t locale "workspace.assets.typography")]
      [:span.num-assets (str "\u00A0(") (count typographies) ")"] ;; Unicode 00A0 is non-breaking space
      (when local?
        [:div.group-button {:on-click add-typography} i/plus])]

     [:& context-menu
      {:selectable false
       :show (:menu-open? @state)
       :on-close #(swap! state assoc :menu-open? false)
       :top (:top @state)
       :left (:left @state)
       :options [[(t locale "workspace.assets.rename") handle-rename-typography-clicked]
                 [(t locale "workspace.assets.edit") handle-edit-typography-clicked]
                 [(t locale "workspace.assets.delete") handle-delete-typography]]}]
     (when open?
       [:div.group-list
        (for [typography (sort-by :ts typographies)]
          [:& typography-entry
           {:key (:id typography)
            :typography typography
            :file file
            :read-only? (not local?)
            :on-context-menu #(on-context-menu (:id typography) %)
            :on-change #(handle-change typography %)
            :on-select #(handle-typography-selection typography)
            :editting? (or (= (:rename-typography local) (:id typography))
                           (= (:edit-typography local) (:id typography)))
            :focus-name? (= (:rename-typography local) (:id typography))}])])]))

(defn file-colors-ref
  [id]
  (l/derived (fn [state]
               (let [wfile (:workspace-file state)]
                 (if (= (:id wfile) id)
                   (vals (get-in wfile [:data :colors]))
                   (vals (get-in state [:workspace-libraries id :data :colors])))))
             st/state =))


(defn file-media-ref
  [id]
  (l/derived (fn [state]
               (let [wfile (:workspace-file state)]
                 (if (= (:id wfile) id)
                   (vals (get-in wfile [:data :media]))
                   (vals (get-in state [:workspace-libraries id :data :media])))))
             st/state =))

(defn file-components-ref
  [id]
  (l/derived (fn [state]
               (let [wfile (:workspace-file state)]
                 (if (= (:id wfile) id)
                   (vals (get-in wfile [:data :components]))
                   (vals (get-in state [:workspace-libraries id :data :components])))))
             st/state =))

(defn file-typography-ref
  [id]
  (l/derived (fn [state]
               (let [wfile (:workspace-file state)]
                 (if (= (:id wfile) id)
                   (vals (get-in wfile [:data :typography]))
                   (vals (get-in state [:workspace-libraries id :data :typography])))))
             st/state =))

(defn apply-filters
  [coll filters]
  (->> coll
    (filter (fn [item]
              (or (matches-search (:name item "!$!") (:term filters))
                  (matches-search (:value item "!$!") (:term filters)))))
    (sort-by #(str/lower (:name %)))))

(mf/defc file-library
  [{:keys [file local? open? filters locale] :as props}]
  (let [open?          (mf/use-state open?)
        shared?        (:is-shared file)
        router         (mf/deref refs/router)
        toggle-open    #(swap! open? not)

        toggles        (mf/use-state #{:components
                                       :graphics
                                       :colors
                                       :typography})

        url            (rt/resolve router :workspace
                                   {:project-id (:project-id file)
                                    :file-id (:id file)}
                                   {:page-id (get-in file [:data :pages 0])})

        colors-ref     (mf/use-memo (mf/deps (:id file)) #(file-colors-ref (:id file)))
        colors         (apply-filters (mf/deref colors-ref) filters)

        typography-ref (mf/use-memo (mf/deps (:id file)) #(file-typography-ref (:id file)))
        typographies (apply-filters (mf/deref typography-ref) filters)

        media-ref      (mf/use-memo (mf/deps (:id file)) #(file-media-ref (:id file)))
        media          (apply-filters (mf/deref media-ref) filters)

        components-ref (mf/use-memo (mf/deps (:id file)) #(file-components-ref (:id file)))
        components     (apply-filters (mf/deref components-ref) filters)]

    [:div.tool-window
     [:div.tool-window-bar
      [:div.collapse-library
       {:class (dom/classnames :open @open?)
        :on-click toggle-open}
       i/arrow-slide]

      (if local?
        [:*
          [:span (t locale "workspace.assets.file-library")]
          (when shared?
            [:span.tool-badge (t locale "workspace.assets.shared")])]
        [:*
          [:span (:name file)]
          [:span.tool-link
           [:a {:href (str "#" url) :target "_blank"} i/chain]]])]

     (when @open?
       (let [show-components?   (and (or (= (:box filters) :all)
                                         (= (:box filters) :components))
                                     (or (> (count components) 0)
                                         (str/empty? (:term filters))))
             show-graphics?     (and (or (= (:box filters) :all)
                                         (= (:box filters) :graphics))
                                     (or (> (count media) 0)
                                         (str/empty? (:term filters))))
             show-colors?       (and (or (= (:box filters) :all)
                                         (= (:box filters) :colors))
                                     (or (> (count colors) 0)
                                         (str/empty? (:term filters))))
             show-typography?   (and (or (= (:box filters) :all)
                                         (= (:box filters) :typography))
                                     (or (> (count colors) 0)
                                         (str/empty? (:term filters))))]
         [:div.tool-window-content
          (when show-components?
            [:& components-box {:file-id (:id file)
                                :local? local?
                                :components components
                                :open? (contains? @toggles :components)
                                :on-open #(swap! toggles conj :components)
                                :on-close #(swap! toggles disj :components)}])
          (when show-graphics?
            [:& graphics-box {:file-id (:id file)
                              :local? local?
                              :objects media
                              :open? (contains? @toggles :graphics)
                              :on-open #(swap! toggles conj :graphics)
                              :on-close #(swap! toggles disj :graphics)}])
          (when show-colors?
            [:& colors-box {:file-id (:id file)
                            :local? local?
                            :locale locale
                            :colors colors
                            :open? (contains? @toggles :colors)
                            :on-open #(swap! toggles conj :colors)
                            :on-close #(swap! toggles disj :colors)}])

          (when show-typography?
            [:& typography-box {:file file
                                :file-id (:id file)
                                :local? local?
                                :locale locale
                                :typographies typographies
                                :open? (contains? @toggles :typography)
                                :on-open #(swap! toggles conj :typography)
                                :on-close #(swap! toggles disj :typography)}])

          (when (and (not show-components?) (not show-graphics?) (not show-colors?))
            [:div.asset-group
             [:div.group-title (t locale "workspace.assets.not-found")]])]))]))


(mf/defc assets-toolbox
  [{:keys [team-id file] :as props}]
  (let [libraries (mf/deref refs/workspace-libraries)
        locale    (mf/deref i18n/locale)
        filters   (mf/use-state {:term "" :box :all})

        on-search-term-change
        (mf/use-callback
         (mf/deps team-id)
         (fn [event]
           (let [value (-> (dom/get-target event)
                           (dom/get-value))]
             (swap! filters assoc :term value))))

        on-search-clear-click
        (mf/use-callback
         (mf/deps team-id)
         (fn [event]
           (swap! filters assoc :term "")))

        on-box-filter-change
        (mf/use-callback
         (mf/deps team-id)
         (fn [event]
           (let [value (-> (dom/get-target event)
                           (dom/get-value)
                           (d/read-string))]
             (swap! filters assoc :box value))))]

    [:div.assets-bar
     [:div.tool-window
       [:div.tool-window-content
        [:div.assets-bar-title
         (t locale "workspace.assets.assets")
         [:div.libraries-button {:on-click #(modal/show! :libraries-dialog {})}
          i/libraries
          (t locale "workspace.assets.libraries")]]

        [:div.search-block
          [:input.search-input
           {:placeholder (tr "workspace.assets.search")
            :type "text"
            :value (:term @filters)
            :on-change on-search-term-change}]
          (if (str/empty? (:term @filters))
            [:div.search-icon
             i/search]
            [:div.search-icon.close
             {:on-click on-search-clear-click}
             i/close])]

        [:select.input-select {:value (:box @filters)
                               :on-change on-box-filter-change}
         [:option {:value ":all"} (t locale "workspace.assets.box-filter-all")]
         [:option {:value ":components"} (t locale "workspace.assets.components")]
         [:option {:value ":graphics"} (t locale "workspace.assets.graphics")]
         [:option {:value ":colors"} (t locale "workspace.assets.colors")]
         [:option {:value ":typography"} (t locale "workspace.assets.typography")]]]]

     [:div.libraries-wrapper
      [:& file-library
       {:file file
        :locale locale
        :local? true
        :open? true
        :filters @filters}]

      (for [file (->> (vals libraries)
                      (sort-by #(str/lower (:name %))))]
        [:& file-library
         {:key (:id file)
          :file file
          :local? false
          :locale locale
          :open? false
          :filters @filters}])]]))

