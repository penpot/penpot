;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.color-palette-ctx-menu
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.refs :as refs]
   [app.main.ui.components.color-bullet :as cb]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.icons :as i]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

(mf/defc color-palette-ctx-menu
  [{:keys [show-menu? close-menu on-select-palette selected]}]
  (let [recent-colors (mf/deref refs/workspace-recent-colors)
        file-colors   (mf/deref refs/workspace-file-colors)
        shared-libs   (mf/deref refs/workspace-libraries)]
    [:& dropdown {:show show-menu?
                  :on-close close-menu}
     [:ul {:class (stl/css :palette-menu)}
      (for [{:keys [data id] :as library} (vals shared-libs)]
        (let [colors (-> data :colors vals)]
          [:li  {:class (stl/css-case :palette-library true
                                      :selected (= selected id))
                 :key (dm/str "library-" id)
                 :on-click on-select-palette
                 :data-palette (dm/str id)}
           [:div {:class (stl/css :option-wrapper)}
            [:div {:class (stl/css :library-name)}
             [:div {:class (stl/css :lib-name-wrapper)}
              [:span {:class (stl/css :lib-name)}
               (dm/str (:name library))]
              [:span {:class (stl/css :lib-num)}
               (dm/str "(" (count colors) ")")]]
             (when (= selected id)
               [:span {:class (stl/css :icon-wrapper)}
                i/tick])]
            [:div {:class (stl/css :color-sample)
                   :style #js {"--bullet-size" "20px"}}
             (for [[i {:keys [color id gradient]}] (map-indexed vector (take 7 colors))]
               [:& cb/color-bullet {:key (dm/str "color-" i)
                                    :mini? true
                                    :color {:color color :id id :gradient gradient}}])]]]))

      [:li {:class (stl/css-case :file-library true
                                 :selected (= selected :file))
            :on-click on-select-palette
            :data-palette "file"}

       [:div {:class (stl/css :option-wrapper)}
        [:div {:class (stl/css :library-name)}

         [:div {:class (stl/css :lib-name-wrapper)}
          [:span {:class (stl/css :lib-name)}
           (dm/str (tr "workspace.libraries.colors.file-library"))]
          [:span {:class (stl/css :lib-num)}
           (dm/str "(" (count file-colors) ")")]]

         (when (= selected :file)
           [:span {:class (stl/css :icon-wrapper)}
            i/tick])]
        [:div {:class (stl/css :color-sample)
               :style #js {"--bullet-size" "20px"}}
         (for [[i color] (map-indexed vector (take 7 (vals file-colors)))]
           [:& cb/color-bullet {:key (dm/str "color-" i)
                                :mini? true
                                :color color}])]]]

      [:li {:class (stl/css :recent-colors true
                            :selected (= selected :recent))
            :on-click on-select-palette
            :data-palette "recent"}
       [:div {:class (stl/css :option-wrapper)}
        [:div {:class (stl/css :library-name)}
         [:div {:class (stl/css :lib-name-wrapper)}
          [:span {:class (stl/css :lib-name)}
           (dm/str (tr "workspace.libraries.colors.recent-colors"))]
          [:span {:class (stl/css :lib-num)}
           (dm/str "(" (count recent-colors) ")")]]

         (when (= selected :recent)
           [:span {:class (stl/css :icon-wrapper)}
            i/tick])]
        [:div {:class (stl/css :color-sample)
               :style #js {"--bullet-size" "20px"}}
         (for [[idx color] (map-indexed vector (take 7 (reverse recent-colors)))]
           [:& cb/color-bullet {:key (str "color-" idx)
                                :mini? true
                                :color color}])]]]]]))
