;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.colorpicker.libraries
  (:require
   [app.common.data.macros :as dm]
   [app.main.data.workspace.colors :as dc]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.color-bullet :refer [color-bullet]]
   [app.main.ui.hooks :as h]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.alpha :as mf]))

(mf/defc libraries
  [{:keys [on-select-color on-add-library-color disable-gradient disable-opacity]}]
  (let [selected         (h/use-shared-state dc/colorpicker-selected-broadcast-key :recent)
        current-colors   (mf/use-state [])

        shared-libs      (mf/deref refs/workspace-libraries)
        file-colors      (mf/deref refs/workspace-file-colors)
        recent-colors    (mf/deref refs/workspace-recent-colors)

        on-library-change
        (mf/use-fn
         (fn [event]
           (let [val (dom/get-target-val event)]
             (reset! selected
                     (if (or (= val "recent")
                             (= val "file"))
                       (keyword val)
                       (parse-uuid val))))))

        check-valid-color?
        (fn [color]
          (and (or (not disable-gradient) (not (:gradient color)))
               (or (not disable-opacity) (= 1 (:opacity color)))))]

    ;; Load library colors when the select is changed
    (mf/with-effect [@selected recent-colors file-colors]
      (let [colors (cond
                     (= @selected :recent)
                     ;; The `map?` check is to keep backwards compatibility. We transform from string to map
                     (map #(if (map? %) % {:color %}) (reverse (or recent-colors [])))

                     (= @selected :file)
                     (vals file-colors)

                     :else ;; Library UUID
                     (as-> @selected file-id
                       (->> (get-in shared-libs [file-id :data :colors])
                            (vals)
                            (map #(assoc % :file-id file-id)))))]

        (reset! current-colors (into [] (filter check-valid-color?) colors))))

    ;; If the file colors change and the file option is selected updates the state
    (mf/with-effect [file-colors]
      (when (= @selected :file)
        (let [colors (vals file-colors)]
          (reset! current-colors (into [] (filter check-valid-color?) colors)))))

    [:div.libraries
     [:select {:on-change on-library-change :value (name @selected)}
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
                                 :on-click #(st/emit! (dc/show-palette @selected))}
       i/palette]

      (for [[idx color] (map-indexed vector @current-colors)]
        [:& color-bullet
         {:key (dm/str "color-" idx)
          :color color
          :on-click on-select-color}])]]))
