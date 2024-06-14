;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.releases.v2-0
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.ui.releases.common :as c]
   [rumext.v2 :as mf]))

;; TODO: Review all copies and alt text
(defmethod c/render-release-notes "2.0"
  [{:keys [slide klass next finish navigate version]}]
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
           "Welcome to Penpot 2.0! "]

          [:div {:class (stl/css :version-tag)}
           (dm/str "Version " version)]]

         [:div {:class (stl/css :features-block)}
          [:p {:class (stl/css :feature-content)}
           [:spam {:class (stl/css :feature-title)}
            "CSS Grid Layout: "]
           "Bring your designs to life, knowing that what you create is what developers code."]

          [:p  {:class (stl/css :feature-content)}
           [:spam {:class (stl/css :feature-title)}
            "Sleeker UI: "]
           "We’ve polished Penpot to make your experience smoother and more enjoyable."]

          [:p  {:class (stl/css :feature-content)}
           [:spam {:class (stl/css :feature-title)}
            "New Components System: "]
           "Managing and using your design components got a whole lot better."]

          [:p  {:class (stl/css :feature-content)}
           "And that’s not all - we’ve fined tuned performance and "
           "accessibility to give you a better and more fluid design experience."]

          [:p  {:class (stl/css :feature-content)}
           " Ready to dive in? Let 's get started!"]]

         [:div {:class (stl/css :navigation)}
          [:button {:class (stl/css :next-btn)
                    :on-click next} "Continue"]]]]]]

     0
     [:div {:class (stl/css-case :modal-overlay true)}
      [:div.animated {:class klass}
       [:div {:class (stl/css :modal-container)}
        [:img {:src "images/features/2.0-css-grid.gif"
               :class (stl/css :start-image)
               :border "0"
               :alt "Penpot's CSS Grid Layout"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "CSS Grid Layout - Design Meets Development"]]

         [:div {:class (stl/css :feature)}
          [:p {:class (stl/css :feature-content)}
           "The much-awaited Grid Layout introduces 2-dimensional"
           " layout capabilities to Penpot, allowing for the creation"
           " of adaptive layouts by leveraging the power of CSS properties."]

          [:p {:class (stl/css :feature-content)}
           "It’s a host of new features, including columns and"
           " rows management, flexible units such as FR (fractions),"
           " the ability to create and name areas, and tons of new "
           "and unique possibilities within a design tool."]

          [:p {:class (stl/css :feature-content)}
           "Designers will learn CSS basics while working, "
           "and as always with Penpot, developers can pick"
           " up the design as code to take it from there."]]

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
        [:img {:src "images/features/2.0-new-ui.gif"
               :class (stl/css :start-image)
               :border "0"
               :alt "Penpot's UI Makeover"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "UI Makeover -  Smoother, Sharper, and Simply More Fun"]]

         [:div {:class (stl/css :feature)}
          [:p {:class (stl/css :feature-content)}
           "We've completely overhauled Penpot's user interface. "
           "The improvements in consistency, the introduction of "
           "new microinteractions, and attention to countless details"
           " will significantly enhance the productivity and enjoyment of using Penpot."]
          [:p {:class (stl/css :feature-content)}
           "Furthermore, we’ve made several accessibility improvements, "
           "with better color contrast, keyboard navigation,"
           " and adherence to other best practices."]]

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
        [:img {:src "images/features/2.0-components.gif"
               :class (stl/css :start-image)
               :border "0"
               :alt "Penpot's new components system"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "New Components System"]]
         [:div {:class (stl/css :feature)}
          [:p {:class (stl/css :feature-content)}
           "The new Penpot components system improves"
           " control over instances, including their "
           "inheritances and properties overrides. "
           "Main components are now accessible as design"
           " elements, allowing a better updating "
           "workflow through instant changes synchronization."]
          [:p {:class (stl/css :feature-content)}
           "And that’s not all, there are new capabilities "
           "such as component swapping and annotations "
           "that will help you to better manage your design systems."]]

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
        [:img {:src "images/features/2.0-html.gif"
               :class (stl/css :start-image)
               :border "0"
               :alt " Penpot's  HTML code generator"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "And much more"]]
         [:div {:class (stl/css :feature)}
          [:p {:class (stl/css :feature-content)}
           "In addition to all of this, we’ve included several other requested improvements:"]
          [:ul {:class (stl/css :feature-list)}
           [:li "Access HTML markup code directly in inspect mode"]
           [:li "Images are now treated as element fills, maintaining their aspect ratio on resize, ideal for flexible designs"]
           [:li "Enjoy new color themes with options for both dark and light modes"]
           [:li "Feel the speed boost! Enjoy a smoother experience with a bunch of performance improvements"]]]

         [:div {:class (stl/css :navigation)}

          [:& c/navigation-bullets
           {:slide slide
            :navigate navigate
            :total 4}]

          [:button {:on-click finish
                    :class (stl/css :next-btn)} "Let's go"]]]]]])))

