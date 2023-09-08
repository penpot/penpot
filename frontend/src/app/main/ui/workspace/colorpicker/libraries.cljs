;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.colorpicker.libraries
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.main.data.events :as ev]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.colors :as mdc]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.color-bullet :refer [color-bullet]]
   [app.main.ui.components.color-bullet-new :as cb]
   [app.main.ui.components.select :refer [select]]
   [app.main.ui.context :as ctx]
   [app.main.ui.hooks :as h]
   [app.main.ui.hooks.resize :as r]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.timers :as ts]
   [rumext.v2 :as mf]))

(mf/defc libraries
  [{:keys [state on-select-color on-add-library-color disable-gradient disable-opacity  disable-image]}]
  (let [new-css-system   (mf/use-ctx ctx/new-css-system)
        selected         (h/use-shared-state mdc/colorpicker-selected-broadcast-key :recent)
        current-colors   (mf/use-state [])

        shared-libs      (mf/deref refs/workspace-libraries)
        file-colors      (mf/deref refs/workspace-file-colors)
        recent-colors    (mf/deref refs/workspace-recent-colors)
        recent-colors    (h/use-equal-memo  (filter #(or (:gradient %) (:color %) (:image %)) recent-colors))

        on-library-change
        (mf/use-fn
         (fn [event]
           (let [val (if new-css-system
                       event
                       (dom/get-target-val event))]
             (reset! selected
                     (if (or (= val "recent")
                             (= val "file"))
                       (keyword val)
                       (parse-uuid val))))))

        check-valid-color?
        (fn [color]
          (and (or (not disable-gradient) (not (:gradient color)))
               (or (not disable-opacity) (= 1 (:opacity color)))
               (or (not disable-image) (not (:image color)))))

        toggle-palette
        (mf/use-fn
         (fn []
           (r/set-resize-type! :bottom)
           (dom/add-class!  (dom/get-element-by-class "color-palette") "fade-out-down")
           (ts/schedule 300 #(st/emit! (dw/remove-layout-flag :textpalette)
                                       (-> (dw/toggle-layout-flag :colorpalette)
                                           (vary-meta assoc ::ev/origin "workspace-colorpicker"))))))

        shared-libs-options (mapv (fn[lib] {:value (d/name (:id lib)) :label (:name lib)} ) (vals shared-libs))


        library-options [{:value "recent" :label  (tr "workspace.libraries.colors.recent-colors")}
                         {:value "file" :label (tr "workspace.libraries.colors.file-library")}]

        options (concat library-options shared-libs-options)

        on-color-click
        (mf/use-fn
         (mf/deps state)
         (fn[event]
           (on-select-color state event)))]

    ;; Load library colors when the select is changed
    (mf/with-effect [@selected recent-colors file-colors]
      (let [colors (cond
                     (= @selected :recent)
                     ;; The `map?` check is to keep backwards compatibility. We transform from string to map
                     (map #(if (map? %) % {:color %}) (reverse (or recent-colors [])))

                     (= @selected :file)
                     (->> (vals file-colors) (sort-by :name))

                     :else ;; Library UUID
                     (as-> @selected file-id
                       (->> (get-in shared-libs [file-id :data :colors])
                            (vals)
                            (sort-by :name)
                            (map #(assoc % :file-id file-id)))))]

        (reset! current-colors (into [] (filter check-valid-color?) colors))))

    ;; If the file colors change and the file option is selected updates the state
    (mf/with-effect [file-colors]
      (when (= @selected :file)
        (let [colors (vals file-colors)]
          (reset! current-colors (into [] (filter check-valid-color?) colors)))))

    (if new-css-system
      [:div {:class (stl/css :libraries)}
       [:div {:class (stl/css :select-wrapper)}
        [:& select
         {:class (stl/css :shadow-type-select)
          :default-value (or (name @selected) "recent")
          :options options
          :on-change on-library-change}]]

       [:div {:class (stl/css :selected-colors)}
        (when (= @selected :file)
          [:button {:class (stl/css :add-color-btn)
                    :on-click on-add-library-color}
           i/add-refactor])

        [:button {:class (stl/css :palette-btn)
                  :on-click toggle-palette}
         i/swatches-refactor]

        (for [[idx color] (map-indexed vector @current-colors)]
          [:& cb/color-bullet
           {:key (dm/str "color-" idx)
            :color color
            :on-click on-color-click}])]]


      [:div.libraries
       [:select {:data-mousetrap-dont-stop true ;; makes mousetrap to not stop at this element
                 :on-change on-library-change
                 :value (name @selected)}
        [:option {:value "recent"} (tr "workspace.libraries.colors.recent-colors")]
        [:option {:value "file"} (tr "workspace.libraries.colors.file-library")]

        (for [[_ {:keys [name id]}] shared-libs]
          [:option {:key id :value id} name])]

       [:div.selected-colors
        (when (= @selected :file)
          [:div.color-bullet.button.plus-button {:style {:background-color "var(--color-white)"}
                                                 :on-click on-add-library-color}
           i/plus])

        [:div.color-bullet.button {:style {:background-color "var(--color-white)"}
                                   :on-click (fn []
                                               (r/set-resize-type! :bottom)
                                               (dom/add-class!  (dom/get-element-by-class "color-palette") "fade-out-down")
                                               (ts/schedule 300 #(st/emit! (dw/remove-layout-flag :textpalette)
                                                                           (-> (dw/toggle-layout-flag :colorpalette)
                                                                               (vary-meta assoc ::ev/origin "workspace-colorpicker")))))}
         i/palette]

        (for [[idx color] (map-indexed vector @current-colors)]
          [:& color-bullet
           {:key (dm/str "color-" idx)
            :color color
            :on-click on-color-click}])]])))
