;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.releases.v2-11
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.ui.releases.common :as c]
   [rumext.v2 :as mf]))

(defmethod c/render-release-notes "2.11"
  [{:keys [slide klass next finish navigate version]}]
  (mf/html
   (case slide
     :start
     [:div {:class (stl/css-case :modal-overlay true)}
      [:div.animated {:class klass}
       [:div {:class (stl/css :modal-container)}
        [:img {:src "images/features/2.11-slide-0.jpg"
               :class (stl/css :start-image)
               :border "0"
               :alt "Penpot 2.11 is here!"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "What’s new in Penpot?"]

          [:div {:class (stl/css :version-tag)}
           (dm/str "Version " version)]]

         [:div {:class (stl/css :features-block)}
          [:span {:class (stl/css :feature-title)}
           "Typography tokens take the stage!"]

          [:p  {:class (stl/css :feature-content)}
           "This release brings one of our most anticipated design system upgrades yet: Typography tokens."]

          [:p  {:class (stl/css :feature-content)}
           "But that’s not all. Variants get a nice boost with multi-switching, new creation shortcuts, and draggable property reordering. Invitations are now easier to manage and the user menu has been reorganized. Now showing your current Penpot version and direct access to release info."]

          [:p  {:class (stl/css :feature-content)}
           "And as always, you’ll notice performance improvements throughout. Faster, smoother, and just a bit more magical every time."]

          [:p  {:class (stl/css :feature-content)}
           "Let’s dive in!"]]

         [:div {:class (stl/css :navigation)}
          [:button {:class (stl/css :next-btn)
                    :on-click next} "Continue"]]]]]]

     0
     [:div {:class (stl/css-case :modal-overlay true)}
      [:div.animated {:class klass}
       [:div {:class (stl/css :modal-container)}
        [:img {:src "images/features/2.11-typography-token.gif"
               :class (stl/css :start-image)
               :border "0"
               :alt "Typography token: one token to rule your text"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "Typography token: one token to rule your text"]]

         [:div {:class (stl/css :feature)}
          [:p {:class (stl/css :feature-content)}
           "Imagine having just one token to manage all your typography. With the new Typography token, you can create presets that bundle all your text styles (font, weight, size, line height, spacing, and more) into a single reusable definition. Just one clean, flexible token to keep your type consistent across your designs."]

          [:p {:class (stl/css :feature-content)}
           "The Typography token also marks a big step forward for Penpot: it’s our first composite token! Composite tokens are special because they can hold multiple properties within one token. Shadow token will be the next composite token coming your way."]]

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
        [:img {:src "images/features/2.11-variants.gif"
               :class (stl/css :start-image)
               :border "0"
               :alt "Variants get a power-up"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "Variants get a power-up"]]
         [:div {:class (stl/css :feature)}

          [:p {:class (stl/css :feature-content)}
           "Variants just got published and they already got a serious quality-of-life boost!"]

          [:p {:class (stl/css :feature-content)}
           "- Switch several variant copies at once: No more clicking through each one individually when you want to update a property. Just select multiple copies and change their values in one go — fast, smooth, and efficient."]

          [:p {:class (stl/css :feature-content)}
           "- New ways to create variants, right from the design viewport: No need to dig through menus. The new buttons make it super quick to spin up variant sets directly where you’re working."]

          [:p {:class (stl/css :feature-content)}
           "- Reorder your component properties by drag & drop: Because organization matters, now you can arrange your properties however makes the most sense to you, so you can keep the ones you use most often right where you want them."]]

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
        [:img {:src "images/features/2.11-invitations.gif"
               :class (stl/css :start-image)
               :border "0"
               :alt "A smoother way to manage invitations"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "A smoother way to manage invitations"]]
         [:div {:class (stl/css :feature)}

          [:p {:class (stl/css :feature-content)}
           "The Invitations section just got a big usability upgrade for admins. Here’s what’s new:"]


          [:p {:class (stl/css :feature-content)}
           "Sorting - Organize invitations by role type or status to keep track of who’s in and who’s pending."]
          [:p {:class (stl/css :feature-content)}
           "Quicker actions - Main actions (resend and delete) are now visible upfront for quicker access."]
          [:p {:class (stl/css :feature-content)}
           "Bulk management - Select multiple invitations to resend or delete them all at once."]

          [:p {:class (stl/css :feature-content)}
           "Invited users will also get clearer emails, including a reminder sent one day before the invite expires (after seven days). Simple, clean, and much more efficient."]]

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
        [:img {:src "images/features/2.11-menu.gif"
               :class (stl/css :start-image)
               :border "0"
               :alt "User menu makeover"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "User menu makeover"]]
         [:div {:class (stl/css :feature)}

          [:p {:class (stl/css :feature-content)}
           "The user menu got a well-deserved cleanup. Options are now grouped into clear sections like Help & Learning and Community & Contributions, making navigation faster and easier."]

          [:p {:class (stl/css :feature-content)}
           "You’ll also notice a handy new detail: the menu now shows your current Penpot version and gives you quick access to changelog information. This is especially useful for self-hosted setups that want to stay in sync with the latest updates. Simple, organized, and more informative."]]

         [:div {:class (stl/css :navigation)}

          [:& c/navigation-bullets
           {:slide slide
            :navigate navigate
            :total 4}]

          [:button {:on-click finish
                    :class (stl/css :next-btn)} "Let's go"]]]]]])))

