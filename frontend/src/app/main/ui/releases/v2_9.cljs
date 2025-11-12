;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.releases.v2-9
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.ui.releases.common :as c]
   [rumext.v2 :as mf]))

(defmethod c/render-release-notes "2.9"
  [{:keys [slide klass next finish navigate version]}]
  (mf/html
   (case slide
     :start
     [:div {:class (stl/css-case :modal-overlay true)}
      [:div.animated {:class klass}
       [:div {:class (stl/css :modal-container)}
        [:img {:src "images/features/2.9-slide-0.jpg"
               :class (stl/css :start-image)
               :border "0"
               :alt "Penpot 2.9 is here!"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "What’s new in Penpot?"]

          [:div {:class (stl/css :version-tag)}
           (dm/str "Version " version)]]

         [:div {:class (stl/css :features-block)}
          [:span {:class (stl/css :feature-title)}
           "Penpot 2.9 is out!"]

          [:p  {:class (stl/css :feature-content)}
           "We're keeping the momentum going with another exciting round of improvements and features!"]

          [:p  {:class (stl/css :feature-content)}
           "This release brings major progress in Design Token management (including our very first typography token!), smarter text overrides for components, and a rich collection of quality-of-life enhancements."]

          [:p  {:class (stl/css :feature-content)}
           "Let’s dive in!"]]

         [:div {:class (stl/css :navigation)}
          [:button {:class (stl/css :next-btn)
                    :on-click next} "Continue"]]]]]]

     0
     [:div {:class (stl/css-case :modal-overlay true)}
      [:div.animated {:class klass}
       [:div {:class (stl/css :modal-container)}
        [:img {:src "images/features/2.9-font-size.gif"
               :class (stl/css :start-image)
               :border "0"
               :alt "New typography token type"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "New typography token type"]]

         [:div {:class (stl/css :feature)}
          [:p {:class (stl/css :feature-content)}
           "You can now define and manage font size tokens right from the Design Tokens panel. This is just the first of many typography token types to come. Font family token is next!"]

          [:p {:class (stl/css :feature-content)}
           "And there’s more progress on Tokens, including support for importing multiple token files via .zip, and smarter token visibility, only showing the relevant tokens for each layer type."]]

         [:div {:class (stl/css :navigation)}
          [:& c/navigation-bullets
           {:slide slide
            :navigate navigate
            :total 2}]

          [:button {:on-click next
                    :class (stl/css :next-btn)} "Continue"]]]]]]

     1
     [:div {:class (stl/css-case :modal-overlay true)}
      [:div.animated {:class klass}
       [:div {:class (stl/css :modal-container)}
        [:img {:src "images/features/2.9-qol.gif"
               :class (stl/css :start-image)
               :border "0"
               :alt "Quality-of-life galore"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "Quality-of-life galore"]]
         [:div {:class (stl/css :feature)}

          [:p {:class (stl/css :feature-content)}
           "This release packs dozens of small yet impactful usability improvements, including enhanced UX writing (thanks to community contributions!), a new visual indicator for comments directly in the design space, a reorganized dashboard sidebar, improved text resizing behavior, and much more."]

          [:p {:class (stl/css :feature-content)}
           "As always, we've squashed plenty of bugs and made underlying performance improvements to keep everything running smoothly."]]

         [:div {:class (stl/css :navigation)}

          [:& c/navigation-bullets
           {:slide slide
            :navigate navigate
            :total 2}]

          [:button {:on-click finish
                    :class (stl/css :next-btn)} "Let's go"]]]]]])))

