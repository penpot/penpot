;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.releases.v2-7
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.ui.releases.common :as c]
   [rumext.v2 :as mf]))

(defmethod c/render-release-notes "2.7"
  [{:keys [slide klass next finish navigate version]}]
  (mf/html
   (case slide
     :start
     [:div {:class (stl/css-case :modal-overlay true)}
      [:div.animated {:class klass}
       [:div {:class (stl/css :modal-container)}
        [:img {:src "images/features/2.7-slide-0.jpg"
               :class (stl/css :start-image)
               :border "0"
               :alt "Design Tokens make their debut in Penpot!"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "What’s new in Penpot?"]

          [:div {:class (stl/css :version-tag)}
           (dm/str "Version " version)]]

         [:div {:class (stl/css :features-block)}
          [:span {:class (stl/css :feature-title)}
           "Penpot 2.7 is out!"]

          [:p  {:class (stl/css :feature-content)}
           "After the huge excitement around our last release. The first-ever native Design Tokens support in a design tool (yay!), we’re keeping the momentum going with a fresh batch of new features and improvements."]

          [:p  {:class (stl/css :feature-content)}
           "This update brings the first set of upgrades to our new Design Tokens system, a few of the many to come. We’ve also expanded who can create sharing prototype links and improved the invitations area. Last but not least, we fixed a bunch of bugs and optimizations that will make the experience more enjoyable for all."]

          [:p  {:class (stl/css :feature-content)}
           "Let’s dive in!"]]

         [:div {:class (stl/css :navigation)}
          [:button {:class (stl/css :next-btn)
                    :on-click next} "Continue"]]]]]]

     0
     [:div {:class (stl/css-case :modal-overlay true)}
      [:div.animated {:class klass}
       [:div {:class (stl/css :modal-container)}
        [:img {:src "images/features/2.7-duplicate-set.gif"
               :class (stl/css :start-image)
               :border "0"
               :alt "Design Tokens improvements"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "Design Tokens improvements"]]

         [:div {:class (stl/css :feature)}
          [:p {:class (stl/css :feature-content)}
           "It hasn’t been long since we launched Design Tokens in Penpot (the first native Design Tokens support in a design tool!), and we’re already rolling out the first set of improvements."]

          [:p {:class (stl/css :feature-content)}
           "The highlight: you can now duplicate token sets directly from a menu item. A huge time-saver, especially when working from existing sets. We’ve also made it easier to create themes by letting you select their set right away, and we’ve polished some info indicators to make everything a bit clearer. Plus, we’ve fixed a bunch of early-stage bugs to keep things running smoothly."]]

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
        [:img {:src "images/features/2.7-share.gif"
               :class (stl/css :start-image)
               :border "0"
               :alt "Editors and viewers can now create Share prototype links"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "Editors and viewers can now create Share prototype links"]]

         [:div {:class (stl/css :feature)}
          [:p {:class (stl/css :feature-content)}
           "From now on, both editors and viewers can create Share Prototype links. Sharing prototypes is key for better team collaboration, no matter the role. It’s a common need, team members often have to share presentations without risking any accidental changes to the designs, which means they don’t necessarily need editing permissions. In the future, Penpot will introduce more fine-grained control over these permissions."]

          [:p {:class (stl/css :feature-content)}
           "This update gives editors and viewers the same ability to configure, create, copy, and delete sharing links. A capability that, until now, was limited to owners and admins."]]

         [:div {:class (stl/css :navigation)}
          [:& c/navigation-bullets
           {:slide slide
            :navigate navigate
            :total 3}]

          [:button {:on-click next
                    :class (stl/css :next-btn)} "Continue"]]]]]]

     2
     [:div {:class (stl/css-case :modal-overlay true)}
      [:div.animated {:class klass}
       [:div {:class (stl/css :modal-container)}
        [:img {:src "images/features/2.7-invitations.gif"
               :class (stl/css :start-image)
               :border "0"
               :alt "A clearer way to invite your first team members"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "A clearer way to invite your first team members"]]
         [:div {:class (stl/css :feature)}

          [:p {:class (stl/css :feature-content)}
           "Penpot works perfectly for solo projects, but it’s always more fun with a team. That’s why we’ve updated the initial state of the invitations area. Instead of starting blank, it now offers clearer guidance to help you invite your first team members."]

          [:p {:class (stl/css :feature-content)}
           "This improvement in design and UX writing comes from community member Prithvi Tharun (credit where it’s due!) Not all open source contributions are about code, and this is a fantastic example of how design and writing make a real difference too."]]

         [:div {:class (stl/css :navigation)}

          [:& c/navigation-bullets
           {:slide slide
            :navigate navigate
            :total 3}]

          [:button {:on-click finish
                    :class (stl/css :next-btn)} "Let's go"]]]]]])))

