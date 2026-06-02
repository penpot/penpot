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

;; ---------------------------------------------------------------------------
;; Private helpers
;; ---------------------------------------------------------------------------

(defn- convert-grouped-colors
  "Walk the nested group tree produced by `grp/group-assets` and replace
  every raw library color (at the leaf `\"\"` vectors) with its converted
  form via `ctc/library-color->color`.  Done once inside the effect so the
  render path never needs to call `library-color->color`."
  [groups resolved-file-id]
  (reduce-kv
   (fn [acc group-key value]
     (assoc acc group-key
            (if (= group-key "")
              ;; leaf vector — convert each raw color
              (mapv #(ctc/library-color->color % resolved-file-id) value)
              ;; nested sub-tree — recurse, preserving sorted-map type
              (convert-grouped-colors value resolved-file-id))))
   (empty groups)
   groups))

;; ---------------------------------------------------------------------------
;; Color row
;; ---------------------------------------------------------------------------

(mf/defc color-row-colorpicker*
  "Single color row for the list view.  Memoized so it only re-renders when
  `color-item` or `on-click` actually changes."
  {::mf/memo true
   ::mf/private true}
  [{:keys [color-item on-click]}]
  (let [gradient     (:gradient color-item)
        image        (:image color-item)
        color        (:color color-item)
        {:keys [name]} (meta color-item)
        element-id   (mf/use-id)
        element-ref  (mf/use-ref nil)

        handle-click
        (mf/use-fn
         (mf/deps on-click color-item)
         (fn [_] (on-click color-item)))
        opacity-text (str " (" (mth/round (* (:opacity color-item) 100)) "%)")
        gradient-text (str " (" (uc/gradient-type->string (:type gradient)) ")")]

    [:> tooltip* {:content (su/color-title color-item)
                  :trigger-ref element-ref
                  :id element-id}

     [:div {:class           (stl/css :color-row-colorpicker)
            :ref             element-ref
            :role            "listitem"
            :aria-labelledby element-id
            :on-click        handle-click}

      [:> swatch* {:background   color-item
                   :show-tooltip false
                   :size         "medium"}]
      (cond
        gradient
        (if name
          [:span {:class (stl/css :color-row-colorpicker-label)}
           (str name)
           [:span {:class (stl/css :color-row-colorpicker-gradient-type)}
            gradient-text]]

          [:span {:class (stl/css :color-row-colorpicker-label)}
           (tr "media.gradient")
           [:span {:class (stl/css :color-row-colorpicker-gradient-type)}
            gradient-text]])

        image
        [:span (tr "media.image")]

        color
        (if name
          [:span {:class (stl/css :color-row-colorpicker-label)}
           name
           (when (and (number? (:opacity color-item)) (< (:opacity color-item) 1))
             [:span {:class (stl/css :color-row-colorpicker-opacity)}
              opacity-text])]
          [:span {:class (stl/css :color-row-colorpicker-label)}
           color
           (when (and (number? (:opacity color-item)) (< (:opacity color-item) 1))
             [:span {:class (stl/css :color-row-colorpicker-opacity)}
              opacity-text])])

        :else
        [:span (tr "labels.other")])]]))

;; ---------------------------------------------------------------------------
;; Grouped color list
;; ---------------------------------------------------------------------------

(mf/defc color-group-list*
  "Renders library colors organised by group/path in list view.
   `groups`             nested map produced by `grp/group-assets`, with colors
                        already converted via `convert-grouped-colors`
   `prefix`             accumulated group label (empty string at root)
   `resolved-file-id`   UUID of the library the colors belong to
   `on-color-click`     called with the converted color map on click
   `open-groups`        set of group paths that are currently collapsed
   `on-toggle-group`    (fn [path]) to toggle a group open/closed"
  {::mf/memo true
   ::mf/private true}
  [{:keys [groups prefix resolved-file-id on-color-click open-groups on-toggle-group]}]
  (let [direct-colors (get groups "")
        subgroups     (dissoc groups "")
        is-root?      (empty? prefix)
        collapsed?    (and (not is-root?) (contains? open-groups prefix))
        handle-toggle-group (mf/use-fn
                             (mf/deps prefix on-toggle-group)
                             (fn [_]
                               (on-toggle-group prefix)))]
    [:*
     (when (not is-root?)
       [:div {:class         (stl/css :color-group-header)
              :role          "button"
              :tab-index     0
              :aria-expanded (not collapsed?)
              :aria-label    prefix
              :on-key-down   (fn [e]
                               (when (or (= (.-key e) "Enter") (= (.-key e) " "))
                                 (.preventDefault e)
                                 (handle-toggle-group e)))
              :on-click      handle-toggle-group}
        [:> i/icon* {:icon-id (if collapsed? i/arrow-right i/arrow-down)
                     :size    "s"
                     :class   (stl/css :color-group-arrow)}]
        [:span {:class (stl/css :color-group-name)} prefix]])

     (when-not collapsed?
       [:*
        (for [color direct-colors]
          [:> color-row-colorpicker*
           {:key        (dm/str (:ref-id color))
            :color-item color
            :on-click   on-color-click}])

        (for [[group-name sub-tree] subgroups]
          [:> color-group-list*
           {:key              group-name
            :groups           sub-tree
            :prefix           (cpn/merge-path-item-with-dot prefix group-name)
            :resolved-file-id resolved-file-id
            :on-color-click   on-color-click
            :open-groups      open-groups
            :on-toggle-group  on-toggle-group}])])]))

;; ---------------------------------------------------------------------------
;; Libraries panel
;; ---------------------------------------------------------------------------

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
         (fn [color]
           (when-not (= :recent selected)
             (st/emit! (ev/event
                        {::ev/name "use-library-color"
                         ::ev/origin "colorpicker"
                         :external-library (not= :file selected)})))
           (on-select-color state color)))

        on-toggle-group
        (mf/use-fn
         (fn [path]
           (swap! open-groups*
                  (fn [s]
                    (if (contains? s path)
                      (disj s path)
                      (conj s path))))))]

    ;; Load library colors when the selected library (or filter options) change.
    ;;
    ;; flat    current-colors*  — used for the grid view and the recent list view.
    ;; grouped grouped-colors*  — used for the library grouped list view.
    ;;
    ;; Library colors are fully converted with `library-color->color` here so
    ;; the render path never needs to do it.  `flat-colors` is materialised as
    ;; an eager vector so realisation does not leak into render time.
    ;; open-groups* is reset to #{} (all groups expanded) on every library switch.
    (mf/with-effect [selected recent-colors libraries file-id valid-color?]
      (let [resolved-file-id (if (= selected :file) file-id selected)]
        (reset! open-groups* #{})
        (if (= selected :recent)
          (let [colors (into []
                             (comp
                              (filter valid-color?)
                              (map-indexed (fn [index color]
                                             (let [color (if (map? color) color {:color color})]
                                               (vary-meta color assoc ::id (dm/str index)))))
                              (take-while some?))
                             (sort ctc/sort-colors (reverse recent-colors)))]
            (reset! current-colors* colors)
            (reset! grouped-colors* {}))

          (let [raw-colors (->> (dm/get-in libraries [resolved-file-id :data :colors])
                                (vals)
                                (filter valid-color?)
                                (sort-by :name))

                ;; Eager vector for the grid view — index-based ::id for keying.
                flat-colors (into []
                                  (map-indexed (fn [index color]
                                                 (-> (ctc/library-color->color color resolved-file-id)
                                                     (vary-meta assoc ::id (dm/str index)))))
                                  raw-colors)

                ;; Group tree with colors already converted — no conversions at render time.
                grouped (some-> (grp/group-assets raw-colors false)
                                (convert-grouped-colors resolved-file-id))]
            (reset! current-colors* flat-colors)
            (reset! grouped-colors* (or grouped {}))))))

    [:div {:class (stl/css :libraries)}
     [:div {:class (stl/css :select-wrapper)}

      [:> select* {:on-change        on-library-change
                   :options          options
                   :class            (stl/css :library-select)
                   :default-selected (or (d/name selected) "recent")}]

      [:> icon-button*
       {:variant    "ghost"
        :aria-label (tr "workspace.libraries.colors.show-color-palette")
        :on-click   toggle-palette
        :icon       i/swatches}]

      [:> icon-button*
       {:variant    "ghost"
        :aria-label (if (= :grid view-mode)
                      (tr "workspace.assets.list-view")
                      (tr "workspace.assets.grid-view"))
        :on-click   toggle-view-mode
        :icon       (if (= :grid view-mode)
                      i/view-as-list
                      i/view-as-icons)}]

      (when (= selected :file)
        [:> icon-button*
         {:variant    "ghost"
          :aria-label (tr "workspace.libraries.colors.add-library-color")
          :on-click   on-add-library-color
          :icon       i/add}])]

     (if (= view-mode :grid)
       [:div {:class     (stl/css :selected-colors)
              :role      "list"
              :aria-label (tr "workspace.assets.colors")}
        (for [color current-colors]
          [:div {:role "listitem"
                 :key  (-> color meta ::id)}
           [:> swatch* {:background color
                        :on-click   on-color-click
                        :size       "medium"}]])]

       [:div {:class      (stl/css :selected-colors-list)
              :role       "list"
              :aria-label (tr "workspace.assets.colors")}
        (if (= selected :recent)
          ;; Recent colors have no path/groups — render flat
          (for [color current-colors]
            [:> color-row-colorpicker*
             {:key        (-> color meta ::id)
              :color-item color
              :on-click   on-color-click}])

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
