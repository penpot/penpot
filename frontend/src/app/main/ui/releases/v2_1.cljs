;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.releases.v2-1
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.ui.releases.common :as c]
   [rumext.v2 :as mf]))

(defmethod c/render-release-notes "2.1"
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
           "Penpot 2.1 brings improvements to the authentication system, path editing, real-time persistence, and comments system among other enhancements. We’ve improved the stability of the platform by fixing a bunch of bugs, a lot of them raised by our amazing community <3."]

          [:p  {:class (stl/css :feature-content)}
           "This minor release comes shortly after our amazing Penpot 2.0 and it shows the way to long-expected capabilities like the incoming new plugin system!"]

          [:p  {:class (stl/css :feature-content)}
           " Ready to dive in? Let 's get started!"]]

         [:div {:class (stl/css :navigation)}
          [:button {:class (stl/css :next-btn)
                    :on-click finish} "Let's go"]]]]]])))

