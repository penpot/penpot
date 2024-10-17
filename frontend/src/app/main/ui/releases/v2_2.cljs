;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.releases.v2-2
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.ui.releases.common :as c]
   [rumext.v2 :as mf]))

(defmethod c/render-release-notes "2.2"
  [{:keys [slide klass finish version]}]
  (mf/html
   (case slide
     :start
     [:div {:class (stl/css-case :modal-overlay true)}
      [:div.animated {:class klass}
       [:div {:class (stl/css :modal-container)}
        [:img {:src "images/features/2.0-intro-image.png"
               :class (stl/css :start-image)
               :border "0"
               :alt "A graphic illustration with Penpot style"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "What's new in Penpot? "]

          [:div {:class (stl/css :version-tag)}
           (dm/str "Version " version)]]

         [:div {:class (stl/css :features-block)}
          [:p {:class (stl/css :feature-content)}
           "This Penpot 2.2 release focuses on internal changes that are laying out the ground for the upcoming plugin system and substantial performance improvements."]

          [:p  {:class (stl/css :feature-content)}
           "This version also adds full JSON API interoperability and the brand-new Penpot’s Storybook!"]

          [:p  {:class (stl/css :feature-content)}
           "Self-hosted Penpot installations will benefit from better file data storage and Penpot admins can now use the improved automatic snapshotting process when recovering old files."]

          [:p  {:class (stl/css :feature-content)}
           "Thanks again to our awesome community for their amazing contributions to this release!"]]

         [:div {:class (stl/css :navigation)}
          [:button {:class (stl/css :next-btn)
                    :on-click finish} "Let's go"]]]]]])))

