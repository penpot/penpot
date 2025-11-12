;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.releases.v2-8
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.ui.releases.common :as c]
   [rumext.v2 :as mf]))

(defmethod c/render-release-notes "2.8"
  [{:keys [slide klass next finish navigate version]}]
  (mf/html
   (case slide
     :start
     [:div {:class (stl/css-case :modal-overlay true)}
      [:div.animated {:class klass}
       [:div {:class (stl/css :modal-container)}
        [:img {:src "images/features/2.8-slide-0.jpg"
               :class (stl/css :start-image)
               :border "0"
               :alt "Penpot 2.8 is here!"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "What’s new in Penpot?"]

          [:div {:class (stl/css :version-tag)}
           (dm/str "Version " version)]]

         [:div {:class (stl/css :features-block)}
          [:span {:class (stl/css :feature-title)}
           "Penpot 2.8 is out!"]

          [:p  {:class (stl/css :feature-content)}
           "Keeping the momentum from our last releases, we're moving forward with a fresh batch of features and improvements."]

          [:p  {:class (stl/css :feature-content)}
           "This update brings significant user experience optimizations, new capabilities for Design Token management, and an important performance enhancement, in addition to the usual bug fixes and general optimizations."]

          [:p  {:class (stl/css :feature-content)}
           "Let’s dive in!"]]

         [:div {:class (stl/css :navigation)}
          [:button {:class (stl/css :next-btn)
                    :on-click next} "Continue"]]]]]]

     0
     [:div {:class (stl/css-case :modal-overlay true)}
      [:div.animated {:class klass}
       [:div {:class (stl/css :modal-container)}
        [:img {:src "images/features/2.8-selection.gif"
               :class (stl/css :start-image)
               :border "0"
               :alt "Quality-of-life improvements"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "Quality-of-life improvements"]]

         [:div {:class (stl/css :feature)}
          [:p {:class (stl/css :feature-content)}
           "The devil is in the details, and we've added many more improvements than we can list in this introduction, so we’ll list them here:"]

          [:p {:class (stl/css :feature-content)}
           "- You can now deselect layers with Ctrl+Shift+Drag."]
          [:p {:class (stl/css :feature-content)}
           "- Synchronize your color theme (light, dark) with your operating system theme."]
          [:p {:class (stl/css :feature-content)}
           "- Copy objects directly as SVG from the design workspace using the contextual menu."]
          [:p {:class (stl/css :feature-content)}
           "- Your choice of ruler visibility settings now persists across files and reloads."]
          [:p {:class (stl/css :feature-content)}
           "- We have introduced a new look and feel for tooltips."]
          [:p {:class (stl/css :feature-content)}
           "- Our fonts catalog has been updated, highlighting fonts like Atkinson Hyperlegible, developed specifically to increase legibility for readers with low vision."]
          [:p {:class (stl/css :feature-content)}
           "- And we’ve a new language! Hi Serbians!"]]

         [:div {:class (stl/css :navigation)}
          [:& c/navigation-bullets
           {:slide slide
            :navigate navigate
            :total 4}]

          [:button {:on-click next
                    :class (stl/css :next-btn)} "Continue"]]]]]]

     1
     [:div {:class (stl/css-case :modal-overlay true)}
      [:div.animated {:class klass}
       [:div {:class (stl/css :modal-container)}
        [:img {:src "images/features/2.8-json-multi.gif"
               :class (stl/css :start-image)
               :border "0"
               :alt "Multi-file tokens import and export"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "Multi-file tokens import and export"]]

         [:div {:class (stl/css :feature)}
          [:p {:class (stl/css :feature-content)}
           "Continuing the development of our Design Tokens system, we keep launching new functionalities that will improve your workflow."]

          [:p {:class (stl/css :feature-content)}
           "It is now possible to perform multi-file token import. This is perfect for handling more complex setups like separate JSON files for themes or metadata. The same goes for exporting. Now you can export tokens choosing between single or multi-file (check your team preference!), being able to preview both options before you decide."]

          [:p {:class (stl/css :feature-content)}
           "This is just one more step in the evolution of Design Tokens in Penpot. And there's more to come: typography tokens are already in the works!"]]

         [:div {:class (stl/css :navigation)}
          [:& c/navigation-bullets
           {:slide slide
            :navigate navigate
            :total 4}]

          [:button {:on-click next
                    :class (stl/css :next-btn)} "Continue"]]]]]]

     2
     [:div {:class (stl/css-case :modal-overlay true)}
      [:div.animated {:class klass}
       [:div {:class (stl/css :modal-container)}
        [:img {:src "images/features/2.8-ia-help.gif"
               :class (stl/css :start-image)
               :border "0"
               :alt "Better onboarding experience"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "Better onboarding experience"]]

         [:div {:class (stl/css :feature)}
          [:p {:class (stl/css :feature-content)}
           "We know Penpot works perfectly for solo projects, but it’s always more fun and productive to work as a team. That's why we have made significant optimizations to the onboarding process to make your team mates getting started easier and faster."]

          [:p {:class (stl/css :feature-content)}
           "- We have reduced the number of onboarding steps so new users can start designing sooner. Only essential data will be collected to help you set up your profile more efficiently."]

          [:p {:class (stl/css :feature-content)}
           "- We have integrated AI-powered help, which is trained on Penpot documentation, directly into the design workspace. Get assistance without switching context, so you can stay in the flow."]]

         [:div {:class (stl/css :navigation)}
          [:& c/navigation-bullets
           {:slide slide
            :navigate navigate
            :total 4}]

          [:button {:on-click next
                    :class (stl/css :next-btn)} "Continue"]]]]]]

     3
     [:div {:class (stl/css-case :modal-overlay true)}
      [:div.animated {:class klass}
       [:div {:class (stl/css :modal-container)}
        [:img {:src "images/features/2.8-svg-opt.gif"
               :class (stl/css :start-image)
               :border "0"
               :alt "Performance improvements related to SVG paths"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "Performance improvements related to SVG paths"]]
         [:div {:class (stl/css :feature)}

          [:p {:class (stl/css :feature-content)}
           "We are putting a special focus on performance. This time, we've made improvements around handling paths, which typically make up icons and illustrations."]

          [:p {:class (stl/css :feature-content)}
           "We have carried out a refactoring of how Penpot deals with path data. This translates into perceptible performance improvements in SVG-heavily loaded files. This means that, in files with a significant presence of SVG icons and illustrations you can expect:"]
          [:p {:class (stl/css :feature-content)}
           "- Faster import/export."]
          [:p {:class (stl/css :feature-content)}
           "- A more fluid user experience."]
          [:p {:class (stl/css :feature-content)}
           "This is one of many performance improvements to come, including a new rendering engine that is already under development (this will be huge, please be patient!)."]]

         [:div {:class (stl/css :navigation)}

          [:& c/navigation-bullets
           {:slide slide
            :navigate navigate
            :total 4}]

          [:button {:on-click finish
                    :class (stl/css :next-btn)} "Let's go"]]]]]])))

