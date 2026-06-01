;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.colorpicker.libraries
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.math :as mth]
   [app.common.path-names :as cpn]
   [app.common.types.color :as ctc]
   [app.common.uuid :as uuid]
   [app.main.data.event :as ev]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.colors :as mdc]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.context :as ctx]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.controls.select :refer [select*]]
   [app.main.ui.ds.foundations.assets.icon  :as i]
   [app.main.ui.ds.tooltip :refer [tooltip*]]
   [app.main.ui.ds.utilities.swatch :refer [swatch*] :as su]
   [app.main.ui.hooks :as h]
   [app.main.ui.hooks.resize :as r]
   [app.main.ui.workspace.sidebar.assets.groups :as grp]
   [app.util.color :as uc]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(mf/defc color-row-colorpicker*
  [{:keys [color-item on-click]}]
  (let [gradient (:gradient color-item)
        image    (:image color-item)
        color    (:color color-item)
        {:keys [name]} (meta color-item)
        element-id (mf/use-id)
        element-ref    (mf/use-ref nil)]
    [:> tooltip* {:content (su/color-title color-item)
                  :trigger-ref element-ref
                  :id element-id}

     [:div {:class    (stl/css :color-row-colorpicker)
            :ref      element-ref
            :aria-labelledby element-id
            :on-click on-click}

      [:> swatch* {:background color-item
                   :show-tooltip false
                   :size "medium"}]
      (cond
        gradient
        (if name
          [:span {:class (stl/css :color-row-colorpicker-label)}
           (str name)
           [:span {:class (stl/css :color-row-colorpicker-gradient-type)}
            (str " (" (uc/gradient-type->string (:type gradient)) ")")]]

          [:span {:class (stl/css :color-row-colorpicker-label)}
           (tr "media.gradient")
           [:span {:class (stl/css :color-row-colorpicker-gradient-type)}
            (str " (" (uc/gradient-type->string (:type gradient)) ")")]])

        image
        [:span (tr "media.image")]

        color
        (if name
          [:span {:class (stl/css :color-row-colorpicker-label)}
           name
           (when (and (number? (:opacity color-item)) (< (:opacity color-item) 1))
             [:span {:class (stl/css :color-row-colorpicker-opacity)}
              (str " (" (mth/round (* (:opacity color-item) 100)) "%)")])]
          [:span {:class (stl/css :color-row-colorpicker-label)}
           color
           (when (and (number? (:opacity color-item)) (< (:opacity color-item) 1))
             [:span {:class (stl/css :color-row-colorpicker-opacity)}
              (str " (" (mth/round (* (:opacity color-item) 100)) "%)")])])

        :else
        [:span (tr "unknown")])]]))

(mf/defc color-group-list*
  "Renders library colors organised by group/path in list view.
   `groups`             nested map produced by `grp/group-assets`
   `prefix`             accumulated group label (empty string at root)
   `resolved-file-id`   UUID of the library the colors belong to
   `on-color-click`     called with the converted color map on click
   `open-groups`        set of group paths that are currently collapsed
   `on-toggle-group`    (fn [path]) to toggle a group open/closed"
  [{:keys [groups prefix resolved-file-id on-color-click open-groups on-toggle-group]}]
  (let [direct-colors (get groups "")
        subgroups     (dissoc groups "")
        is-root?      (empty? prefix)
        collapsed?    (and (not is-root?) (contains? open-groups prefix))]
    [:*
     ;; Group header — only shown for non-root levels
     (when (not is-root?)
       [:div {:class    (stl/css :color-group-header)
              :on-click #(on-toggle-group prefix)}
         [:> i/icon* {:icon-id (if collapsed? i/arrow-right i/arrow-down)
                      :size    "s"
                      :class   (stl/css :color-group-arrow)}]
        [:span {:class (stl/css :color-group-name)} prefix]])

     ;; Children — hidden when the group is collapsed
     (when-not collapsed?
       [:*
        ;; Colors that live directly at this level
        (for [color direct-colors]
          (let [converted    (ctc/library-color->color color resolved-file-id)
                handle-click (fn [_] (on-color-click converted))]
            [:> color-row-colorpicker*
             {:key              (dm/str (:id color))
              :color-item       converted
              :on-click         handle-click}]))

        ;; Recurse into sub-groups
        (for [[group-name sub-tree] subgroups]
          [:> color-group-list*
           {:key              group-name
            :groups           sub-tree
            :prefix           (cpn/merge-path-item-with-dot prefix group-name)
            :resolved-file-id resolved-file-id
            :on-color-click   on-color-click
            :open-groups      open-groups
            :on-toggle-group  on-toggle-group}])])]))


(mf/defc libraries*
  [{:keys [state on-select-color on-add-library-color disable-gradient disable-opacity disable-image]}]
  (let [selected*        (h/use-shared-state mdc/colorpicker-selected-broadcast-key :recent)
        selected         (deref selected*)

        view-mode*       (mf/use-state :grid)
        view-mode        (deref view-mode*)

        file-id          (mf/use-ctx ctx/current-file-id)

        current-colors*  (mf/use-state [])
        current-colors   (deref current-colors*)

        grouped-colors*  (mf/use-state {})
        grouped-colors   (deref grouped-colors*)

        open-groups*     (mf/use-state #{})
        open-groups      (deref open-groups*)

        libraries        (mf/deref refs/libraries)
        recent-colors    (mf/deref refs/recent-colors)
        recent-colors    (mf/with-memo [recent-colors]
                           (filterv ctc/valid-color? recent-colors))

        library-options
        (mf/with-memo []
          [{:value "recent" :label  (tr "workspace.libraries.colors.recent-colors") :id "recent"}
           {:value "file" :label (tr "workspace.libraries.colors.file-library") :id "file"}])

        options
        (mf/with-memo [library-options libraries file-id]
          (into library-options
                (comp
                 (map val)
                 (map (fn [lib] {:value (d/name (:id lib)) :label (:name lib) :id (d/name (:id lib))})))
                (dissoc libraries file-id)))

        on-library-change
        (mf/use-fn
         (fn [event]
           (reset! selected*
                   (if (or (= event "recent")
                           (= event "file"))
                     (keyword event)
                     (uuid/parse event)))))

        valid-color?
        (mf/use-fn
         (mf/deps disable-gradient disable-opacity disable-image)
         (fn [color]
           (and (or (not disable-gradient) (not (:gradient color)))
                (or (not disable-opacity) (= 1 (:opacity color)))
                (or (not disable-image) (not (:image color))))))

        toggle-palette
        (mf/use-fn
         (mf/deps selected)
         (fn []
           (r/set-resize-type! :bottom)
           (dom/add-class!  (dom/get-element-by-class "color-palette") "fade-out-down")
           (st/emit! (dw/remove-layout-flag :textpalette)
                     (-> (mdc/show-palette selected)
                         (vary-meta assoc ::ev/origin "workspace-colorpicker")))))

        toggle-view-mode
        (mf/use-fn
         (mf/deps view-mode)
         (fn []
           (let [new-mode (if (= view-mode :grid) :list :grid)]
             (reset! view-mode* new-mode))))

        on-color-click
        (mf/use-fn
         (mf/deps state selected on-select-color)
         (fn [event]
           (when-not (= :recent selected)
             (st/emit! (ev/event
                        {::ev/name "use-library-color"
                         ::ev/origin "colorpicker"
                         :external-library (not= :file selected)})))
           (on-select-color state event)))

        on-toggle-group
        (mf/use-fn
         (fn [path]
           (swap! open-groups*
                  (fn [s]
                    (if (contains? s path)
                      (disj s path)
                      (conj s path))))))]

    ;; Load library colors when the select is changed.
    ;; Flat current-colors* is used for the grid view and recent list view.
    ;; Grouped grouped-colors* is used for the library list view.
    ;; open-groups* tracks collapsed group paths (empty set = all expanded).
    (mf/with-effect [selected recent-colors libraries file-id valid-color?]
      (let [resolved-file-id (if (= selected :file) file-id selected)]
        (reset! open-groups* #{})
        (if (= selected :recent)
          (let [colors (->> (reverse recent-colors)
                            (filter valid-color?)
                            (map-indexed (fn [index color]
                                           (let [color (if (map? color) color {:color color})]
                                             (vary-meta color assoc ::id (dm/str index)))))
                            (sort ctc/sort-colors))]
            (reset! current-colors* colors)
            (reset! grouped-colors* {}))

          (let [raw-colors  (->> (dm/get-in libraries [resolved-file-id :data :colors])
                                 (vals)
                                 (filter valid-color?)
                                 (sort-by :name))
                flat-colors (->> raw-colors
                                 (map #(ctc/library-color->color % resolved-file-id))
                                 (map-indexed (fn [index color]
                                                (vary-meta color assoc ::id (dm/str index)))))
                grouped     (grp/group-assets raw-colors false)]
            (reset! current-colors* flat-colors)
            (reset! grouped-colors* (or grouped {}))))))

    [:div {:class (stl/css :libraries)}
     [:div {:class (stl/css :select-wrapper)}

      [:> select* {:on-change on-library-change
                   :options options
                   :class (stl/css :library-select)
                   :default-selected (or (d/name selected) "recent")}]

      [:> icon-button*
       {:variant "ghost"
        :aria-label "Toggle palette"
        :on-click toggle-palette
        :icon i/swatches}]

      [:> icon-button*
       {:variant "ghost"
        :aria-label (if (= :grid view-mode)
                      "Switch to list view"
                      "Switch to grid view")
        :on-click toggle-view-mode
        :icon (if (= :grid view-mode)
                i/view-as-list
                i/view-as-icons)}]

      (when (= selected :file)
        [:> icon-button*
         {:variant "ghost"
          :aria-label "Add library color"
          :on-click on-add-library-color
          :icon i/add}])]

     (if (= view-mode :grid)
       [:div {:class (stl/css :selected-colors)}
        (for [color current-colors]
          [:> swatch* {:background color
                       :key (-> color meta ::id)
                       :on-click on-color-click
                       :size "medium"}])]

       [:div {:class (stl/css :selected-colors-list)}
        (if (= selected :recent)
          ;; Recent colors have no path/groups — render flat
          (for [color current-colors]
            [:> color-row-colorpicker*
             {:key      (-> color meta ::id)
              :color-item    color
              :on-click on-color-click}])

          ;; Library colors — grouped list view
          (when (seq grouped-colors)
            (let [resolved-file-id (if (= selected :file) file-id selected)]
              [:> color-group-list*
               {:groups           grouped-colors
                :prefix           ""
                :resolved-file-id resolved-file-id
                :on-color-click   on-color-click
                :open-groups      open-groups
                :on-toggle-group  on-toggle-group}])))])]))
