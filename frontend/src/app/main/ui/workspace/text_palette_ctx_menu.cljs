;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.text-palette-ctx-menu
  (:require-macros [app.main.style :refer [css]])
  (:require
   [app.common.data.macros :as dm]
   [app.main.refs :as refs]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))


(mf/defc text-palette-ctx-menu
  [{:keys [show-menu? close-menu on-select-palette selected]}]
  (let [file-typographies (mf/deref refs/workspace-file-typography)
        shared-libs   (mf/deref refs/workspace-libraries)]
    [:& dropdown {:show show-menu?
                  :on-close close-menu}
     [:ul {:class (dom/classnames (css :workspace-context-menu) true)}
      (for [[idx cur-library] (map-indexed vector (vals shared-libs))]
        (let [typographies (-> cur-library (get-in [:data :typographies]) vals)]
          [:li
           {:class (dom/classnames (css :palette-library) true
                                   (css :selected) (= selected (:id cur-library)))
            :key (str "library-" idx)
            :on-click #(on-select-palette cur-library)}
           [:div
            {:class (dom/classnames (css :library-name) true)}
            [:span {:class (css :lib-name)}
             (dm/str (:name cur-library))]
            [:span {:class (css :lib-num)}
             (dm/str "(" (count typographies) ")")]]

           (when (= selected (:id cur-library))
             [:span {:class (dom/classnames (css :icon-wrapper) true)}
              i/tick-refactor])]))

      [:li
       {:class (dom/classnames (css :file-library) true
                               (css :selected) (= selected :file))
        :on-click #(on-select-palette :file)}

       [:div {:class (dom/classnames (css :library-name) true)}
        [:span {:class (css :lib-name)}
         (tr "workspace.libraries.colors.file-library")]
        [:span {:class (css :lib-num)}
         (dm/str "(" (count file-typographies) ")")]]
       (when (= selected :file)
         [:span {:class (dom/classnames (css :icon-wrapper) true)}
          i/tick-refactor])]]]))
