;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.releases.v2-5
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.ui.releases.common :as c]
   [rumext.v2 :as mf]))

;; TODO: Review all copies and alt text
(defmethod c/render-release-notes "2.5"
  [{:keys [slide klass next finish navigate version]}]
  (mf/html
   (case slide
     :start
     [:div {:class (stl/css-case :modal-overlay true)}
      [:div.animated {:class klass}
       [:div {:class (stl/css :modal-container)}
        [:img {:src "images/features/2.5-slide-0.png"
               :class (stl/css :start-image)
               :border "0"
               :alt "A graphic illustration with Penpot style"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "What’s new in Penpot?"]

          [:div {:class (stl/css :version-tag)}
           (dm/str "Version " version)]]

         [:div {:class (stl/css :features-block)}
          [:span {:class (stl/css :feature-title)}
           "We’re thrilled to introduce Penpot 2.5"]

          [:p  {:class (stl/css :feature-content)}
           "Packed with powerful new features and little big details. This release brings multi-step gradients, along with comment notifications, making it easier than ever to communicate with your team members. Now you also can easily copy/paste groups of styles between layers and share direct links to specific boards, among other new capabilities considered true gems for designers and team collaboration."]

          [:p  {:class (stl/css :feature-content)}
           "But that’s not all—we’ve also tackled numerous bug fixes and optimizations."]

          [:p  {:class (stl/css :feature-content)}
           "Let’s dive in!"]]

         [:div {:class (stl/css :navigation)}
          [:button {:class (stl/css :next-btn)
                    :on-click next} "Continue"]]]]]]

     0
     [:div {:class (stl/css-case :modal-overlay true)}
      [:div.animated {:class klass}
       [:div {:class (stl/css :modal-container)}
        [:img {:src "images/features/2.5-gradients.gif"
               :class (stl/css :start-image)
               :border "0"
               :alt "Multi-step gradients and more"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "Multi-step gradients and more"]]

         [:div {:class (stl/css :feature)}
          [:p {:class (stl/css :feature-content)}
           "We’re so happy to bring you one of our most requested features—multi-step gradients! Now, you can create smooth, complex color transitions with multiple stops, giving you more creative options to customize your designs."]

          [:p {:class (stl/css :feature-content)}
           "And that’s not all. We’ve also added quick actions to flip and rotate gradients, plus now you can adjust the radius for radial gradients. More control, more flexibility, more fun."]]

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
        [:img {:src "images/features/2.5-mention.gif"
               :class (stl/css :start-image)
               :border "0"
               :alt "Comment mentions and manage notifications"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "Comment mentions and manage notifications"]]

         [:div {:class (stl/css :feature)}
          [:p {:class (stl/css :feature-content)}
           "No more lost comments! You can now tag teammates in comments, and they’ll get a notification so they never miss direct feedback. Plus, now you can filter mentions—just select 'Only your mentions' to quickly find discussions that matter to you."]

          [:p {:class (stl/css :feature-content)}
           "We’ve also added a new section in your profile where you can customize your notifications, choosing what to receive on your dashboard and via email. On top of that, comments got a UI refresh, making everything clearer and better organized. And this is just the first batch of improvements—expect even more comment-related upgrades in the next Penpot release."]]

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
        [:img {:src "images/features/2.5-copy.gif"
               :class (stl/css :start-image)
               :border "0"
               :alt "Copy/paste styles, CSS, and text"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "Copy/paste styles, CSS, and text"]]
         [:div {:class (stl/css :feature)}
          [:p {:class (stl/css :feature-content)}
           "Easily copy and apply styles across your designs with just a few clicks. With the new Copy/Paste options, you can quickly transfer fills, strokes, shadows, and other properties from one layer to another—or multiple layers at once. Reusing styles is no longer a repetitive task."]
          [:p {:class (stl/css :feature-content)}
           "And we’ve also added more copy options:"]
          [:p {:class (stl/css :feature-content)}
           "- 'Copy as CSS' to grab the code instantly."]
          [:p {:class (stl/css :feature-content)}
           "- 'Copy as text' if you just need the content."]
          [:p {:class (stl/css :feature-content)}
           "Less manual work for a faster workflow. We hope you find it as useful as we do."]]

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
        [:img {:src "images/features/2.5-link.gif"
               :class (stl/css :start-image)
               :border "0"
               :alt "Links to specific boards"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "Links to specific boards"]]
         [:div {:class (stl/css :feature)}
          [:p {:class (stl/css :feature-content)}
           "In a single Penpot file, it's common to have multiple individual screens or designs spread across different boards. Now, you can generate direct links to each board, making it easy to share them with team members or include direct links in documentation."]
          [:p {:class (stl/css :feature-content)}
           "No more navigating through the design workspace of a file to find a specific screen—just send a link and take your team straight to the intended board."]]

         [:div {:class (stl/css :navigation)}

          [:& c/navigation-bullets
           {:slide slide
            :navigate navigate
            :total 4}]

          [:button {:on-click finish
                    :class (stl/css :next-btn)} "Let's go"]]]]]])))

