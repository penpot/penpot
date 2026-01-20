;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.releases.v2-13
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.ui.releases.common :as c]
   [rumext.v2 :as mf]))

(defmethod c/render-release-notes "2.13"
  [{:keys [slide klass next finish navigate version]}]
  (mf/html
   (case slide
     :start
     [:div {:class (stl/css-case :modal-overlay true)}
      [:div.animated {:class klass}
       [:div {:class (stl/css :modal-container)}
        [:img {:src "images/features/2.13-slide-0.jpg"
               :class (stl/css :start-image)
               :border "0"
               :alt "Penpot 2.13 is here!"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "What’s new in Penpot?"]

          [:div {:class (stl/css :version-tag)}
           (dm/str "Version " version)]]

         [:div {:class (stl/css :features-block)}
          [:span {:class (stl/css :feature-title)}
           "The first release of the year, and we’re just getting started 🚀"]

          [:p  {:class (stl/css :feature-content)}
           "This is our first release of the year, and it sets the tone for what’s coming next. We’re kicking off an exciting year where we’ll take Penpot to a whole new level, with improved performance, stronger design system foundations, long-requested features, and new capabilities that unlock better workflows for teams."]

          [:p  {:class (stl/css :feature-content)}
           "This release brings two highlights the community has been asking for, along with solid improvements under the hood to keep everything fast and smooth."]

          [:p  {:class (stl/css :feature-content)}
           "Let’s dive in!"]]

         [:div {:class (stl/css :navigation)}
          [:button {:class (stl/css :next-btn)
                    :on-click next} "Continue"]]]]]]

     0
     [:div {:class (stl/css-case :modal-overlay true)}
      [:div.animated {:class klass}
       [:div {:class (stl/css :modal-container)}
        [:img {:src "images/features/2.13-trash.gif"
               :class (stl/css :start-image)
               :border "0"
               :alt "The Trash"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "The Trash"]]

         [:div {:class (stl/css :feature)}
          [:p {:class (stl/css :feature-content)}
           "Deleting a file no longer means it’s gone forever. Introducing The Trash, a dedicated space in the dashboard where deleted files and projects live before being permanently removed."]

          [:p {:class (stl/css :feature-content)}
           "From here, you can recover content deleted by mistake or clean things up for good when you’re sure you don’t need them anymore. The Trash works for both files and projects, and items are automatically removed after a period of time depending on your Penpot plan."]

          [:p {:class (stl/css :feature-content)}
           "Highly requested, long overdue, and now officially here."]]

         [:div {:class (stl/css :navigation)}
          [:& c/navigation-bullets
           {:slide slide
            :navigate navigate
            :total 3}]

          [:button {:on-click next
                    :class (stl/css :next-btn)} "Continue"]]]]]]

     1
     [:div {:class (stl/css-case :modal-overlay true)}
      [:div.animated {:class klass}
       [:div {:class (stl/css :modal-container)}
        [:img {:src "images/features/2.13-shadow-tokens.gif"
               :class (stl/css :start-image)
               :border "0"
               :alt "Shadow tokens: Reusable shadows, at last!"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "Shadow tokens: Reusable shadows, at last!"]]
         [:div {:class (stl/css :feature)}

          [:p {:class (stl/css :feature-content)}
           "With Shadow tokens, we’re introducing our second composite token, right after Typography tokens. This is a big step forward for design systems in Penpot."]

          [:p {:class (stl/css :feature-content)}
           "Until now, shadows couldn’t be defined as reusable styles the way colors could before color tokens existed. Shadow tokens change that. You can now create reusable, consistent shadows, made of one or multiple layers, fully tokenized and ready to scale across your designs."]

          [:p {:class (stl/css :feature-content)}
           "Each shadow can reference existing tokens or use custom values, supports both Drop Shadow and Inner Shadow, and even allows shadow tokens to reference other shadow tokens. A brand-new capability, unlocked."]]

         [:div {:class (stl/css :navigation)}

          [:& c/navigation-bullets
           {:slide slide
            :navigate navigate
            :total 2}]

          [:button {:on-click finish
                    :class (stl/css :next-btn)} "Let's go"]]]]]])))

