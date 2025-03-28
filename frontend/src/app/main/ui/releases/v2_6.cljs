;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.releases.v2-6
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.ui.releases.common :as c]
   [rumext.v2 :as mf]))

(defmethod c/render-release-notes "2.6"
  [{:keys [slide klass next finish navigate version]}]
  (mf/html
   (case slide
     :start
     [:div {:class (stl/css-case :modal-overlay true)}
      [:div.animated {:class klass}
       [:div {:class (stl/css :modal-container)}
        [:img {:src "images/features/2.6-slide-0.png"
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
           "Design Tokens make their debut in Penpot!"]

          [:p  {:class (stl/css :feature-content)}
           "Penpot is the first design tool to integrate native design
           tokens—a single source of truth to improve efficiency and
           collaboration between product design and development."]

          [:p  {:class (stl/css :feature-content)}
           "But that’s not all—we’ve also tackled improvements, bug fixes and optimizations."]

          [:p  {:class (stl/css :feature-content)}
           "Let’s dive in!"]]

         [:div {:class (stl/css :navigation)}
          [:button {:class (stl/css :next-btn)
                    :on-click next} "Continue"]]]]]]

     0
     [:div {:class (stl/css-case :modal-overlay true)}
      [:div.animated {:class klass}
       [:div {:class (stl/css :modal-container)}
        [:img {:src "images/features/2.6-tokens-1.gif"
               :class (stl/css :start-image)
               :border "0"
               :alt "Manage brands and themes across your design systems"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "Manage brands and themes across your design systems"]]

         [:div {:class (stl/css :feature)}
          [:p {:class (stl/css :feature-content)}
           "Create and manage different token types—Color, Opacity,
           Border Radius, Dimension, Sizing, Spacing, Rotation, and
           Stroke. And this is just the beginning—more token types are
           on the way!"]

          [:p {:class (stl/css :feature-content)}
           "Add values to your tokens, including references to other
           tokens (aliases) and even math operations to keep things
           dynamic and flexible."]

          [:p {:class (stl/css :feature-content)}
           "Use Themes and Sets for an efficient way to manage your
           design system across multiple dimensions—whether it’s
           brand, color schemes, devices, density, or anything else
           your product needs."]]

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
        [:img {:src "images/features/2.6-tokens-2.gif"
               :class (stl/css :start-image)
               :border "0"
               :alt "Open Source design tokens format"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "Open Source design tokens format"]]

         [:div {:class (stl/css :feature)}
          [:p {:class (stl/css :feature-content)}
           "Penpot adopts the W3C Design Tokens Community Group (DTCG)
           standard, ensuring maximum compatibility with a wide range
           of tools and technologies."]

          [:p {:class (stl/css :feature-content)}
           "With Penpot’s standardized design tokens format, you can
           easily reuse and sync tokens across different platforms,
           workflows, and disciplines. Import your existing tokens
           into Penpot—or export them for use anywhere else. Seamless
           interoperability by design through Open Source."]]

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
        [:img {:src "images/features/2.6-bubbles.gif"
               :class (stl/css :start-image)
               :border "0"
               :alt "Comments grouped by zoom level"}]

        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :modal-header)}
          [:h1 {:class (stl/css :modal-title)}
           "Comments grouped by zoom level"]]
         [:div {:class (stl/css :feature)}

          [:p {:class (stl/css :feature-content)}
           "When collaborating on files, feedback can quickly become
           dense and overwhelming, turning what should be information
           into visual noise. Now, comments are grouped based on your
           zoom level, giving the right level of visibility and making
           navigating feedback easier."]

          [:p {:class (stl/css :feature-content)}
           "When you’re zoomed out, comments are grouped to reduce
           clutter and keep your workspace clean. As you zoom in, the
           groups expand, revealing individual comments in
           context. This makes navigating feedback much smoother,
           especially in complex designs with lots of discussion."]]

         [:div {:class (stl/css :navigation)}

          [:& c/navigation-bullets
           {:slide slide
            :navigate navigate
            :total 3}]

          [:button {:on-click finish
                    :class (stl/css :next-btn)} "Let's go"]]]]]])))

