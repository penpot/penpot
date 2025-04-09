;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.colorpicker.libraries
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.colors :as c]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.types.color :as ctc]
   [app.main.data.event :as ev]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.colors :as mdc]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.color-bullet :as cb]
   [app.main.ui.components.select :refer [select]]
   [app.main.ui.context :as ctx]
   [app.main.ui.hooks :as h]
   [app.main.ui.hooks.resize :as r]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [potok.v2.core :as ptk]
   [rumext.v2 :as mf]))

(mf/defc libraries
  [{:keys [state on-select-color on-add-library-color disable-gradient disable-opacity disable-image]}]
  (let [selected*        (h/use-shared-state mdc/colorpicker-selected-broadcast-key :recent)
        selected         (deref selected*)

        file-id          (mf/use-ctx ctx/current-file-id)

        current-colors*  (mf/use-state [])
        current-colors   (deref current-colors*)

        libraries        (mf/deref refs/libraries)
        recent-colors    (mf/deref refs/recent-colors)
        recent-colors    (mf/with-memo [recent-colors]
                           (filterv ctc/valid-color? recent-colors))

        library-options
        (mf/with-memo []
          [{:value "recent" :label  (tr "workspace.libraries.colors.recent-colors")}
           {:value "file" :label (tr "workspace.libraries.colors.file-library")}])

        options
        (mf/with-memo [library-options libraries file-id]
          (into library-options
                (comp
                 (map val)
                 (map (fn [lib] {:value (d/name (:id lib)) :label (:name lib)})))
                (dissoc libraries file-id)))

        on-library-change
        (mf/use-fn
         (fn [event]
           (reset! selected*
                   (if (or (= event "recent")
                           (= event "file"))
                     (keyword event)
                     (parse-uuid event)))))

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

        on-color-click
        (mf/use-fn
         (mf/deps state selected on-select-color)
         (fn [event]
           (when-not (= :recent selected)
             (st/emit! (ptk/event
                        ::ev/event
                        {::ev/name "use-library-color"
                         ::ev/origin "colorpicker"
                         :external-library (not= :file selected)})))
           (on-select-color state event)))]

    ;; Load library colors when the select is changed
    (mf/with-effect [selected recent-colors libraries file-id valid-color?]
      (let [file-id (if (= selected :file)
                      file-id
                      selected)

            colors (if (= selected :recent)
                     ;; NOTE: The `map?` check is to keep backwards
                     ;; compatibility We transform from string to map
                     (->> (reverse recent-colors)
                          (filter valid-color?)
                          (map-indexed (fn [index color]
                                         (let [color (if (map? color) color {:color color})]
                                           (assoc color ::id (dm/str index)))))
                          (sort c/sort-colors))
                     (->> (dm/get-in libraries [file-id :data :colors])
                          (vals)
                          (filter valid-color?)
                          (map-indexed (fn [index color]
                                         (-> color
                                             (assoc :file-id file-id)
                                             (assoc ::id (dm/str index)))))
                          (sort-by :name)))]

        (reset! current-colors* colors)))

    [:div {:class (stl/css :libraries)}
     [:div {:class (stl/css :select-wrapper)}
      [:& select
       {:class (stl/css :shadow-type-select)
        :data-direction "up"
        :default-value (or (d/name selected) "recent")
        :options options
        :on-change on-library-change}]]

     [:div {:class (stl/css :selected-colors)}
      (when (= selected :file)
        [:button {:class (stl/css :add-color-btn)
                  :on-click on-add-library-color}
         i/add])

      [:button {:class (stl/css :palette-btn)
                :on-click toggle-palette}
       i/swatches]

      (for [color current-colors]
        [:& cb/color-bullet
         {:key (dm/str "color-" (::id color))
          :color color
          :on-click on-color-click}])]]))
