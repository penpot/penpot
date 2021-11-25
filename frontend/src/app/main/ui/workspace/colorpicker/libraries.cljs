;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.colorpicker.libraries
  (:require
   [app.common.uuid :refer [uuid]]
   [app.main.data.workspace.colors :as dc]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.color-bullet :refer [color-bullet]]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [okulary.core :as l]
   [rumext.alpha :as mf]))

(def selected-palette-ref
  (-> (l/in [:workspace-local :selected-palette-colorpicker])
      (l/derived st/state)))

(mf/defc libraries
  [{:keys [on-select-color on-add-library-color disable-gradient disable-opacity]}]
  (let [selected-library       (or (mf/deref selected-palette-ref) :recent)
        current-library-colors (mf/use-state [])

        shared-libs      (mf/deref refs/workspace-libraries)
        file-colors      (mf/deref refs/workspace-file-colors)
        recent-colors    (mf/deref refs/workspace-recent-colors)

        parse-selected
        (fn [selected-str]
          (if (#{"recent" "file"} selected-str)
            (keyword selected-str)
            (uuid selected-str)))

        check-valid-color? (fn [color]
                             (and (or (not disable-gradient) (not (:gradient color)))
                                  (or (not disable-opacity) (= 1 (:opacity color)))))]

    ;; Load library colors when the select is changed
    (mf/use-effect
     (mf/deps selected-library)
     (fn []
       (let [mapped-colors
             (cond
               (= selected-library :recent)
               ;; The `map?` check is to keep backwards compatibility. We transform from string to map
               (map #(if (map? %) % (hash-map :color %)) (reverse (or recent-colors [])))

               (= selected-library :file)
               (vals file-colors)

               :else ;; Library UUID
               (->> (get-in shared-libs [selected-library :data :colors])
                    (vals)
                    (map #(merge % {:file-id selected-library}))))]

         (reset! current-library-colors (into [] (filter check-valid-color?) mapped-colors)))))

    ;; If the file colors change and the file option is selected updates the state
    (mf/use-effect
     (mf/deps file-colors)
     (fn [] (when (= selected-library :file)
              (let [colors (vals file-colors)]
                (reset! current-library-colors (into [] (filter check-valid-color?) colors))))))

    [:div.libraries
     [:select {:on-change (fn [e]
                            (when-let [val (parse-selected (dom/get-target-val e))]
                              (st/emit! (dc/change-palette-selected-colorpicker val))))
               :value (name selected-library)}
      [:option {:value "recent"} (tr "workspace.libraries.colors.recent-colors")]
      [:option {:value "file"} (tr "workspace.libraries.colors.file-library")]

      (for [[_ {:keys [name id]}] shared-libs]
        [:option {:key id
                  :value id} name])]

     [:div.selected-colors
      (when (= selected-library :file)
        [:div.color-bullet.button.plus-button {:style {:background-color "var(--color-white)"}
                                               :on-click on-add-library-color}
         i/plus])

      [:div.color-bullet.button {:style {:background-color "var(--color-white)"}
                                 :on-click #(st/emit! (dc/show-palette selected-library))}
       i/palette]

      (for [[idx color] (map-indexed vector @current-library-colors)]
        [:& color-bullet {:key (str "color-" idx)
                          :color color
                          :on-click #(on-select-color color)}])]]))
