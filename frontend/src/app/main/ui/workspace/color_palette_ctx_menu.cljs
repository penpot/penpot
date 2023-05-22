;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.color-palette-ctx-menu
  (:require-macros [app.main.style :refer [css]])
  (:require
   [app.common.data.macros :as dm]
   [app.main.refs :as refs]
   [app.main.ui.components.color-bullet-new :as cb]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(mf/defc color-palette-ctx-menu
  [{:keys [show-menu? close-menu on-select-palette selected]}]
  (let [recent-colors (mf/deref refs/workspace-recent-colors)
        file-colors   (mf/deref refs/workspace-file-colors)
        shared-libs   (mf/deref refs/workspace-libraries)]
    [:& dropdown {:show show-menu?
                  :on-close close-menu}
     [:ul {:class (dom/classnames (css :palette-menu) true)}
      (for [{:keys [data id] :as library} (vals shared-libs)]
        (let [colors (-> data :colors vals)]
          [:li
           {:class (dom/classnames (css :palette-library) true
                                   (css :selected) (= selected id))
            :key (dm/str "library-" id)
            :on-click on-select-palette
            :data-palette (dm/str id)}
           [:div {:class (dom/classnames (css :option-wrapper) true)}
            [:div {:class (dom/classnames (css :library-name) true)}
             (str (:name library) " " (str/ffmt "(%)" (count colors)))
             (when (= selected id)
               [:span {:class (dom/classnames (css :icon-wrapper) true)}
                i/tick-refactor])]
            [:div {:class (dom/classnames (css :color-sample) true)
                   :style #js {"--bullet-size" "20px"}}
             (for [[i {:keys [color id gradient]}] (map-indexed vector (take 7 colors))]
               [:& cb/color-bullet {:key (dm/str "color-" i)
                                    :mini? true
                                    :color {:color color :id id :gradient gradient}}])]]]))

      [:li {:class (dom/classnames (css :file-library) true
                                   (css :selected) (= selected :file))
            :on-click on-select-palette
            :data-palette "file"}

       [:div {:class (dom/classnames (css :option-wrapper) true)}
        [:div {:class (dom/classnames (css :library-name) true)}
         (dm/str
          (tr "workspace.libraries.colors.file-library")
          (str/ffmt " (%)" (count file-colors)))

         (when (= selected :file)
           [:span {:class (dom/classnames  (css :icon-wrapper) true)}
            i/tick-refactor])]
        [:div {:class (dom/classnames (css :color-sample) true)
               :style #js {"--bullet-size" "20px"}}
         (for [[i color] (map-indexed vector (take 7 (vals file-colors)))]
           [:& cb/color-bullet {:key (dm/str "color-" i)
                                :mini? true
                                :color color}])]]]

      [:li {:class (dom/classnames (css :recent-colors) true
                                   (css :selected) (= selected :recent))
            :on-click on-select-palette
            :data-palette "recent"}
       [:div {:class (dom/classnames (css :option-wrapper) true)}
        [:div {:class (dom/classnames (css :library-name) true)}
         (str (tr "workspace.libraries.colors.recent-colors")
              (str/format " (%s)" (count recent-colors)))
         (when (= selected :recent)
           [:span {:class (dom/classnames  (css :icon-wrapper) true)}
            i/tick-refactor])]
        [:div {:class (dom/classnames (css :color-sample) true)
               :style #js {"--bullet-size" "20px"}}
         (for [[idx color] (map-indexed vector (take 7 (reverse recent-colors)))]
           [:& cb/color-bullet {:key (str "color-" idx)
                                :mini? true
                                :color color}])]]]]]))
