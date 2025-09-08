;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.text-palette-ctx-menu
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.refs :as refs]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.icons :as deprecated-icon]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

(mf/defc text-palette-ctx-menu
  [{:keys [show-menu? close-menu on-select-palette selected]}]
  (let [typographies (mf/deref refs/workspace-file-typography)
        libraries    (mf/deref refs/libraries)]
    [:& dropdown {:show show-menu?
                  :on-close close-menu}
     [:ul {:class (stl/css :text-context-menu)}
      (for [[idx cur-library] (map-indexed vector (vals libraries))]
        (let [typographies (-> cur-library (get-in [:data :typographies]) vals)]
          [:li
           {:class (stl/css-case :palette-library true
                                 :selected (= selected (:id cur-library)))
            :key (str "library-" idx)
            :on-click #(on-select-palette cur-library)}
           [:div
            {:class (stl/css :library-name)}
            [:span {:class (stl/css :lib-name)}
             (dm/str (:name cur-library))]
            [:span {:class (stl/css :lib-num)}
             (dm/str "(" (count typographies) ")")]]

           (when (= selected (:id cur-library))
             [:span {:class (stl/css :icon-wrapper)}
              deprecated-icon/tick])]))

      [:li
       {:class (stl/css-case :file-library true
                             :selected (= selected :file))
        :on-click #(on-select-palette :file)}

       [:div {:class (stl/css :library-name)}
        [:span {:class (stl/css :lib-name)}
         (tr "workspace.libraries.colors.file-library")]
        [:span {:class (stl/css :lib-num)}
         (dm/str "(" (count typographies) ")")]]
       (when (= selected :file)
         [:span {:class (stl/css :icon-wrapper)}
          deprecated-icon/tick])]]]))
