;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.workspace.colorpicker.libraries
  (:require
   [rumext.alpha :as mf]
   [okulary.core :as l]
   [cuerdas.core :as str]
   [app.common.geom.point :as gpt]
   [app.common.math :as math]
   [app.common.uuid :refer [uuid]]
   [app.util.dom :as dom]
   [app.util.color :as uc]
   [app.util.object :as obj]
   [app.main.store :as st]
   [app.main.refs :as refs]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.colors :as dc]
   [app.main.data.modal :as modal]
   [app.main.ui.icons :as i]
   [app.util.i18n :as i18n :refer [t]]
   [app.main.ui.components.color-bullet :refer [color-bullet]]
   [app.main.ui.workspace.colorpicker.gradients :refer [gradients]]
   [app.main.ui.workspace.colorpicker.harmony :refer [harmony-selector]]
   [app.main.ui.workspace.colorpicker.hsva :refer [hsva-selector]]
   [app.main.ui.workspace.colorpicker.ramp :refer [ramp-selector]]
   [app.main.ui.workspace.colorpicker.color-inputs :refer [color-inputs]]))

(mf/defc libraries [{:keys [current-color on-select-color on-add-library-color
                            disable-gradient disable-opacity]}]
  (let [selected-library       (mf/use-state "recent")
        current-library-colors (mf/use-state [])

        shared-libs      (mf/deref refs/workspace-libraries)
        file-colors      (mf/deref refs/workspace-file-colors)
        recent-colors    (mf/deref refs/workspace-recent-colors)
        locale           (mf/deref i18n/locale)

        parse-selected
        (fn [selected]
          (if (#{"recent" "file"} selected)
            (keyword selected)
            (uuid selected)) )

        check-valid-color? (fn [color]
                             (and (or (not disable-gradient) (not (:gradient color)))
                                  (or (not disable-opacity) (= 1 (:opacity color)))))]

    ;; Load library colors when the select is changed
    (mf/use-effect
     (mf/deps @selected-library)
     (fn []
       (let [mapped-colors
             (cond
               (= @selected-library "recent")
               ;; The `map?` check is to keep backwards compatibility. We transform from string to map
               (map #(if (map? %) % (hash-map :color %)) (reverse (or recent-colors [])))

               (= @selected-library "file")
               (vals file-colors)

               :else ;; Library UUID
               (map #(merge {:file-id (uuid @selected-library)})
                    (vals (get-in shared-libs [(uuid @selected-library) :data :colors]))))]
         (reset! current-library-colors (into [] (filter check-valid-color?) mapped-colors)))))

    ;; If the file colors change and the file option is selected updates the state
    (mf/use-effect
     (mf/deps file-colors)
     (fn [] (when (= @selected-library "file")
              (let [colors (vals file-colors)]
                (reset! current-library-colors (into [] (filter check-valid-color?) colors))))))


    [:div.libraries
     [:select {:on-change (fn [e]
                            (when-let [val (dom/get-target-val e)]
                              (reset! selected-library val)))
               :value @selected-library}
      [:option {:value "recent"} (t locale "workspace.libraries.colors.recent-colors")]
      [:option {:value "file"} (t locale "workspace.libraries.colors.file-library")]

      (for [[_ {:keys [name id]}] shared-libs]
        [:option {:key id
                  :value id} name])]

     [:div.selected-colors
      (when (= "file" @selected-library)
        [:div.color-bullet.button.plus-button {:style {:background-color "white"}
                                               :on-click on-add-library-color}
         i/plus])

      [:div.color-bullet.button {:style {:background-color "white"}
                                 :on-click #(st/emit! (dc/show-palette (parse-selected @selected-library)))}
       i/palette]

      (for [[idx color] (map-indexed vector @current-library-colors)]
        [:& color-bullet {:key (str "color-" idx)
                          :color color
                          :on-click #(on-select-color color)}])]]))
