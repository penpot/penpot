;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.releases.v2-10
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.ui.releases.common :as c]
   [rumext.v2 :as mf]))

(defmethod c/render-release-notes "2.10"
  [{:keys [slide klass next finish navigate version]}]
  (mf/html
   (case slide
     :start
     [:div {:class (stl/css-case :modal-overlay true)}
      [:div.animated {:class klass}
       [:div {:class (stl/css :modal-container)}
        [:img {:src "images/features/2.10-slide-0.jpg"
               :class (stl/css :start-image)
               :border "0"
               :alt "Penpot 2.10 is here!"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "What’s new in Penpot?"]

          [:div {:class (stl/css :version-tag)}
           (dm/str "Version " version)]]

         [:div {:class (stl/css :features-block)}
          [:span {:class (stl/css :feature-title)}
           "Variants have arrived in Penpot!"]

          [:p  {:class (stl/css :feature-content)}
           "We’re excited to introduce what’s (probably) the top community request. Variants are a powerful way to bring order and flexibility to your design system in Penpot."]

          [:p  {:class (stl/css :feature-content)}
           "This release also brings major progress on design tokens—including several new token types to help you manage typography at an entirely new level—plus a bunch of quality-of-life improvements that make the Penpot experience even more enjoyable."]

          [:p  {:class (stl/css :feature-content)}
           "Let’s dive in!"]]

         [:div {:class (stl/css :navigation)}
          [:button {:class (stl/css :next-btn)
                    :on-click next} "Continue"]]]]]]

     0
     [:div {:class (stl/css-case :modal-overlay true)}
      [:div.animated {:class klass}
       [:div {:class (stl/css :modal-container)}
        [:img {:src "images/features/2.10-variants.gif"
               :class (stl/css :start-image)
               :border "0"
               :alt "Variants have arrived in Penpot!"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "Variants have arrived in Penpot!"]]

         [:div {:class (stl/css :feature)}
          [:p {:class (stl/css :feature-content)}
           "With Variants, you can group similar components—like buttons, icons, or toggles—into a single, customizable component. Instead of juggling multiple versions for every state, size, or style, you’ll manage them all in one unified place with clear, intuitive properties."]

          [:p {:class (stl/css :feature-content)}
           "Imagine a single button component that seamlessly switches between primary and secondary styles, active and disabled states, and small to large sizes—all without leaving your flow. That’s the power of Variants."]

          [:p {:class (stl/css :feature-content)}
           "This release has been shaped by our amazing community. A huge thank-you to everyone who shared ideas, feedback, and insights to make Penpot Variants possible <3"]]

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
        [:img {:src "images/features/2.10-penpotfest.jpg"
               :class (stl/css :start-image)
               :border "0"
               :alt "Road to Penpot 3.0… Join us at Penpot Fest next month!"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "Road to Penpot 3.0… Join us at Penpot Fest next month!"]]
         [:div {:class (stl/css :feature)}

          [:p {:class (stl/css :feature-content)}
           "This year’s Penpot Fest theme is: Better, together: Refining the designer-developer workflow."]

          [:p {:class (stl/css :feature-content)}
           "Catch industry experts from GitHub, Blender, Wikimedia Foundation and more. Join workshops and our panel discussion for practical and visionary thoughts."]

          [:p {:class (stl/css :feature-content)}
           "And one more thing…Tune into our product showcase to see future plans and help us shape Penpot. Come for the insights, stay for the community…"]

          [:p {:class (stl/css :feature-content)}
           [:a {:href "https://penpot.app/penpotfest"
                :target "_blank"}
            "Get your tickets"]
           " now to join us 8-10 October, in Madrid!"]]

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
        [:img {:src "images/features/2.10-font-tokens.gif"
               :class (stl/css :start-image)
               :border "0"
               :alt "New typography token types"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "New typography token types"]]
         [:div {:class (stl/css :feature)}

          [:p {:class (stl/css :feature-content)}
           "We're continually building on Penpot's native Design Tokens support, and this time, we introduce an expanded range of token types, giving you greater options for your design systems."]

          [:p {:class (stl/css :feature-content)}
           "This latest update brings—no more no less than—six new token types, significantly boosting your ability to manage design decisions, particularly in typography: Font Family, Font Weight, Text Case, Text Decoration, Letter Spacing token, and Number token (for unitless values)."]]

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
        [:img {:src "images/features/2.10-preset.gif"
               :class (stl/css :start-image)
               :border "0"
               :alt "Select artboard defaults before their creation"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "Select artboard defaults before their creation"]]
         [:div {:class (stl/css :feature)}

          [:p {:class (stl/css :feature-content)}
           "Starting a new design in Penpot just got easier. You can now choose from preset artboard sizes before creating an artboard. This will help you begin your designs with the right dimensions from the very first click."]

          [:p {:class (stl/css :feature-content)}
           "This feature was born from community proposals. Several Penpot users helped us understand the different workflows around creating artboards, and their feedback shaped the simple, effective solution we’re announcing today."]]

         [:div {:class (stl/css :navigation)}

          [:& c/navigation-bullets
           {:slide slide
            :navigate navigate
            :total 4}]

          [:button {:on-click finish
                    :class (stl/css :next-btn)} "Let's go"]]]]]])))

